package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠手感调校测试：系统列表同款连续滚动换算 + 距离门限微振
 *
 * 背景约束一（换算）：oplus_crown 上报高分辨率相对滚轮单位流（慢转 ~56 事件/s、值 1~5，
 * 快甩单事件 ±50+）。本机系统设置 / 桌面的列表内核按「系统滚动系数 × 0.5」逐事件连续下发，
 * 不足 1px 时按方向补 1px；不再按 12 单位一档做量子化跳跃。
 * 背景约束二（振感）：OPPO 原生 crown_vibrator_instance 以实际滚动像素累积计振（默认 24px），
 * 因此统一按滚动距离触发，保证不同转速下振感节奏一致。
 */
class CrownScrollTuningTest {

    private fun resetWithFactor(factor: Float = 2f) {
        CrownScrollHelper.resetFeedbackGatesForTest()
        CrownScrollHelper.scrollFactorPxPerUnit = factor
    }

    @Test
    fun testScrollPixelsMatchSystemListFormula() {
        resetWithFactor(2f)
        // round(units × 2) 后再 × 0.5：6 单位 → 6px，3 单位 → 3px
        assertEquals(6, CrownScrollHelper.scrollPixelsFor(6f))
        assertEquals(3, CrownScrollHelper.scrollPixelsFor(3f))
        assertEquals(-13, CrownScrollHelper.scrollPixelsFor(-13f))
        assertEquals(55, CrownScrollHelper.scrollPixelsFor(55f))
        assertEquals(0, CrownScrollHelper.scrollPixelsFor(0f))
    }

    @Test
    fun testSubPixelRotationGetsOnePixel() {
        resetWithFactor(2f)
        // 系统列表内核：Math.round 后不足 1px 时按方向补足 1px，微小转动也跟手
        assertEquals(1, CrownScrollHelper.scrollPixelsFor(0.1f))
        assertEquals(-1, CrownScrollHelper.scrollPixelsFor(-0.1f))
    }

    @Test
    fun testScrollFactorIsHalfDamped() {
        resetWithFactor(4f)
        // 6 单位 × 4 = 24 → × 0.5 = 12px
        assertEquals(12, CrownScrollHelper.scrollPixelsFor(6f))
        resetWithFactor(1f)
        // 6 单位 × 1 = 6 → × 0.5 = 3px
        assertEquals(3, CrownScrollHelper.scrollPixelsFor(6f))
    }

    @Test
    fun testGateEmitsSingleTickPerThresholdDistance() {
        val gate = CrownTickGate()
        // 每满 24px 门限触发一次微振，余量跨调用保留
        assertTrue(gate.onScroll(24f))
        assertTrue(gate.onScroll(24f))
    }

    @Test
    fun testGateCoalescesSmallDeltas() {
        val gate = CrownTickGate()
        // 每次 8px：24px 门限下第 3 次才触发（振感不随事件频率漂移）
        assertFalse(gate.onScroll(8f))
        assertFalse(gate.onScroll(8f))
        assertTrue(gate.onScroll(8f))
        assertFalse(gate.onScroll(8f))
        assertFalse(gate.onScroll(8f))
        assertTrue(gate.onScroll(8f))
    }

    @Test
    fun testGateIsSignAgnostic() {
        val gate = CrownTickGate()
        assertFalse(gate.onScroll(-8f))
        assertFalse(gate.onScroll(8f))
        assertTrue(gate.onScroll(-8f))
    }

    @Test
    fun testGateCarriesRemainderAcrossCalls() {
        val gate = CrownTickGate()
        // 30px 步进：首次触发并保留 6px 余量，第二次 6+30=36 再次触发
        assertTrue(gate.onScroll(30f))
        assertTrue(gate.onScroll(30f))
        // 余量不丢失：连续 5px 三次后应由余量补齐触发
        val gate2 = CrownTickGate()
        assertFalse(gate2.onScroll(5f))
        assertFalse(gate2.onScroll(5f))
        assertFalse(gate2.onScroll(5f))
        assertFalse(gate2.onScroll(5f))
        assertTrue(gate2.onScroll(5f))
    }

    @Test
    fun testGateResetClearsRemainder() {
        val gate = CrownTickGate()
        assertFalse(gate.onScroll(20f))
        gate.reset()
        assertFalse(gate.onScroll(20f))
        assertTrue(gate.onScroll(20f))
    }

    @Test
    fun testCustomThresholdIsHonored() {
        val gate = CrownTickGate(thresholdPx = 10f)
        assertTrue(gate.onScroll(10f))
        val gate2 = CrownTickGate(thresholdPx = 50f)
        assertFalse(gate2.onScroll(40f))
        assertTrue(gate2.onScroll(10f))
    }

    @Test
    fun testSignedGateMatchesSystemMDelta() {
        // 系统列表：门限判断先于累加，满 24px 后下一次事件触发并整体清零
        val gate = CrownTickGate(thresholdPx = 24f, signed = true)
        repeat(4) { assertFalse(gate.onScroll(6f)) } // 累计 24px
        assertTrue(gate.onScroll(6f)) // 第 5 次触发
        assertFalse(gate.onScroll(6f)) // 清零后重新累计
    }

    @Test
    fun testSignedGateCancelsOppositeRotation() {
        val gate = CrownTickGate(thresholdPx = 24f, signed = true)
        repeat(3) { assertFalse(gate.onScroll(6f)) } // +18
        repeat(3) { assertFalse(gate.onScroll(-6f)) } // 抵消回 0
        repeat(3) { assertFalse(gate.onScroll(6f)) } // 再 +18，仍未满
        assertFalse(gate.onScroll(6f)) // 累计 24
        assertTrue(gate.onScroll(6f)) // 下一次触发
    }
}
