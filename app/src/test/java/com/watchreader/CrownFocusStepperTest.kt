package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 表冠焦点轮选步进测试：复刻系统设置主页对齐模式（ScaleAlign）
 *
 * 背景约束（逆向自 com.android.settings RecyclerView 对齐分支）：
 * 1. 单事件轴值先做 |δ|≥80 钳 ±79——快甩也不会单事件跨多项；
 * 2. 位移按 round(axis × 系统滚动系数) 累积（对齐分支不吃 ×0.5 阻尼）；
 * 3. 累计满 120px（设置项行高）翻上/下一项并给一次齿轮微振，零头保留；
 * 4. 换向先抵消剩余量再走新方向。
 */
class CrownFocusStepperTest {

    private fun stepper(factor: Float = 1f) = CrownFocusStepper(
        pxPerStep = 120f,
        scrollFactorPxPerUnit = factor
    )

    @Test
    fun testSlowSpinAccumulatesToOneStep() {
        val s = stepper()
        assertEquals(0, s.feed(50f))
        assertEquals(0, s.feed(50f))
        // 累计 100 + 30 = 130 → 1 步余 10
        assertEquals(1, s.feed(30f))
    }

    @Test
    fun testSingleEventClampedTo79() {
        val s = stepper()
        // 快甩单事件 500 单位被钳成 79px，未满 120 不翻项
        assertEquals(0, s.feed(500f))
        assertEquals(1, s.feed(50f)) // 79 + 50 = 129 → 1 步
    }

    @Test
    fun testFastSpinAdvancesAtMostOneStepPerEvent() {
        val s = stepper()
        // 连续大事件：每事件至多翻一项（钳制后 79 < 120，需跨事件凑满）
        assertEquals(0, s.feed(1200f))
        assertEquals(1, s.feed(1200f))
        assertEquals(0, s.feed(1200f))
        assertEquals(1, s.feed(1200f))
    }

    @Test
    fun testDirectionFollowsSign() {
        val s = stepper()
        // 钳制下单事件只挪 −79px：第一步要跨两个事件
        assertEquals(0, s.feed(-130f))
        assertEquals(-1, s.feed(-130f))
        // 反向：先抵消 −38 零头，再凑满 +120
        assertEquals(0, s.feed(130f))
        assertEquals(1, s.feed(130f))
    }

    @Test
    fun testRemainderCarriesAcrossEvents() {
        val s = stepper()
        assertEquals(0, s.feed(50f))
        assertEquals(0, s.feed(50f))
        // 50 + 50 = 100，+20 凑满 120
        assertEquals(1, s.feed(20f))
    }

    @Test
    fun testReverseCancellationConsumesRemainderFirst() {
        val s = stepper()
        assertEquals(0, s.feed(110f)) // 余 +110
        // 反向 130 单位被钳成 −79：抵消 +110 后余 +31，未满档 → 0 步
        assertEquals(0, s.feed(-130f))
        assertEquals(0, s.feed(-100f)) // 31 − 79 = −48
        assertEquals(-1, s.feed(-72f)) // −48 − 72 = −120 → −1 步
    }

    @Test
    fun testScrollFactorScalesAccumulation() {
        val s = stepper(factor = 2f)
        // 30 单位 × 2 = 60px
        assertEquals(0, s.feed(30f))
        assertEquals(1, s.feed(30f))
    }

    @Test
    fun testResetClearsAccumulator() {
        val s = stepper()
        s.feed(110f)
        s.reset()
        assertEquals(0, s.feed(110f))
    }

    @Test
    fun testNegativeInputClampsSymmetrically() {
        val s = stepper()
        assertEquals(0, s.feed(-500f)) // 钳成 −79px
        assertEquals(-1, s.feed(-50f)) // −79 − 50 = −129 → −1 步
    }
}