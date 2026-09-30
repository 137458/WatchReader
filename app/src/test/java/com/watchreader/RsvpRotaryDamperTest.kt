package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RSVP 表冠调速阻尼器测试：强阻尼门限分档 + 时间窗节流 + 空闲复位
 *
 * 时钟由调用方注入（onDelta 的 nowMs 参数），用例不依赖真实时钟。
 * 步进约定：每格 ±10 字/分，方向与累加器符号一致。
 */
class RsvpRotaryDamperTest {

    @Test
    fun testFineRotationAccumulatesToThresholdBeforeStepping() {
        val damper = RsvpRotaryDamper()
        // 单次小幅刻度（1.5 < 门限 2.5）不步进
        assertEquals(0f, damper.onDelta(1.5f, nowMs = 0), 0.001f)
        // 累计到 3.0 ≥ 2.5 且距上次步进 ≥ 75ms，触发一步 +10
        assertEquals(10f, damper.onDelta(1.5f, nowMs = 100), 0.001f)
    }

    @Test
    fun testCoarseFlickStepsImmediately() {
        val damper = RsvpRotaryDamper()
        // 快甩单事件 80 ≥ 75 粗档门限，单次即计一步
        assertEquals(10f, damper.onDelta(80f, nowMs = 1000), 0.001f)
    }

    @Test
    fun testThrottleSuppressesSecondStepWithin75ms() {
        val damper = RsvpRotaryDamper()
        assertEquals(10f, damper.onDelta(80f, nowMs = 1000), 0.001f)
        // 50ms 后再次到达门限：被时间窗节流吞掉
        assertEquals(0f, damper.onDelta(80f, nowMs = 1050), 0.001f)
        // 节流窗口过后，残留累加器允许下一步
        assertEquals(10f, damper.onDelta(0f, nowMs = 1080), 0.001f)
    }

    @Test
    fun testIdleGapResetsAccumulator() {
        val damper = RsvpRotaryDamper()
        assertEquals(0f, damper.onDelta(2.0f, nowMs = 0), 0.001f)
        // 500ms > 400ms 空闲窗：累加器清零，仅本次 0.5 生效，未达门限不步进
        // （若无复位，累计 2.5 将恰好达到门限）
        assertEquals(0f, damper.onDelta(0.5f, nowMs = 500), 0.001f)
    }

    @Test
    fun testNegativeDirectionStepsNegative() {
        val damper = RsvpRotaryDamper()
        assertEquals(0f, damper.onDelta(-1.5f, nowMs = 0), 0.001f)
        assertEquals(-10f, damper.onDelta(-1.5f, nowMs = 100), 0.001f)
    }

    @Test
    fun testExactlyAtFineThresholdSteps() {
        val damper = RsvpRotaryDamper()
        assertEquals(0f, damper.onDelta(1.0f, nowMs = 0), 0.001f)
        // 恰好等于门限 2.5 视为达到（>= 语义）
        assertEquals(10f, damper.onDelta(1.5f, nowMs = 100), 0.001f)
    }
}
