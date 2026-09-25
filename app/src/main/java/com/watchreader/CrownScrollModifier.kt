package com.watchreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ListView
import android.widget.OverScroller
import android.widget.ScrollView
import androidx.compose.foundation.gestures.ScrollableState
import kotlin.math.abs

/**
 * OPPO Watch (ColorOS Watch / Wear OS) 表冠转动与触觉交互管理器
 *
 * 核心对齐（逆向自 OPPO 官方 HeyLauncher）：
 * 1. 【输入源与极性】精准识别 oplus_crown (SOURCE_MOUSE / SOURCE_CLASS_POINTER) 与 SOURCE_ROTARY_ENCODER。
 * 2. 【极性归一化】AXIS_VSCROLL 负号取反，统一为：顺时针向下翻页为正 (+)，逆时针向上回退为负 (-)。
 * 3. 【刻度累加与振感】对齐 OPPO 系统 crown_vibrator_instance 默认 24px 门限，位移达到阈值即触发原厂 302 齿轮微振。
 * 4. 【单位标定】oplus_crown 上报高分辨率相对滚轮单位流（见 CROWN_UNITS_PER_NOTCH 实测注释），
 *    全量线性换算为像素；Compose ScrollState 语义 value += delta（正 = 向下），与原生 smoothScrollBy(+px) 同向。
 */
object CrownScrollHelper {

    // 每物理档位滚动量：对齐系统 app 每档滚动量（约 3 行文字）
    const val DEFAULT_STEP_PIXELS = 64f

    // 每物理档位对应的相对滚轮单位数（2026-09-25 OWW251 实机 getevent 标定）：
    // oplus_crown 上报的是高分辨率相对滚轮单位流而非档位脉冲 —— 慢转约 56 事件/s（单事件值 1~5），
    // 快甩单事件可达 ±50+，单次轻拨净位移约 13 单位。约 12~13 单位 = 一个物理棘轮档位。
    // 历史版本按 |值| ≤ 2.5 视为档位的启发式与本机编码完全不匹配：多数事件落入像素透传分支
    // 造成爬行慢滚（调步进常量无效的直接原因），少数小值事件又放大成 64/128px 突跳。
    const val CROWN_UNITS_PER_NOTCH = 12f

    // OPPO 官方系统默认每 24px 触发一次表冠齿轮微振 (crown_vibrator_instance)
    const val VIBRATION_THRESHOLD_PIXELS = 24f

    // 齿轮微振距离门限节流器（跨界面共享：24px 累积一次微振，对齐 OPPO crown_vibrator_instance）
    private val gearTickGate = CrownTickGate()

    // 调速档位微振节流器（跨界面共享：每物理档位 12 单位累积一次微振，供 RSVP 速率 / 自动滚屏调速使用）
    private val adjustTickGate = CrownTickGate(thresholdPx = CROWN_UNITS_PER_NOTCH)

    @Volatile
    private var lastCrownEventMs = 0L

    // 时间源（可注入：JVM 单测用假时钟驱动节流与重武装逻辑）
    internal var timeSourceMs: () -> Long = { android.os.SystemClock.uptimeMillis() }

    // 振感观测探针（叠加通知，不改变生产路径；供 JVM 单测断言振感触发时机）
    internal var gearTickProbe: ((Context?, View?) -> Unit)? = null

    /** 重置跨界面共享的振感节流状态（JVM 单测隔离用；生产勿调） */
    internal fun resetFeedbackGatesForTest() {
        gearTickGate.reset()
        adjustTickGate.reset()
        lastCrownEventMs = 0L
        velocityWindowStartMs = 0L
        velocityAccumPx = 0f
        lastEstimatedVelocity = 0f
        timeSourceMs = { android.os.SystemClock.uptimeMillis() }
    }

    /**
     * 每个表冠事件开头调用：维护惯性速度窗口（停顿超过 25ms 视为一次手势结束，清零累计位移）
     */
    private fun beginCrownEvent() {
        val now = timeSourceMs()
        if (now - lastCrownEventMs > VELOCITY_WINDOW_RESET_MS) {
            velocityWindowStartMs = now
            velocityAccumPx = 0f
        }
        lastCrownEventMs = now
    }

