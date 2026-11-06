package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠纯算术内核测试：换算、速度、边界判定、手势窗口、距离门限
 *
 * 全部为纯 JVM 断言（不碰 Android 系统服务）：把原先藏在 MotionEvent 接线里的
 * 判定与记账抽出来后，这些边界才第一次可以直接测——反向抵消到零、门限恰等、
 * 余量跨事件结转、静默超时重开窗口。
 */
class CrownScrollArithmeticTest {

    // 标定值取自 CrownScrollHelper：系统滚动系数 2f、阻尼 0.5、24px 门限、500px/s 起振、±2000 钳制
    private val factor = CrownScrollHelper.DEFAULT_SCROLL_FACTOR_PX_PER_UNIT
    private val damping = CrownScrollHelper.NATIVE_SCROLL_DAMPING
    private val thresholdPx = CrownScrollHelper.VIBRATION_THRESHOLD_PIXELS
    private val minFlingVelocity = CrownScrollHelper.FLING_MIN_VELOCITY_PX_S
    private val estimateFactor = 700f
    private val maxVelocity = 2000f

    @Test
    fun scrollPixelsFollowSystemListFormula() {
        // 6 单位 × 2 = 12，再 × 0.5 = 6px；55 单位 → 110 → 55px
        assertEquals(6, CrownScrollArithmetic.scrollPixelsFor(6f, factor, damping))
        assertEquals(55, CrownScrollArithmetic.scrollPixelsFor(55f, factor, damping))
        assertEquals(-13, CrownScrollArithmetic.scrollPixelsFor(-13f, factor, damping))
    }

    @Test
    fun scrollFactorScalesLinearly() {
        // 同一增量在更高系统滚动系数下线性放大：4f → 24 → 12px，1f → 6 → 3px
        assertEquals(12, CrownScrollArithmetic.scrollPixelsFor(6f, 4f, damping))
        assertEquals(3, CrownScrollArithmetic.scrollPixelsFor(6f, 1f, damping))
    }

    @Test
    fun subPixelRotationGetsOnePixelTowardDirection() {
        // 取整后为 0 时按方向补 1px，保证微小转动跟手且方向不错
        assertEquals(1, CrownScrollArithmetic.scrollPixelsFor(0.1f, factor, damping))
        assertEquals(-1, CrownScrollArithmetic.scrollPixelsFor(-0.1f, factor, damping))
    }

    @Test
    fun negligibleDeltaYieldsNoScroll() {
        assertEquals(0, CrownScrollArithmetic.scrollPixelsFor(0f, factor, damping))
        assertEquals(0, CrownScrollArithmetic.scrollPixelsFor(0.00005f, factor, damping))
        assertTrue(CrownScrollArithmetic.isNegligible(0.00005f))
        assertFalse(CrownScrollArithmetic.isNegligible(0.5f))
    }

    @Test
    fun flingVelocityEstimatesFromWindowAccumulation() {
        // 300px / 200ms × 700 = 1050px/s
        assertEquals(
            1050f,
            CrownScrollArithmetic.flingVelocity(300f, 200L, estimateFactor, maxVelocity),
            0.01f
        )
        assertEquals(
            -700f,
            CrownScrollArithmetic.flingVelocity(-100f, 100L, estimateFactor, maxVelocity),
            0.01f
        )
    }

    @Test
    fun flingVelocityClampsToSystemMaximum() {
        assertEquals(2000f, CrownScrollArithmetic.flingVelocity(10000f, 1000L, estimateFactor, maxVelocity), 0.01f)
        assertEquals(-2000f, CrownScrollArithmetic.flingVelocity(-10000f, 1000L, estimateFactor, maxVelocity), 0.01f)
    }

    @Test
    fun invalidWindowYieldsZeroVelocity() {
        // 窗口毫秒数非正（首事件 / 时钟回退）不得除零放大
        assertEquals(0f, CrownScrollArithmetic.flingVelocity(300f, 0L, estimateFactor, maxVelocity), 0.01f)
        assertEquals(0f, CrownScrollArithmetic.flingVelocity(300f, -5L, estimateFactor, maxVelocity), 0.01f)
    }

