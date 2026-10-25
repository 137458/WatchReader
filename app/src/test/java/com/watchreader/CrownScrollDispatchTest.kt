package com.watchreader

import androidx.compose.foundation.ScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠 → 滚动分发回归测试（对齐系统设置 / 桌面列表内核的连续滚动口径）
 *
 * 本测试锁定四条铁律：
 * 1. 表冠顺时针（+）必须使 ScrollState.value 增大（向下翻页），与阅读页一致；
 * 2. 每个事件按「系统滚动系数 × 0.5」连续下发，允许亚像素输入补足 1px，不做档位量子跳跃；
 * 3. 齿轮微振按实际滚动像素累积，满 crown_vibrator_instance（默认 24px）才触发一次；
 * 4. 滚动余量耗尽时静默钳制：不越界、不响齿轮微振、也不响任何边界振感（产品决策：到底不反馈）。
 */
class CrownScrollDispatchTest {

    private fun prepare(factor: Float = 2f, time: Long = 1000L) {
        CrownScrollHelper.resetFeedbackGatesForTest()
        CrownScrollHelper.scrollFactorPxPerUnit = factor
        CrownScrollHelper.timeSourceMs = { time }
    }

    /** 模拟已布局的滚动容器：maxValue 的 setter 是 foundation 模块内符号（JVM 层公有） */
    private fun newScrollState(maxValue: Int = 4000): ScrollState {
        val state = ScrollState(0)
        ScrollState::class.java.declaredMethods
            .first { it.name.startsWith("setMaxValue") }
            .invoke(state, maxValue)
        return state
    }

    @Test
    fun crownClockwiseScrollsDown() {
        prepare()
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(6f, state, null, null) // 6 单位 × 2 × 0.5 = 6px
        assertEquals(6, state.value)
    }

    @Test
    fun crownCounterClockwiseScrollsUp() {
        prepare()
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(12f, state, null, null) // 12px
        assertEquals(12, state.value)
        CrownScrollHelper.dispatchScroll(-6f, state, null, null) // -6px
        assertEquals(6, state.value)
    }

    @Test
    fun subPixelRotationStillMovesOnePixel() {
        prepare()
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(0.1f, state, null, null)
        assertEquals("亚像素转动必须补足 1px，不得被吞掉", 1, state.value)
        CrownScrollHelper.dispatchScroll(-0.1f, state, null, null)
        assertEquals(0, state.value)
    }

    @Test
    fun continuousRotationDoesNotQuantumJump() {
        prepare()
        val state = newScrollState()
        // 旧档位模型下 1 单位两次都不会移动；新模型每个事件都应跟手
        CrownScrollHelper.dispatchScroll(1f, state, null, null)
        assertEquals(1, state.value)
        CrownScrollHelper.dispatchScroll(1f, state, null, null)
        assertEquals(2, state.value)
    }

    @Test
    fun vibrationAccumulatesByScrollDistanceNotNotch() {
        prepare()
        val state = newScrollState()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            // 系统列表 mDelta 语义：门限判断在累加之前，满 24px 后由下一次事件触发并清零
            repeat(4) { CrownScrollHelper.dispatchScroll(6f, state, null, null) }
            assertEquals(24, state.value)
            assertEquals(0, ticks)

            CrownScrollHelper.dispatchScroll(6f, state, null, null)
            assertEquals(30, state.value)
            assertEquals(1, ticks)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
    }

    @Test
    fun signedGateCancelsOppositeRotation() {
        // 系统列表用有符号累积：反向转动会抵消余量，不会各转一半就各响一次
        prepare()
        val state = newScrollState()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(3) { CrownScrollHelper.dispatchScroll(6f, state, null, null) } // +18
            repeat(3) { CrownScrollHelper.dispatchScroll(-6f, state, null, null) } // 回到 0
            assertEquals(0, ticks)
            assertEquals(0, state.value)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
    }

    @Test
    fun idleTickFiresPerDistanceOnNonScrollablePage() {
        prepare()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(5) { CrownScrollHelper.dispatchIdleTick(6f, null, null) } // 满 24px 后下一次事件触发
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("空转页按系统滚动距离门限振一次", 1, ticks)
    }

    @Test
    fun bottomEdgeClampsSilently() {
        prepare()
        val state = newScrollState(maxValue = 10)
        CrownScrollHelper.dispatchScroll(100f, state, null, null) // 到达底部
        assertEquals(10, state.value)

        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            CrownScrollHelper.dispatchScroll(100f, state, null, null) // 底部继续向下
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("触边后不应再走齿轮微振", 0, ticks)
        assertEquals("触边后不应越界滚动", 10, state.value)
    }

    @Test
    fun adjustTickFiresPerDetent() {
        // 调速档位微振：每物理档位（12 单位）恰好一振，杜绝调速时逐事件连震
        prepare()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(6) { CrownScrollHelper.dispatchAdjustTick(2f, null, null) } // 12 单位 → 1 振
            assertEquals(1, ticks)
            CrownScrollHelper.dispatchAdjustTick(-11f, null, null) // 不足一档，余量跨事件保留
            assertEquals(1, ticks)
            CrownScrollHelper.dispatchAdjustTick(2f, null, null) // 11 + 2 → 1 振
            assertEquals(2, ticks)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
    }

