package com.watchreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 * 核心对齐（逆向自本机系统列表内核，与设置 / 桌面同源）：
 * 1. 【输入源与极性】SOURCE_ROTARY_ENCODER 使用 AXIS_SCROLL 原值；oplus_crown（SOURCE_MOUSE /
 *    SOURCE_CLASS_POINTER）使用 AXIS_VSCROLL 取反。统一为顺时针向下为正 (+)，逆时针向上为负 (-)。
 * 2. 【连续滚动】每个事件按 ViewConfiguration 系统滚动系数的 50% 立即下发（至少 1px），
 *    不再按物理档位量子化步进；转动过程与屏幕位移 1:1 跟手。
 * 3. 【刻度累加与振感】对齐 OPPO 系统 mDelta / crown_vibrator_instance（默认 24px）：
 *    按实际滚动像素有符号累积，门限判断先于累加、触发即清零，触发一次原厂 302 齿轮微振；
 *    触底/触顶静默，不响振感；马达投递采用 remove-before 语义。
 * 4. 【惯性】累计位移 × 700 / 窗口毫秒估算速度，超过系统最小起振速度 500px/s 即启动
 *    OverScroller 惯性；任何新表冠事件立即中止惯性恢复跟手，手势数据 25ms 无事件后终止。
 */
object CrownScrollHelper {

    // 每物理档位对应的相对滚轮单位数（2026-09-25 OWW251 实机 getevent 标定）：
    // oplus_crown 上报的是高分辨率相对滚轮单位流而非档位脉冲 —— 约 12~13 单位 = 一个物理棘轮档位。
    // 该标定仅保留给 RSVP / 自动滚屏调速的档位微振，不再参与滚动位移换算。
    const val CROWN_UNITS_PER_NOTCH = 12f

    // 系统列表内核的滚动阻尼：原生每事件只消费系统滚动系数的一半
    internal const val NATIVE_SCROLL_DAMPING = 0.5f

    // OPPO 官方系统默认每 24px 触发一次表冠齿轮微振 (crown_vibrator_instance)
    const val VIBRATION_THRESHOLD_PIXELS = 24f

    // 惯性起振速度门限（px/s）：对齐系统 RecyclerView 的 minFlingVelocity
    internal const val FLING_MIN_VELOCITY_PX_S = 500f
    private const val FLING_MAX_VELOCITY_PX_S = 2000f
    // 手势空闲超时：与系统列表 postTerminatingSmoothScrollRunnable 的 25ms 一致
    private const val GESTURE_IDLE_TIMEOUT_MS = 25L

    // 调速档位微振节流器（跨界面共享：每物理档位 12 单位累积一次微振，供 RSVP 速率 / 自动滚屏调速使用）
    private val adjustTickGate = CrownTickGate(thresholdPx = CROWN_UNITS_PER_NOTCH)

    // 齿轮微振距离门限节流器：与系统列表 mDelta 一致，有符号累积、过门限即清零
    private val gearTickGate = CrownTickGate(
        thresholdPx = VIBRATION_THRESHOLD_PIXELS,
        signed = true
    )

    private var gestureStartMs = 0L
    private var gestureLastEventMs = 0L
    private var gestureAccumPx = 0f

    // 供 JVM 单测断言 Fling 触发
    internal var flingStartProbe: ((Float) -> Unit)? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // 时间源（可注入：JVM 单测用假时钟驱动节流与重武装逻辑）
    internal var timeSourceMs: () -> Long = { android.os.SystemClock.uptimeMillis() }

    // 振感观测探针（叠加通知，不改变生产路径；供 JVM 单测断言振感触发时机）
    internal var gearTickProbe: ((Context?, View?) -> Unit)? = null

    /** 重置跨界面共享的振感节流状态（JVM 单测隔离用；生产勿调） */
    internal fun resetFeedbackGatesForTest() {
        gearTickGate.reset()
        adjustTickGate.reset()
        gearTickGate.thresholdPx = VIBRATION_THRESHOLD_PIXELS
        gestureStartMs = 0L
        gestureLastEventMs = 0L
        gestureAccumPx = 0f
        flingStartProbe = null
        stopFlingInternal()
        timeSourceMs = { android.os.SystemClock.uptimeMillis() }
        scrollFactorPxPerUnit = DEFAULT_SCROLL_FACTOR_PX_PER_UNIT
    }