    @Test
    fun slowRotationStaysBelowFlingThreshold() {
        // 640px / 2000ms × 700 = 224px/s < 500px/s：慢转不惯性
        val slow = CrownScrollArithmetic.flingVelocity(640f, 2000L, estimateFactor, maxVelocity)
        assertFalse(CrownScrollArithmetic.reachesFlingThreshold(slow, minFlingVelocity))
    }

    @Test
    fun flingThresholdIncludesExactBoundaryAndSign() {
        assertTrue(CrownScrollArithmetic.reachesFlingThreshold(500f, minFlingVelocity))
        assertTrue(CrownScrollArithmetic.reachesFlingThreshold(-500f, minFlingVelocity))
        assertFalse(CrownScrollArithmetic.reachesFlingThreshold(499.99f, minFlingVelocity))
    }

    @Test
    fun canDispatchFollowsEventDirection() {
        // 只有本事件方向还有余量才下发；触底时向上仍需可下发
        assertTrue(CrownScrollArithmetic.canDispatch(1, canScrollForward = true, canScrollBackward = false))
        assertTrue(CrownScrollArithmetic.canDispatch(-1, canScrollForward = false, canScrollBackward = true))
        assertFalse(CrownScrollArithmetic.canDispatch(1, canScrollForward = false, canScrollBackward = true))
        assertFalse(CrownScrollArithmetic.canDispatch(-1, canScrollForward = true, canScrollBackward = false))
    }

    @Test
    fun scrollViewBoundaryUsesBothEdges() {
        // 顶部 scrollY = 0 / 底部 scrollY = maxScrollY 各只静默一个方向
        assertTrue(CrownScrollArithmetic.atScrollViewBoundary(1, scrollY = 100, maxScrollY = 100))
        assertFalse(CrownScrollArithmetic.atScrollViewBoundary(1, scrollY = 99, maxScrollY = 100))
        assertTrue(CrownScrollArithmetic.atScrollViewBoundary(-1, scrollY = 0, maxScrollY = 100))
        assertFalse(CrownScrollArithmetic.atScrollViewBoundary(-1, scrollY = 1, maxScrollY = 100))
    }

    @Test
    fun reachedEdgeDetectsExhaustedDirection() {
        assertTrue(CrownScrollArithmetic.reachedEdge(12f, canScrollForward = false, canScrollBackward = true))
        assertFalse(CrownScrollArithmetic.reachedEdge(12f, canScrollForward = true, canScrollBackward = false))
        assertTrue(CrownScrollArithmetic.reachedEdge(-12f, canScrollForward = true, canScrollBackward = false))
        assertFalse(CrownScrollArithmetic.reachedEdge(-12f, canScrollForward = false, canScrollBackward = true))
    }

    @Test
    fun gestureWindowWithholdsSampleUntilMinWindow() {
        val window = CrownGestureWindow(idleTimeoutMs = 25L, minWindowMs = 20L)
        // 首事件 elapsed = 0，不足 20ms 不给样本
        assertNull(window.record(10f, 1000L))
        val sample = window.record(10f, 1020L)!!
        assertEquals(20f, sample.accumPx, 0.01f)
        assertEquals(20L, sample.windowMs)
    }

    @Test
    fun gestureWindowRestartsAfterIdleTimeout() {
        val window = CrownGestureWindow(idleTimeoutMs = 25L, minWindowMs = 20L)
        window.record(10f, 1000L)
        assertEquals(20f, window.record(10f, 1020L)!!.accumPx, 0.01f)
        // 静默 35ms > 25ms：窗口重开，累计位移清零，本事件 elapsed = 0 仍不给样本
        assertNull(window.record(5f, 1055L))
        val sample = window.record(5f, 1075L)!!
        assertEquals(10f, sample.accumPx, 0.01f)
    }