    /**
     * 24px 距离门限齿轮微振：按滚动位移计振，杜绝快速转动时逐事件粘连振感
     */
    private fun emitGearTick(distancePx: Float, context: Context?, view: View?) {
        if (gearTickGate.onScroll(distancePx)) {
            gearTickProbe?.invoke(context, view)
            RotaryHapticManager.performScrollTick(context, view)
        }
    }

    /**
     * 判断 MotionEvent 是否为表冠滚动事件
     */
    fun isCrownScrollEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val source = event.source
        return (source and InputDevice.SOURCE_ROTARY_ENCODER) == InputDevice.SOURCE_ROTARY_ENCODER ||
                (source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }

    /**
     * 极性归一化提取表冠旋转增量（向下滚动为正数 +，向上滚动为负数 -）
     */
    fun extractCrownDelta(event: MotionEvent): Float {
        val axisScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val axisGeneric = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        val rawDelta = if (abs(axisScroll) > 0.0001f) axisScroll else axisGeneric
        return -rawDelta
    }

    /**
     * 表冠增量归一化为像素滚动量（纯函数，便于测试）
     *
     * 像素 = 原始轴单位 × 系统滚动系数（getScaledVerticalScrollFactor，与 HeyLauncher 同源），
     * 未初始化前用实机标定兜底值。禁止再做档位/像素启发式分流 —— 本机编码是高分辨率单位流，
     * 任何按事件绝对值分流的写法都会造成慢转爬行、快甩飞滚。
     */
    fun normalizeToPixels(rawDelta: Float): Float {
        if (abs(rawDelta) < 0.0001f) return 0f
        return rawDelta * scrollFactorPxPerUnit
    }

    // 系统滚动系数（px/轴单位）：与系统桌面共用同一缩放，保证全设备灵敏度一致
    @Volatile
    internal var scrollFactorPxPerUnit = DEFAULT_STEP_PIXELS / CROWN_UNITS_PER_NOTCH

