package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠手感调校测试：单位线性换算 + 24px 距离门限齿轮微振节流
 *
 * 背景约束一（换算）：oplus_crown 上报高分辨率相对滚轮单位流（慢转 ~56 事件/s、值 1~5，
 * 快甩单事件 ±50+），约 12 单位 = 一个物理档位；任何按事件绝对值分档/透传的启发式
 * 都与本机编码不匹配（历史上曾导致调步进常量无效、慢转爬行快甩飞滚），必须全量线性。
 * 背景约束二（振感）：原先每个表冠事件无条件触发一次微振，快速转动时事件密集导致振感粘连、
 * 慢速微小转动时又逐事件乱振；OPPO 原生 crown_vibrator_instance 以固定位移门限计振，
 * 因此统一改为按滚动距离累积触发，保证不同转速下振感节奏一致。
 */
class CrownScrollTuningTest {

    @Test
    fun testNormalizeScalesUnitsLinearlyToPixels() {
        // 线性标定：12 单位 = 一档 = 64px（64/12 ≈ 5.33px/单位）
        assertEquals(64f, CrownScrollHelper.normalizeToPixels(12f), 0.01f)
        assertEquals(32f, CrownScrollHelper.normalizeToPixels(6f), 0.01f)
        assertEquals(-69.33f, CrownScrollHelper.normalizeToPixels(-13f), 0.01f)
        assertEquals(0f, CrownScrollHelper.normalizeToPixels(0f), 0.01f)
        // 快甩大值同样线性，不再透传跳变
        assertEquals(293.33f, CrownScrollHelper.normalizeToPixels(55f), 0.01f)
    }

    @Test
    fun testGateEmitsSingleTickPerThresholdDistance() {
        val gate = CrownTickGate()
        // 单格 40px 步进：每格恰好一次微振
        assertTrue(gate.onScroll(40f))
        assertTrue(gate.onScroll(40f))
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
}