    @Test
    fun gestureWindowIgnoresZeroDeltaAndResetClearsAccumulation() {
        val window = CrownGestureWindow(idleTimeoutMs = 25L, minWindowMs = 20L)
        assertNull(window.record(0f, 1000L))
        window.record(10f, 1000L)
        window.reset()
        // reset 后 lastEventMs 归零：下一事件必重开窗口，累计从 0 起算
        assertNull(window.record(10f, 1100L))
        val sample = window.record(10f, 1120L)!!
        assertEquals(20f, sample.accumPx, 0.01f)
    }

    @Test
    fun unsignedTickGateTriggersAtThresholdWithCarry() {
        val gate = CrownTickGate(thresholdPx)
        assertFalse(gate.onScroll(8f))
        assertFalse(gate.onScroll(8f))
        // 24px 满门限：触发一次，余量 0
        assertTrue(gate.onScroll(8f))
        assertFalse(gate.onScroll(23f))
        assertTrue(gate.onScroll(1f))
    }

    @Test
    fun unsignedTickGateKeepsOvershootAsCarry() {
        val gate = CrownTickGate(thresholdPx)
        // 30px 只扣一个门限，余 6px：再走 17px 不足 24 不触发，再走 1px 恰满即触发
        assertTrue(gate.onScroll(30f))
        assertFalse(gate.onScroll(17f))
        assertTrue(gate.onScroll(1f))
    }

    @Test
    fun unsignedTickGateIsSignIndependent() {
        val gate = CrownTickGate(thresholdPx)
        assertFalse(gate.onScroll(-8f))
        assertFalse(gate.onScroll(-8f))
        assertTrue(gate.onScroll(-8f))
    }

    @Test
    fun signedTickGateCancelsReverseRotation() {
        val gate = CrownTickGate(thresholdPx, signed = true)
        assertFalse(gate.onScroll(10f))
        // 反向 10px 抵消到零：余量不残留，正向重新从 0 起算
        assertFalse(gate.onScroll(-10f))
        assertFalse(gate.onScroll(23f))
        assertTrue(gate.onScroll(1f))
    }

    @Test
    fun signedTickGateTriggersAtExactThresholdAndCarries() {
        val gate = CrownTickGate(thresholdPx, signed = true)
        // 恰等门限也触发；30px 余 6px，再 18px 满 24 触发
        assertTrue(gate.onScroll(24f))
        assertTrue(gate.onScroll(30f))
        assertFalse(gate.onScroll(17f))
        assertTrue(gate.onScroll(1f))
    }

    @Test
    fun tickGateResetClearsCarry() {
        val gate = CrownTickGate(thresholdPx)
        assertTrue(gate.onScroll(30f))
        gate.reset()
        assertFalse(gate.onScroll(23f))
        assertTrue(gate.onScroll(1f))
    }

    @Test
    fun zeroDistanceNeverTicks() {
        val gate = CrownTickGate(thresholdPx)
        assertFalse(gate.onScroll(0f))
    }

    // ── P2-1：原生容器按真实消费量入账（ADR-019 决策 3）─────────────
    // 贴边时 scrollBy 会把请求量钳掉一部分，记账只认位移前后差值

    @Test
    fun consumedDeltaIsActualDisplacement() {
        // 完整消费：请求 79px，位移 79px
        assertEquals(79, CrownScrollArithmetic.consumedDelta(before = 1200, after = 1279))
        // 触底钳制：距底 5px 请求 79px，只认 5px，多记的距离不能推进振感与惯性
        assertEquals(5, CrownScrollArithmetic.consumedDelta(before = 2795, after = 2800))
        // 反向贴顶同理，符号随实际位移
        assertEquals(-3, CrownScrollArithmetic.consumedDelta(before = 3, after = 0))
        // 完全无余量：真实消费为 0，本事件不参与振感与惯性
        assertEquals(0, CrownScrollArithmetic.consumedDelta(before = 2800, after = 2800))
    }
}
