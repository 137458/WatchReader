package com.watchreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewConfiguration
import android.widget.ListView
import android.widget.OverScroller
import android.widget.ScrollView
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.gestures.ScrollableState

/**
 * 表冠滚动的 Android 执行层：把 [CrownScrollArithmetic] 的纯判定落到真实容器、马达与惯性引擎
 *
 * 分派顺序与手感口径与 [CrownScrollHelper] 的 KDoc 一致，本对象只持有"会随手指/时间变化"的
 * 可变状态（两个门限节流器、手势速度窗口、OverScroller 惯性状态），跨界面共享。
 * 单测经 [CrownScrollHelper.resetFeedbackGatesForTest] 复位本对象的全部状态。
 */
internal object CrownScrollDispatcher {

    private const val GESTURE_IDLE_TIMEOUT_MS = 25L
    private const val GESTURE_MIN_WINDOW_MS = 20L

    // 调速档位微振节流器（跨界面共享：每物理档位 12 单位累积一次微振，供 RSVP 速率 / 自动滚屏调速使用）
    private val adjustTickGate = CrownTickGate(thresholdPx = CrownScrollHelper.CROWN_UNITS_PER_NOTCH)

    // 齿轮微振距离门限节流器：与系统列表 mDelta 一致，有符号累积、满门限触发并保留余量
    private val gearTickGate = CrownTickGate(
        thresholdPx = CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS,
        signed = true
    )

    private val gestureWindow = CrownGestureWindow(
        idleTimeoutMs = GESTURE_IDLE_TIMEOUT_MS,
        minWindowMs = GESTURE_MIN_WINDOW_MS
    )

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 执行表冠滚动分发（Compose 滚动容器）：系统列表同款连续滚动 + 距离门限微振 + 惯性
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        val scrollPixels = CrownScrollHelper.scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        abortFling(scrollState)