    /**
     * 齿轮微振反馈：由距离门限节流后触发 1 次原厂 302 齿轮微振
     */
    private fun emitNotchHaptic(context: Context?, view: View?) {
        gearTickProbe?.invoke(context, view)
        RotaryHapticManager.performScrollTick(context, view)
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
     *
     * 系统列表内核按输入源区分轴向：旋转编码器读 AXIS_SCROLL 原值，
     * oplus_crown 鼠标类读 AXIS_VSCROLL 并取反。
     */
    fun extractCrownDelta(event: MotionEvent): Float {
        if ((event.source and InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (abs(scroll) > 0.0001f) return scroll
        }
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (abs(vertical) > 0.0001f) return -vertical
        return 0f
    }

    /**
     * 单事件滚动像素：对齐系统 RecyclerView 的换算——先乘系统滚动系数，
     * 再乘 0.5 阻尼；结果不足 1px 时按方向补足 1px，保证微小转动也跟手。
     */
    internal fun scrollPixelsFor(rawDelta: Float): Int {
        if (abs(rawDelta) < 0.0001f) return 0
        val full = Math.round(rawDelta * scrollFactorPxPerUnit)
        val damped = Math.round(full * NATIVE_SCROLL_DAMPING)
        return if (damped == 0) (if (rawDelta > 0f) 1 else -1) else damped
    }

    // 系统滚动系数（px/轴单位）：与系统应用共用同一缩放，保证全设备灵敏度一致。
    // 启动时由 ensureSystemScrollFactor 覆盖；默认值仅供 JVM 单测兜底。
    internal const val DEFAULT_SCROLL_FACTOR_PX_PER_UNIT = 2f

    @Volatile
    internal var scrollFactorPxPerUnit = DEFAULT_SCROLL_FACTOR_PX_PER_UNIT

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
     * 读取系统表冠振感门限 crown_vibrator_instance（默认 24px），与设置 / 桌面同源。
     */
    fun ensureSystemHapticThreshold(context: Context?) {
        if (context == null) return
        try {
            val threshold = Settings.System.getInt(
                context.applicationContext.contentResolver,
                "crown_vibrator_instance",
                VIBRATION_THRESHOLD_PIXELS.toInt()
            )
            if (threshold > 0) {
                gearTickGate.thresholdPx = threshold.toFloat()
                Log.i("CrownScroll", "crown vibrator threshold = $threshold px")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 执行表冠滚动分发（Compose 滚动容器）：系统列表同款连续滚动 + 距离门限微振 + 惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        abortFling(scrollState)

        // 触底/触顶静默钳制：到达边界后滚动安全停止，不响任何振感
        val canScroll = if (scrollPixels > 0) scrollState.canScrollForward else scrollState.canScrollBackward
        if (canScroll) {
            scrollState.dispatchRawDelta(scrollPixels.toFloat())
            if (gearTickGate.onScroll(scrollPixels.toFloat())) {
                emitNotchHaptic(context, view)
            }
            trackGestureMotion(scrollPixels.toFloat(), scrollState, context)
        }
    }

    /**
     * 无滚动内容页面的表冠空转反馈（如无线传书页）：按系统滚动距离门限累积微振
     */
    fun dispatchIdleTick(rawOrPixelDelta: Float, context: Context?, view: View? = null) {
        val scrollPixels = scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        if (gearTickGate.onScroll(scrollPixels.toFloat())) {
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
     * 原生 Android ListView 表冠滚动分发（目录 / 书签 / 选卷）：连续滚动 + 距离门限微振 + 惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        listView: ListView,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        abortFling(listView)

        if (listView.canScrollList(if (scrollPixels > 0) 1 else -1)) {
            listView.scrollListBy(scrollPixels)
            if (gearTickGate.onScroll(scrollPixels.toFloat())) {
                emitNotchHaptic(context, view)
            }
            trackGestureMotion(scrollPixels.toFloat(), listView, context)
        }
    }

    /**
     * 原生 Android ScrollView 表冠滚动分发（阅读页）：连续滚动 + 距离门限微振 + 惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollView: ScrollView,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        abortFling(scrollView)

        val maxScroll = maxScrollOf(scrollView)
        val atBoundary = (scrollPixels > 0 && scrollView.scrollY >= maxScroll) ||
            (scrollPixels < 0 && scrollView.scrollY <= 0)
        if (!atBoundary) {
            scrollView.scrollBy(0, scrollPixels)
            if (gearTickGate.onScroll(scrollPixels.toFloat())) {
                emitNotchHaptic(context, view)
            }
            trackGestureMotion(scrollPixels.toFloat(), scrollView, context)
        }
    }

    // ═══ 惯性滑动工具与状态清理（ADR-019 系统列表同款连续滚动 + 速度阈值惯性）═══

    // 速度估计公式：窗口累计位移 × 700 / 窗口毫秒数（launcher 原实现即此系数）
    private const val VELOCITY_ESTIMATE_FACTOR = 700f

    private fun trackGestureMotion(deltaPx: Float, surface: Any, context: Context?) {
        if (deltaPx == 0f) return
        val now = timeSourceMs()
        if (now - gestureLastEventMs > GESTURE_IDLE_TIMEOUT_MS) {
            gestureStartMs = now
            gestureAccumPx = 0f
        }
        gestureLastEventMs = now
        gestureAccumPx += deltaPx

        val elapsed = now - gestureStartMs
        if (elapsed < 20L) return
        val velocity = computeFlingVelocity(gestureAccumPx, elapsed)
        if (abs(velocity) < FLING_MIN_VELOCITY_PX_S) return

        flingStartProbe?.invoke(velocity)
        if (context != null) {
            startFling(surface, velocity, context)
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

    /** 速度估计（纯函数，可测）：累计位移 × 700 / 毫秒 → px/s，钳制 ±2000（系统同源） */
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
 * 为原生 ListView 统一安装表冠滚动管线（连续滚动 + 距离门限齿轮微振 + 静默边界钳制）
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
 * [signed] = true 时复刻系统列表 `mDelta` 语义：有符号累积、门限判断发生在累加之前、
 * 触发时整体清零（不保留余量）；[signed] = false 时按绝对值累积并保留余量，
 * 供 RSVP / 自动滚屏调速的档位微振使用。
 */
class CrownTickGate(
    var thresholdPx: Float = CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS,
    private val signed: Boolean = false
) {

    private var accumulator = 0f

    /** 累加滚动距离；返回 true 表示本次应触发一次齿轮微振 */
    fun onScroll(distancePx: Float): Boolean {
        if (distancePx == 0f) return false
        if (signed) {
            if (abs(accumulator) < thresholdPx) {
                accumulator += distancePx
                return false
            }
            accumulator = 0f
            return true
        }
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
