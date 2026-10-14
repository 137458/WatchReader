package com.watchreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
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

    // 每物理档位滚动量：单档步长标定为 40px（约 1.5 行文字，细腻跟手）
    const val DEFAULT_STEP_PIXELS = 40f

    // 每物理档位对应的相对滚轮单位数（2026-09-25 OWW251 实机 getevent 标定）：
    // oplus_crown 上报的是高分辨率相对滚轮单位流而非档位脉冲 —— 约 12~13 单位 = 一个物理棘轮档位。
    const val CROWN_UNITS_PER_NOTCH = 12f

    // 空闲超时门限：停顿超过 250ms 视为手势结束，未满足一档门限的累积余量自动失效清零
    const val IDLE_TIMEOUT_MS = 250L

    // OPPO 官方系统默认每 24px 触发一次表冠齿轮微振 (crown_vibrator_instance)
    const val VIBRATION_THRESHOLD_PIXELS = 24f

    // 档位门限步进累加器（跨界面共享：严格累积达到 12 单位一档才步进并 1:1 触发微振）
    private val scrollStepGate = CrownStepGate(thresholdUnits = CROWN_UNITS_PER_NOTCH, idleTimeoutMs = IDLE_TIMEOUT_MS)

    // 齿轮微振距离门限节流器（保留供独立门限测试）
    private val gearTickGate = CrownTickGate()

    // 调速档位微振节流器（跨界面共享：每物理档位 12 单位累积一次微振，供 RSVP 速率 / 自动滚屏调速使用）
    private val adjustTickGate = CrownTickGate(thresholdPx = CROWN_UNITS_PER_NOTCH)

    // 惯性滑动启动最小累计位移门限：单次连续手势必须累计 >= 120px（接近 2 档），慢速微调与单档绝不触发
    internal const val FLING_MIN_DISPLACEMENT_PX = 120f
    // 惯性起振速度门限（px/s）
    internal const val FLING_MIN_VELOCITY_PX_S = 600f
    private const val FLING_MAX_VELOCITY_PX_S = 2000f
    // 手势停顿判定时间（松手延时）
    internal const val FLING_SETTLE_DELAY_MS = 35L
    // 手势空闲超时：超过 40ms 无事件视为前一次手势完全结束，重置累计位移与速度窗口
    private const val GESTURE_IDLE_TIMEOUT_MS = 40L

    private var gestureStartMs = 0L
    private var gestureLastEventMs = 0L
    private var gestureAccumPx = 0f
    private var lastEstimatedVelocity = 0f

    // 供 JVM 单测断言 Fling 触发
    internal var flingStartProbe: ((Float) -> Unit)? = null

    private var settleSurface: Any? = null
    private var settleContext: Context? = null
    private var settlePending = false
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // 时间源（可注入：JVM 单测用假时钟驱动节流与重武装逻辑）
    internal var timeSourceMs: () -> Long = { android.os.SystemClock.uptimeMillis() }

    // 振感观测探针（叠加通知，不改变生产路径；供 JVM 单测断言振感触发时机）
    internal var gearTickProbe: ((Context?, View?) -> Unit)? = null

    /** 重置跨界面共享的振感节流状态（JVM 单测隔离用；生产勿调） */
    internal fun resetFeedbackGatesForTest() {
        scrollStepGate.reset()
        gearTickGate.reset()
        adjustTickGate.reset()
        gestureStartMs = 0L
        gestureLastEventMs = 0L
        gestureAccumPx = 0f
        lastEstimatedVelocity = 0f
        settleSurface = null
        settleContext = null
        settlePending = false
        flingStartProbe = null
        stopFlingInternal()
        timeSourceMs = { android.os.SystemClock.uptimeMillis() }
    }

    /**
     * 齿轮档位微振反馈：每产生一档步进严格触发 1 次齿轮微振
     */
    private fun emitNotchHaptic(context: Context?, view: View?) {
        gearTickProbe?.invoke(context, view)
        RotaryHapticManager.performScrollTick(context, view)
    }

    /**
     * 24px 距离门限齿轮微振（保留供历史调用兼容）
     */
    private fun emitGearTick(distancePx: Float, context: Context?, view: View?) {
        if (gearTickGate.onScroll(distancePx)) {
            emitNotchHaptic(context, view)
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
     * 执行表冠滚动分发（Compose 滚动容器）：齿轮档位对齐模式 + 快甩惯性启动
     * 慢转微调严格档位对齐；大幅快甩（>= 120px 且速度达标）在松手后触发顺滑惯性滑动。
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        if (abs(rawOrPixelDelta) < 0.0001f) return
        abortFling(scrollState)

        val notches = scrollStepGate.onDelta(rawOrPixelDelta, timeSourceMs())
        if (notches != 0) {
            val scrollPixels = notches * DEFAULT_STEP_PIXELS
            // 触底/触顶静默钳制：到达边界后滚动安全停止，不响任何振感
            if (!((scrollPixels > 0f && !scrollState.canScrollForward) ||
                (scrollPixels < 0f && !scrollState.canScrollBackward))
            ) {
                scrollState.dispatchRawDelta(scrollPixels)
                emitNotchHaptic(context, view)
            }
        }

        trackGestureMotion(rawOrPixelDelta, scrollState, context)
    }

    /**
     * 无滚动内容页面的表冠空转反馈（如无线传书页）：齿轮档位对齐微振
     */
    fun dispatchIdleTick(rawOrPixelDelta: Float, context: Context?, view: View? = null) {
        if (abs(rawOrPixelDelta) < 0.0001f) return
        val notches = scrollStepGate.onDelta(rawOrPixelDelta, timeSourceMs())
        if (notches != 0) {
            emitNotchHaptic(context, view)
        }
    }

    /**
     * 调速场景（自动滚屏调速 / RSVP 速率）的表冠档位微振
     */
    fun dispatchAdjustTick(rawDeltaUnits: Float, context: Context?, view: View? = null) {
        if (abs(rawDeltaUnits) < 0.0001f) return
        if (adjustTickGate.onScroll(rawDeltaUnits)) {
            emitNotchHaptic(context, view)
        }
    }

    /**
     * 原生 Android ListView 表冠滚动分发（目录 / 书签 / 选卷）：齿轮档位对齐模式 + 快甩惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        listView: ListView,
        context: Context?,
        view: View? = null
    ) {
        if (abs(rawOrPixelDelta) < 0.0001f) return
        abortFling(listView)

        val notches = scrollStepGate.onDelta(rawOrPixelDelta, timeSourceMs())
        if (notches != 0) {
            val scrollPixels = notches * DEFAULT_STEP_PIXELS
            if (listView.canScrollList(if (scrollPixels > 0f) 1 else -1)) {
                listView.scrollListBy(scrollPixels.toInt())
                emitNotchHaptic(context, view)
            }
        }

        trackGestureMotion(rawOrPixelDelta, listView, context)
    }

    /**
     * 原生 Android ScrollView 表冠滚动分发（阅读页）：齿轮档位对齐 + 即时直接位移 + 快甩惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollView: ScrollView,
        context: Context?,
        view: View? = null
    ) {
        if (abs(rawOrPixelDelta) < 0.0001f) return
        abortFling(scrollView)

        val notches = scrollStepGate.onDelta(rawOrPixelDelta, timeSourceMs())
        if (notches != 0) {
            val scrollPixels = notches * DEFAULT_STEP_PIXELS
            val maxScroll = maxScrollOf(scrollView)
            if (!((scrollPixels > 0f && scrollView.scrollY >= maxScroll) ||
                (scrollPixels < 0f && scrollView.scrollY <= 0))
            ) {
                scrollView.scrollBy(0, scrollPixels.toInt())
                emitNotchHaptic(context, view)
            }
        }

        trackGestureMotion(rawOrPixelDelta, scrollView, context)
    }

    // ═══ 惯性滑动工具与状态清理（[ADR-017] 齿轮档位模式 + 动力学快甩惯性引擎）═══

    // 速度估计公式：窗口累计位移 × 700 / 窗口毫秒数（launcher 原实现即此系数）
    private const val VELOCITY_ESTIMATE_FACTOR = 700f

    private fun trackGestureMotion(deltaUnits: Float, surface: Any, context: Context?) {
        val now = timeSourceMs()
        if (now - gestureLastEventMs > GESTURE_IDLE_TIMEOUT_MS) {
            gestureStartMs = now
            gestureAccumPx = 0f
            lastEstimatedVelocity = 0f
        }
        gestureLastEventMs = now

        val deltaPx = normalizeToPixels(deltaUnits)
        gestureAccumPx += deltaPx

        val elapsed = now - gestureStartMs
        if (elapsed >= 20L) {
            lastEstimatedVelocity = computeFlingVelocity(gestureAccumPx, elapsed)
        }

        // 仅当累计位移达到了最小甩动手势门限（>= 120px，约 2 档以上）时，才计划松冠 settleCheck
        if (abs(gestureAccumPx) >= FLING_MIN_DISPLACEMENT_PX) {
            if (settlePending) {
                mainHandler.removeCallbacks(settleCheck)
            }
            settleSurface = surface
            settleContext = context
            settlePending = true
            mainHandler.postDelayed(settleCheck, FLING_SETTLE_DELAY_MS)
        } else {
            // 位移不足（精细微调或单档），取消任何 pending 的 settle，确保绝对手停即停
            if (settlePending) {
                mainHandler.removeCallbacks(settleCheck)
                settlePending = false
            }
        }
    }

    private val settleCheck = Runnable {
        performSettle()
    }

    internal fun triggerSettleForTest() {
        performSettle()
    }

    private fun performSettle() {
        settlePending = false
        val surface = settleSurface ?: return
        val context = settleContext
        val velocity = lastEstimatedVelocity
        val totalDisplacement = gestureAccumPx
        settleSurface = null
        settleContext = null

        if (abs(totalDisplacement) >= FLING_MIN_DISPLACEMENT_PX && abs(velocity) >= FLING_MIN_VELOCITY_PX_S) {
            flingStartProbe?.invoke(velocity)
            if (context != null) {
                startFling(surface, velocity, context)
            }
        }
    }

    private var flingScroller: OverScroller? = null
    private var flingSurface: Any? = null
    private var flingLastPos = 0f
    private var flingRunnable: Runnable? = null
    private var flingFrameCallback: Choreographer.FrameCallback? = null
    private val mainChoreographer: Choreographer by lazy { Choreographer.getInstance() }

    private fun startFling(surface: Any, velocity: Float, context: Context) {
        stopFlingInternal()
        val scroller = flingScroller ?: OverScroller(context).also { flingScroller = it }
        when (surface) {
            is ScrollView -> {
                scroller.fling(0, surface.scrollY, 0, velocity.toInt(), 0, 0, 0, maxScrollOf(surface))
                flingLastPos = surface.scrollY.toFloat()
            }
            else -> {
                scroller.fling(0, 0, 0, velocity.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
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
                        surface.scrollTo(0, scroller.currY)
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
            else -> {
                val callback = Choreographer.FrameCallback { step.run() }
                flingFrameCallback = callback
                mainChoreographer.postFrameCallback(callback)
            }
        }
    }

    /** 速度估计（纯函数，可测）：累计位移 × 700 / 毫秒 → px/s，钳制 ±2500 */
    internal fun computeFlingVelocity(accumDeltaPx: Float, windowMs: Long): Float {
        if (windowMs <= 0L) return 0f
        return (accumDeltaPx * VELOCITY_ESTIMATE_FACTOR / windowMs)
            .coerceIn(-FLING_MAX_VELOCITY_PX_S, FLING_MAX_VELOCITY_PX_S)
    }

    /**
     * ScrollView 真实最大滚动量：滚动范围延伸进上下 padding 区（clipToPadding=false），
     * 等价于 scrollTo 内部 clamp 的 child.height - (height - padTop - padBottom)
     */
    private fun maxScrollOf(scrollView: ScrollView): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return maxOf(0, child.bottom + scrollView.paddingBottom - scrollView.height)
    }

    /** 终止惯性滑动（新表冠事件 / 章节切换恢复滚动位置时调用） */
    fun abortFling(surface: Any?) {
        if (surface == null || flingSurface === surface) {
            stopFlingInternal()
        }
    }

    private fun stopFlingInternal() {
        flingScroller?.forceFinished(true)
        flingFrameCallback?.let(mainChoreographer::removeFrameCallback)
        flingFrameCallback = null
        flingRunnable?.let { r ->
            val view = flingSurface as? View
            if (view != null) view.removeCallbacks(r) else mainHandler.removeCallbacks(r)
        }
        flingRunnable = null
        flingSurface = null
    }
}

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
 * 表冠物理棘轮档位门限步进器
 *
 * 机械棘轮手感核心（[ADR-017]）：
 * 1. 严格累加原始输入增量，满 1 档（thresholdUnits）才分发步进，并 1:1 伴随齿轮微振；
 * 2. 未达 1 档门限时绝不下发滚动、绝不触发微振，杜绝微动偷跑与抽搐；
 * 3. 消费整档后余量跨事件保留，支持快转与连续慢转；
 * 4. 停顿超过 idleTimeoutMs 自动清零余量，模拟机械棘轮静止回弹。
 */
class CrownStepGate(
    val thresholdUnits: Float = CrownScrollHelper.CROWN_UNITS_PER_NOTCH,
    val idleTimeoutMs: Long = CrownScrollHelper.IDLE_TIMEOUT_MS
) {
    private var accumulator = 0f
    private var lastEventMs = 0L

    /**
     * 喂入一次表冠旋转增量（向下/顺时针为正，向上/逆时针为负）
     * @return 达到门限的档位数（未满足门限时返回 0，满足时返回对应正负档位数）
     */
    fun onDelta(delta: Float, nowMs: Long): Int {
        if (abs(delta) < 0.0001f) return 0
        if (nowMs - lastEventMs > idleTimeoutMs) {
            accumulator = 0f
        }
        lastEventMs = nowMs
        accumulator += delta

        val notches = (accumulator / thresholdUnits).toInt()
        if (notches != 0) {
            accumulator -= notches * thresholdUnits
            return notches
        }
        return 0
    }

    fun reset() {
        accumulator = 0f
        lastEventMs = 0L
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