        // 触底/触顶静默钳制：用容器真实消费量判定，未消费即已抵边界 ——
        // 不累加振感距离（余量原样保留）、不进惯性，避免贴边空转把振感节奏推乱
        if (!CrownScrollArithmetic.canDispatch(
                scrollPixels,
                scrollState.canScrollForward,
                scrollState.canScrollBackward
            )
        ) return
        val consumed = scrollState.dispatchRawDelta(scrollPixels.toFloat())
        if (consumed == 0f) return
        if (gearTickGate.onScroll(consumed)) {
            emitNotchHaptic(context, view)
        }
        trackGestureMotion(consumed, scrollState, context)
    }

    /**
     * 调速场景（自动滚屏调速 / RSVP 速率）的表冠档位微振
     */
    fun dispatchAdjustTick(rawDeltaUnits: Float, context: Context?, view: View? = null) {
        if (CrownScrollArithmetic.isNegligible(rawDeltaUnits)) return
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
        val scrollPixels = CrownScrollHelper.scrollPixelsFor(rawOrPixelDelta)
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
        val scrollPixels = CrownScrollHelper.scrollPixelsFor(rawOrPixelDelta)
        if (scrollPixels == 0) return
        abortFling(scrollView)
        // 手指甩动的惯性在 ScrollView 自己的 OverScroller 里，与本模块的惯性是两套引擎。
        // 表冠事件到来时先停掉原生甩动，否则两条 scrollBy 每帧抢同一份 scrollY，表现就是转动时跳动
        stopTouchFling(scrollView)

        val maxScroll = ScrollMetrics.maxScrollY(scrollView)
        val atBoundary = CrownScrollArithmetic.atScrollViewBoundary(scrollPixels, scrollView.scrollY, maxScroll)
        if (!atBoundary) {
            scrollView.scrollBy(0, scrollPixels)
            if (gearTickGate.onScroll(scrollPixels.toFloat())) {
                emitNotchHaptic(context, view)
            }
            trackGestureMotion(scrollPixels.toFloat(), scrollView, context)
        }
    }

    /**
     * 终止 ScrollView 内部的手指甩动惯性（0 速度 fling 会接管并立即结束当前动画）
     *
     * `fling(0)` 是本机 ScrollView 唯一公开的"取消当前甩动"入口：它重置内部 OverScroller，
     * 后续 computeScroll 拿到 finished 即停止，不会产生额外位移。
     */
    private fun stopTouchFling(scrollView: ScrollView) {
        if (ScrollMetrics.maxScrollY(scrollView) <= 0) return
        scrollView.fling(0)
    }

    /**
     * 读取系统滚动系数（ViewConfiguration.getScaledVerticalScrollFactor，API 26+）。
     */
    fun ensureSystemScrollFactor(context: Context?) {
        if (context == null) return
        try {
            val factor = ViewConfiguration.get(context.applicationContext).scaledVerticalScrollFactor
            if (factor > 0f) {
                CrownScrollHelper.scrollFactorPxPerUnit = factor
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
                CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS.toInt()
            )
            if (threshold > 0) {
                gearTickGate.thresholdPx = threshold.toFloat()
                Log.i("CrownScroll", "crown vibrator threshold = $threshold px")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 齿轮微振反馈：由距离门限节流后触发 1 次原厂 302 齿轮微振
     */
    private fun emitNotchHaptic(context: Context?, view: View?) {
        CrownScrollTestHooks.gearTickProbe?.invoke(context, view)
        RotaryHapticManager.performScrollTick(context, view)
    }

    private fun trackGestureMotion(deltaPx: Float, surface: Any, context: Context?) {
        if (deltaPx == 0f) return
        val now = CrownScrollTestHooks.timeSourceMs()
        val sample = gestureWindow.record(deltaPx, now) ?: return
        val velocity = CrownScrollHelper.computeFlingVelocity(sample.accumPx, sample.windowMs)
        if (!CrownScrollArithmetic.reachesFlingThreshold(
                velocity,
                CrownScrollHelper.FLING_MIN_VELOCITY_PX_S
            )
        ) return

        CrownScrollTestHooks.flingStartProbe?.invoke(velocity)
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
                scroller.fling(0, surface.scrollY, 0, velocity.toInt(), 0, 0, 0, ScrollMetrics.maxScrollY(surface))
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
                        if (surface.scrollY <= 0 || surface.scrollY >= ScrollMetrics.maxScrollY(surface)) {
                            stopFlingInternal()
                            return
                        }
                    }
                    is ListView -> {
                        if (CrownScrollArithmetic.reachedEdge(
                                delta,
                                surface.canScrollList(1),
                                surface.canScrollList(-1)
                            )
                        ) {
                            stopFlingInternal()
                            return
                        }
                        surface.scrollListBy(delta.toInt())
                    }
                    is ScrollableState -> {
                        if (CrownScrollArithmetic.reachedEdge(
                                delta,
                                surface.canScrollForward,
                                surface.canScrollBackward
                            )
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

    /** 复位两个门限节流器、手势速度窗口与惯性状态（JVM 单测隔离用；生产勿调） */
    @VisibleForTesting
    internal fun resetForTest() {
        gearTickGate.reset()
        adjustTickGate.reset()
        gearTickGate.thresholdPx = CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS
        gestureWindow.reset()
        stopFlingInternal()
        CrownScrollTestHooks.reset()
    }
}

/**
 * 表冠内核的测试注入点（JVM 单测专用）
 *
 * 时间源与观测探针集中在此单点，生产对象只读、不承载可写测试状态；
 * 唯一写入方是 JVM 单测与 [CrownScrollHelper.resetFeedbackGatesForTest]。
 */
internal object CrownScrollTestHooks {

    /** 时间源：默认系统 uptime，单测替换为假时钟以驱动惯性重武装与速度窗口 */
    @VisibleForTesting
    var timeSourceMs: () -> Long = { SystemClock.uptimeMillis() }

    /** 振感观测探针（叠加通知，不改变生产路径） */
    @VisibleForTesting
    var gearTickProbe: ((Context?, View?) -> Unit)? = null

    /** 惯性起振观测探针 */
    @VisibleForTesting
    var flingStartProbe: ((Float) -> Unit)? = null

    /** 恢复默认值（单测隔离） */
    @VisibleForTesting
    fun reset() {
        timeSourceMs = { SystemClock.uptimeMillis() }
        gearTickProbe = null
        flingStartProbe = null
    }
}