    @Test
    fun adjustTickCoalescesFastFlickToSingleTick() {
        prepare()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            CrownScrollHelper.dispatchAdjustTick(50f, null, null)
            assertEquals(1, ticks)
            CrownScrollHelper.dispatchAdjustTick(10f, null, null) // 余量 2 + 10 → 1 振
            assertEquals(2, ticks)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
    }

    @Test
    fun flingVelocityEstimateMatchesLauncherFormula() {
        // 惯性速度估计：速度 = 窗口累计位移 × 700 / 窗口毫秒数，钳制 ±2000
        assertEquals(1050f, CrownScrollHelper.computeFlingVelocity(300f, 200L), 0.01f)
        assertEquals(-700f, CrownScrollHelper.computeFlingVelocity(-100f, 100L), 0.01f)
        // 慢速滚动不应达到起振门限（500）
        assertTrue(CrownScrollHelper.computeFlingVelocity(640f, 2000L) < CrownScrollHelper.FLING_MIN_VELOCITY_PX_S)
        // 快甩钳制在 ±2000
        assertEquals(2000f, CrownScrollHelper.computeFlingVelocity(1000f, 100L), 0.01f)
        assertEquals(-2000f, CrownScrollHelper.computeFlingVelocity(-1000f, 10L), 0.01f)
        // 窗口无效或零位移不给速度
        assertEquals(0f, CrownScrollHelper.computeFlingVelocity(100f, 0L), 0.01f)
        assertEquals(0f, CrownScrollHelper.computeFlingVelocity(0f, 100L), 0.01f)
    }

    @Test
    fun slowRotationNeverTriggersFling() {
        var now = 1000L
        prepare(time = now)
        val state = newScrollState()
        var flingFired = false
        CrownScrollHelper.flingStartProbe = { _ -> flingFired = true }
        try {
            // 20ms 间隔慢转 3 次：累计 6px / 40ms，速度约 105px/s，低于 500 门限
            CrownScrollHelper.dispatchScroll(2f, state, null, null)
            now += 20L
            CrownScrollHelper.timeSourceMs = { now }
            CrownScrollHelper.dispatchScroll(2f, state, null, null)
            now += 20L
            CrownScrollHelper.timeSourceMs = { now }
            CrownScrollHelper.dispatchScroll(2f, state, null, null)
            assertFalse("慢转绝对不应触发惯性滑动", flingFired)
        } finally {
            CrownScrollHelper.flingStartProbe = null
        }
    }

    @Test
    fun fastFlickTriggersFlingWhenVelocitySufficient() {
        var now = 1000L
        prepare(time = now)
        val state = newScrollState()
        var flingVelocity = 0f
        CrownScrollHelper.flingStartProbe = { v -> flingVelocity = v }
        try {
            // 20ms 内两次 20px：累计 40px / 20ms = 1400px/s，达标立即起振
            CrownScrollHelper.dispatchScroll(20f, state, null, null)
            now += 20L
            CrownScrollHelper.timeSourceMs = { now }
            CrownScrollHelper.dispatchScroll(20f, state, null, null)
            assertTrue("快甩且速度达标时必须触发惯性滑动", flingVelocity >= CrownScrollHelper.FLING_MIN_VELOCITY_PX_S)
        } finally {
            CrownScrollHelper.flingStartProbe = null
        }
    }

    @Test
    fun continuedSpinAtBottomEdgeStaysSilentlyClamped() {
        // 安全边界：触底后持续同向空转，完全静默钳制（无齿轮微振、无越界、无任何边界振感）
        var now = 1000L
        prepare(time = now)
        val state = newScrollState(maxValue = 10)
        CrownScrollHelper.dispatchScroll(100f, state, null, null) // 到达底部
        assertEquals(10, state.value)

        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(6) {
                now += 50
                CrownScrollHelper.timeSourceMs = { now }
                CrownScrollHelper.dispatchScroll(12f, state, null, null)
            }
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("空转不得触发齿轮微振", 0, ticks)
        assertEquals("触底后不应越界滚动", 10, state.value)
    }

    @Test
    fun registryActivateDeactivateLifecycle() {
        val t1 = CrownScrollTarget { true }
        try {
            CrownScrollTargetRegistry.activate(t1)
            assertSame(t1, CrownScrollTargetRegistry.active)

            val t2 = CrownScrollTarget { false }
            CrownScrollTargetRegistry.activate(t2) // 页面切换：新目标顶替
            CrownScrollTargetRegistry.deactivate(t1) // 旧页面延迟注销不得误清新目标
            assertSame(t2, CrownScrollTargetRegistry.active)

            CrownScrollTargetRegistry.deactivate(t2)
            assertNull(CrownScrollTargetRegistry.active)
        } finally {
            CrownScrollTargetRegistry.active?.let { CrownScrollTargetRegistry.deactivate(it) }
        }
    }

    @Test
    fun registryTargetReceivesNormalizedDelta() {
        prepare()
        var received = 0f
        val state = newScrollState()
        val target = CrownScrollTarget { delta ->
            received = delta
            CrownScrollHelper.dispatchScroll(delta, state, null, null)
            true
        }
        CrownScrollTargetRegistry.activate(target)
        try {
            CrownScrollTargetRegistry.active?.onCrownDelta(6f)
            assertEquals(6f, received, 0.001f)
            assertEquals(6, state.value)
        } finally {
            CrownScrollTargetRegistry.deactivate(target)
        }
    }
}