    /**
     * 读取系统滚动系数（ViewConfiguration.getScaledVerticalScrollFactor，API 26+）。
     * Activity onCreate 时调用一次；首次表冠分发也会惰性补调。
     */
    fun ensureSystemScrollFactor(context: Context?) {
        if (context == null) return
        try {
            val factor = ViewConfiguration.get(context.applicationContext).scaledVerticalScrollFactor
            if (factor > 0f) {
                scrollFactorPxPerUnit = factor
                Log.i("CrownScroll", "system vertical scroll factor = $factor px/unit")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 执行表冠滚动分发：精准物理移动 + 24px 累加原厂 302 齿轮微振
     *
     * @param scrollPixels 像素滚动量（顺时针向下为正数，逆时针向上为负数）
     * @param scrollState Compose 可滚动状态（LazyListState / ScrollState）
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = normalizeToPixels(rawOrPixelDelta)
        if (abs(scrollPixels) < 0.001f) return
        abortFling(scrollState)
        cancelPendingSettle()
        beginCrownEvent()

        // 触底/触顶静默钳制：到达边界后滚动安全停止，不响任何振感（产品决策）
        if ((scrollPixels > 0f && !scrollState.canScrollForward) ||
            (scrollPixels < 0f && !scrollState.canScrollBackward)
        ) {
            return
        }

        // 顺时针旋转向下滚动：ScrollState 语义 value += delta（正 = 向下翻页），
        // 与阅读页原生 smoothScrollBy(0, +pixels) 同向；线性标定后单事件仅数像素，本机 60Hz 事件流本身就是平滑源，逐事件动画只会引入抖动
        scrollState.dispatchRawDelta(scrollPixels)

        // 24px 距离门限齿轮微振（不再逐事件无脑触发，保证不同转速下振感节奏一致）
        emitGearTick(scrollPixels, context, view)
        scheduleSettle(scrollState, scrollPixels, context)
    }

    /**
     * 无滚动内容页面的表冠空转反馈（如无线传书页）：按系统 app 惯例
     * 每档给出齿轮微振，保持全设备一致的表冠手感，而非完全无响应
     */
    fun dispatchIdleTick(rawOrPixelDelta: Float, context: Context?, view: View? = null) {
        val scrollPixels = normalizeToPixels(rawOrPixelDelta)
        if (abs(scrollPixels) < 0.001f) return
        emitGearTick(scrollPixels, context, view)
    }

    /**
     * 调速场景（自动滚屏调速 / RSVP 速率）的表冠档位微振：按原始表冠单位累积，
     * 每物理档位（12 单位）恰好一振，杜绝历史版本逐事件震动在快速转动时的振感粘连。
     * 与滚动页的 24px 门限并列，构成全应用统一的表冠振感节奏。
     */
    fun dispatchAdjustTick(rawDeltaUnits: Float, context: Context?, view: View? = null) {
        if (abs(rawDeltaUnits) < 0.0001f) return
        if (adjustTickGate.onScroll(rawDeltaUnits)) {
            gearTickProbe?.invoke(context, view)
            RotaryHapticManager.performScrollTick(context, view)
        }
    }

    /**
     * 原生 Android ListView 表冠滚动分发（目录 / 书签 / 选卷）
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        listView: ListView,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = normalizeToPixels(rawOrPixelDelta)
        if (abs(scrollPixels) < 0.001f) return
        abortFling(listView)
        cancelPendingSettle()
        beginCrownEvent()

        // 触底/触顶静默钳制（原生 canScrollList 判定，无需等待滚动回调）
        if (!listView.canScrollList(if (scrollPixels > 0f) 1 else -1)) {
            return
        }

        listView.scrollListBy(scrollPixels.toInt())
        emitGearTick(scrollPixels, context, view)
        scheduleSettle(listView, scrollPixels, context)
    }

    /**
     * 原生 Android ScrollView 表冠滚动分发（阅读页）：1:1 跟手 + 齿轮微振 + 松冠惯性滑动
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollView: ScrollView,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = normalizeToPixels(rawOrPixelDelta)
        if (abs(scrollPixels) < 0.001f) return
        // 新事件终止惯性滑动（对齐 launcher：转动期永远 1:1 跟手）
        abortFling(scrollView)
        beginCrownEvent()

        // 触底/触顶静默钳制（必须含上下 padding：clipToPadding=false 时滚动范围延伸进 padding 区，
        // 漏掉 padding 会让边界提前触发，表现为"表冠滚不到章节底部"）
        val maxScroll = maxScrollOf(scrollView)
        if ((scrollPixels > 0f && scrollView.scrollY >= maxScroll) ||
            (scrollPixels < 0f && scrollView.scrollY <= 0)
        ) {
            return
        }

        scrollView.smoothScrollBy(0, scrollPixels.toInt())
        emitGearTick(scrollPixels, context, view)
        scheduleSettle(scrollView, scrollPixels, context)
    }

    // ═══ 惯性滑动（逆向 HeyLauncher：速度 = 窗口累计位移 × 700 / 毫秒，500 起振、±2000 上限）═══
    // 通用化至全部滚动面：原生 ScrollView（阅读页）/ 原生 ListView（目录） / Compose ScrollState（菜单、书架）

    // 速度估计公式：窗口累计位移 × 700 / 窗口毫秒数（launcher 原实现即此系数）
    private const val VELOCITY_ESTIMATE_FACTOR = 700f

    // fling 起振门限与上限（launcher：f4415x0 = 500，钳制 ±2000）
    internal const val FLING_MIN_VELOCITY_PX_S = 500f
    private const val FLING_MAX_VELOCITY_PX_S = 2000f

    // 停顿超过 25ms（launcher 的 settle 延迟）视为一次手势结束，清零速度窗口
    private const val VELOCITY_WINDOW_RESET_MS = 25L

    private var velocityWindowStartMs = 0L
    private var velocityAccumPx = 0f
    private var lastEstimatedVelocity = 0f

    // 松冠延迟武装：最后一次事件 25ms 后仍无新事件才启动 fling。
    // launcher 在转动期即时武装 fling（事件间隙 fling 额外贡献位移），实机表现为快速段越滚越快；
    // 延迟到松冠后启动，转动期保持纯 1:1 跟手，手感与惯性两不误
    private var settleSurface: Any? = null
    private var settleContext: Context? = null
    private var settlePending = false
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val settleCheck = Runnable {
        settlePending = false
        val surface = settleSurface ?: return@Runnable
        val context = settleContext
        val velocity = lastEstimatedVelocity
        settleSurface = null
        settleContext = null
        if (abs(velocity) > FLING_MIN_VELOCITY_PX_S) {
            startFling(surface, velocity, context)
        }
    }

    private var flingScroller: OverScroller? = null
    private var flingSurface: Any? = null
    private var flingLastPos = 0f
    private var flingRunnable: Runnable? = null

    /** 速度估计（纯函数，可测）：累计位移 × 700 / 毫秒 → px/s，钳制 ±2000 */
    internal fun computeFlingVelocity(accumDeltaPx: Float, windowMs: Long): Float {
        if (windowMs <= 0L) return 0f
        return (accumDeltaPx * VELOCITY_ESTIMATE_FACTOR / windowMs)
            .coerceIn(-FLING_MAX_VELOCITY_PX_S, FLING_MAX_VELOCITY_PX_S)
    }

    private fun scheduleSettle(surface: Any, scrollPixels: Float, context: Context?) {
        velocityAccumPx += scrollPixels
        val elapsed = timeSourceMs() - velocityWindowStartMs
        if (elapsed > 0L) {
            lastEstimatedVelocity = computeFlingVelocity(velocityAccumPx, elapsed)
        }
        if (context == null) return // JVM 单测与无上下文路径不起惯性
        settleSurface = surface
        settleContext = context
        settlePending = true
        mainHandler.postDelayed(settleCheck, VELOCITY_WINDOW_RESET_MS)
    }

    private fun cancelPendingSettle() {
        if (settlePending) {
            mainHandler.removeCallbacks(settleCheck)
            settlePending = false
        }
    }

    /**
     * ScrollView 真实最大滚动量：滚动范围延伸进上下 padding 区（clipToPadding=false），
     * 等价于 scrollTo 内部 clamp 的 child.height - (height - padTop - padBottom)
     */
    private fun maxScrollOf(scrollView: ScrollView): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return maxOf(0, child.bottom + scrollView.paddingBottom - scrollView.height)
    }

    private fun startFling(surface: Any, velocity: Float, context: Context?) {
        stopFlingInternal()
        val ctx = context ?: return
        val scroller = flingScroller ?: OverScroller(ctx).also { flingScroller = it }
        when (surface) {
            is ScrollView -> {
                // 有界 fling：OverScroller 自行钳制在内容范围内
                scroller.fling(0, surface.scrollY, 0, velocity.toInt(), 0, 0, 0, maxScrollOf(surface))
                flingLastPos = surface.scrollY.toFloat()
            }
            else -> {
                scroller.fling(
                    0, 0, 0, velocity.toInt(),
                    Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE
                )
                flingLastPos = 0f
            }
        }
        flingSurface = surface
        val step = object : Runnable {
            override fun run() {
                if (flingRunnable !== this || flingSurface !== surface) return
                if (!scroller.computeScrollOffset()) {
                    stopFlingInternal()
                    return
                }
                val delta = scroller.currY.toFloat() - flingLastPos
                flingLastPos = scroller.currY.toFloat()
                when (surface) {
                    is ScrollView -> {
                        surface.scrollTo(0, scroller.currY.toInt())
                        if (surface.scrollY <= 0 || surface.scrollY >= maxScrollOf(surface)) {
                            stopFlingInternal()
                            return
                        }
                    }
                    is ListView -> {
                        if ((delta > 0f && !surface.canScrollList(1)) ||
                            (delta < 0f && !surface.canScrollList(-1))
                        ) {
                            stopFlingInternal()
                            return
                        }
                        surface.scrollListBy(delta.toInt())
                    }
                    is ScrollableState -> {
                        if ((delta > 0f && !surface.canScrollForward) ||
                            (delta < 0f && !surface.canScrollBackward)
                        ) {
                            stopFlingInternal()
                            return
                        }
                        surface.dispatchRawDelta(delta)
                    }
                }
                postFlingFrame(surface, this)
            }
        }
        flingRunnable = step
        postFlingFrame(surface, step)
    }

    private fun postFlingFrame(surface: Any, step: Runnable) {
        when (surface) {
            is View -> surface.postOnAnimation(step)
            else -> mainHandler.postDelayed(step, 16L)
        }
    }

    /** 终止惯性滑动（新表冠事件 / 章节切换恢复滚动位置时调用） */
    fun abortFling(surface: Any?) {
        if (surface == null || flingSurface === surface) {
            stopFlingInternal()
        }
    }

    private fun stopFlingInternal() {
        flingScroller?.forceFinished(true)
        flingRunnable?.let { r ->
            val view = flingSurface as? View
            if (view != null) view.removeCallbacks(r) else mainHandler.removeCallbacks(r)
        }
        flingRunnable = null
        flingSurface = null
    }
}

/**
 * 扩展方法：MotionEvent 是否为表冠滚动
 */
fun MotionEvent.isCrownScrollEvent(): Boolean = CrownScrollHelper.isCrownScrollEvent(this)

/**
 * 扩展方法：获取 MotionEvent 表冠滚动增量
 */
fun MotionEvent.getCrownScrollDelta(): Float = CrownScrollHelper.extractCrownDelta(this)

/**
 * 为原生 ListView 统一安装表冠滚动管线（线性步进 + 24px 门限齿轮微振 + 301 边界振感）
 *
 * 目录 / 书签 / 选卷三处 ListView 曾各自内联同一段 setOnGenericMotionListener，
 * 统一收敛到本扩展，杜绝三份拷贝在后续调参时漂移。
 */
fun ListView.bindCrownScroll(context: Context) {
    setOnGenericMotionListener { v, event ->
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            CrownScrollHelper.dispatchScroll(CrownScrollHelper.extractCrownDelta(event), this, context, v)
            true
        } else {
            false
        }
    }
}




