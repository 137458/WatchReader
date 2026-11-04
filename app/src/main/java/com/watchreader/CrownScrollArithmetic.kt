package com.watchreader

import kotlin.math.abs

/**
 * 表冠滚动的纯算术内核（零 Android import，可纯 JVM 单测）
 *
 * 只做判定与换算，不持有全局可变状态：门限余量归 [CrownTickGate]，速度窗口归
 * [CrownGestureWindow]，接线与 Android 容器交互归 [CrownScrollHelper] / CrownScrollDispatcher。
 * 标定值（系统滚动系数、0.5 阻尼、24px 门限、500px/s 起振、±2000 钳制）由调用方注入，
 * 避免纯算术层与系统内核标定各存一份而漂移。
 */
internal object CrownScrollArithmetic {

    /** 轴增量噪声下限：小于该值视作无转动（系统事件抖动） */
    const val DELTA_EPSILON = 0.0001f

    /** 增量是否小到可以忽略 */
    fun isNegligible(rawDelta: Float): Boolean = abs(rawDelta) < DELTA_EPSILON

    /**
     * 单事件滚动像素：先乘系统滚动系数、再乘阻尼；四舍五入后不足 1px 时按方向补足 1px
     */
    fun scrollPixelsFor(rawDelta: Float, factorPxPerUnit: Float, damping: Float): Int {
        if (isNegligible(rawDelta)) return 0
        val full = Math.round(rawDelta * factorPxPerUnit)
        val damped = Math.round(full * damping)
        return if (damped == 0) (if (rawDelta > 0f) 1 else -1) else damped
    }

    /** 速度估计：窗口累计位移 × 系数 / 窗口毫秒数，钳制 ±maxVelocityPxS；窗口无效返回 0 */
    fun flingVelocity(
        accumDeltaPx: Float,
        windowMs: Long,
        estimateFactor: Float,
        maxVelocityPxS: Float
    ): Float {
        if (windowMs <= 0L) return 0f
        return (accumDeltaPx * estimateFactor / windowMs)
            .coerceIn(-maxVelocityPxS, maxVelocityPxS)
    }

    /** 速度是否达到起振门限（含恰等） */
    fun reachesFlingThreshold(velocity: Float, minVelocityPxS: Float): Boolean =
        abs(velocity) >= minVelocityPxS

    /**
     * 事件方向是否还有可滚余量：触底/触顶时本事件整段静默丢弃
     * （不累加振感距离、不进惯性，余量原样保留）
     */
    fun canDispatch(
        scrollPixels: Int,
        canScrollForward: Boolean,
        canScrollBackward: Boolean
    ): Boolean = if (scrollPixels > 0) canScrollForward else canScrollBackward

    /** 原生 ScrollView 边界判定：本事件方向已贴边 */
    fun atScrollViewBoundary(scrollPixels: Int, scrollY: Int, maxScrollY: Int): Boolean =
        (scrollPixels > 0 && scrollY >= maxScrollY) || (scrollPixels < 0 && scrollY <= 0)

    /** 惯性步进方向的贴边判定：该方向已无余量则终止惯性 */
    fun reachedEdge(delta: Float, canScrollForward: Boolean, canScrollBackward: Boolean): Boolean =
        (delta > 0f && !canScrollForward) || (delta < 0f && !canScrollBackward)
}

/**
 * 表冠手势速度窗口：静默超过 idleTimeoutMs 即重开窗口（一次拨动 = 一个独立手势）
 *
 * 逐字搬移自 [CrownScrollHelper] 的 trackGestureMotion 记账部分：入账一次位移，
 * 观测窗口不足 minWindowMs 时不给样本（避免除零与抖动放大）。
 */
internal class CrownGestureWindow(
    private val idleTimeoutMs: Long,
    private val minWindowMs: Long
) {

    private var startMs = 0L
    private var lastEventMs = 0L
    private var accumPx = 0f

    /** 一次入账后的速度窗口样本：窗口内累计位移与窗口毫秒数 */
    class Sample(val accumPx: Float, val windowMs: Long)

    /** 入账一次实际滚动位移；返回 null 表示本事件不构成有效速度样本 */
    fun record(deltaPx: Float, nowMs: Long): Sample? {
        if (deltaPx == 0f) return null
        if (nowMs - lastEventMs > idleTimeoutMs) {
            startMs = nowMs
            accumPx = 0f
        }
        lastEventMs = nowMs
        accumPx += deltaPx

        val elapsed = nowMs - startMs
        if (elapsed < minWindowMs) return null
        return Sample(accumPx, elapsed)
    }

    fun reset() {
        startMs = 0L
        lastEventMs = 0L
        accumPx = 0f
    }
}

/**
 * 表冠齿轮微振距离门限节流器
 *
 * [signed] = true 时复刻系统列表 `mDelta` 的有符号累积：反向转动抵消余量、满门限立即触发，
 * 触发后只保留超出部分的余量（整体清零会把越过门限的那段距离丢掉，快转时振感节奏变稀）；
 * [signed] = false 时按绝对值累积并保留余量，供 RSVP / 自动滚屏调速的档位微振使用。
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
            accumulator += distancePx
            if (abs(accumulator) < thresholdPx) return false
            // carry：扣掉一个门限，剩余距离留给下一次触发
            accumulator -= if (accumulator > 0f) thresholdPx else -thresholdPx
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
