package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 步进键连发策略：一次点按只能产生一次步进。
 *
 * 缺陷背景（MenuScreen.kt StepperKey）：旧实现同时存在两条激活路径——
 * 协程在按下瞬间直接派发一步，clickable 又在抬起时派发一步，一次点按步进两次。
 * 修复后的口径：离散步进只由 clickable 在抬起时派发一次；
 * [StepperRepeat] 只描述「按住超过阈值之后」的补充连发，不参与首步。
 */
class MenuStepperRepeatTest {

    @Test
    fun testTapSequenceYieldsExactlyOneStep() {
        // 一次 60ms 点按：clickable 抬起派发 1 步 + 策略补充 0 步 = 1 步
        val steps = 1 + StepperRepeat.repeatCount(60L)
        assertEquals(1, steps)
    }

    @Test
    fun testShortHoldNeverRepeats() {
        assertEquals(0, StepperRepeat.repeatCount(0L))
        assertEquals(0, StepperRepeat.repeatCount(StepperRepeat.HOLD_DELAY_MS - 1))
    }

    @Test
    fun testHoldStartsRepeatingAtThreshold() {
        // 恰好按住阈值即补发第一步，随后每个间隔一步
        assertEquals(1, StepperRepeat.repeatCount(StepperRepeat.HOLD_DELAY_MS))
        assertEquals(2, StepperRepeat.repeatCount(StepperRepeat.HOLD_DELAY_MS + StepperRepeat.INTERVAL_MS))
        assertEquals(3, StepperRepeat.repeatCount(StepperRepeat.HOLD_DELAY_MS + 2 * StepperRepeat.INTERVAL_MS))
    }

    @Test
    fun testCadenceMatchesWristSpec() {
        // 与旧实现的节奏一致（按住 400ms 起每 90ms 一步），仅去掉按下抢先派发那一次
        assertEquals(400L, StepperRepeat.HOLD_DELAY_MS)
        assertEquals(90L, StepperRepeat.INTERVAL_MS)
    }
}