/**
 * 表冠齿轮微振距离门限节流器
 *
 * 对齐 OPPO crown_vibrator_instance：按滚动位移累积计振，而非逐事件触发，
 * 保证不同转速（事件密度）下振感节奏一致。
 */
class CrownTickGate(private val thresholdPx: Float = CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS) {

    private var accumulator = 0f

    /** 累加滚动距离；返回 true 表示本次应触发一次齿轮微振（并已扣除门限，保留余量） */
    fun onScroll(distancePx: Float): Boolean {
        if (distancePx == 0f) return false
        accumulator += abs(distancePx)
        if (accumulator < thresholdPx) return false
        accumulator %= thresholdPx
        return true
    }

    fun reset() {
        accumulator = 0f
    }
}

/**
 * 页面级表冠滚动目标：由非原生滚动容器的 Compose 页面（书架 / 菜单 / 无线传书等）实现
 *
 * 与阅读页（原生 ScrollView 自持 OnGenericMotionListener）、目录页（原生 ListView）并列的
 * 第三条表冠路径：Activity 顶层管线在 RSVP 调速分支之后优先寻址，彻底取代
 * "1dp 隐形焦点锚点"方案 —— 后者依赖原生焦点存活，触点命中与焦点迁移都会使事件丢失，
 * 造成表冠无响应、无振感。
 */
fun interface CrownScrollTarget {
    /**
     * 处理一次表冠旋转增量（已经 CrownScrollHelper 极性归一化：顺时针向下为正）
     *
     * @return true 表示本次增量已消费，Activity 管线终止继续分发
     */
    fun onCrownDelta(delta: Float): Boolean
}

/**
 * 表冠滚动目标注册表（当前前台页面唯一持有者）
 */
object CrownScrollTargetRegistry {

    @Volatile
    var active: CrownScrollTarget? = null
        private set

    fun activate(target: CrownScrollTarget) {
        active = target
    }

    /** 惰性注销：仅当仍是自己时清除，避免页面切换过渡期误清后到目标 */
    fun deactivate(target: CrownScrollTarget) {
        if (active === target) {
            active = null
        }
    }
}
