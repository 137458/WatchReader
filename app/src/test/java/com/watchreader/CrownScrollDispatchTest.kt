package com.watchreader

import androidx.compose.foundation.ScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠 → Compose 滚动分发回归测试（对齐阅读页原生 smoothScrollBy(+px) 的方向语义）
 *
 * 背景：菜单 / 书架曾通过 dispatchRawDelta(-pixels) 分发表冠增量，而 ScrollState 的
 * 滚动语义是 value += delta（正 = 向下翻页），与阅读页 smoothScrollBy(+pixels) 恰好相反。
 * 结果是 Compose 页面页顶顺时针被钳制为完全无响应、逆时针反向滚动，手感灵敏度错乱，
 * 用户感知为"表冠处理有问题、振动与灵敏度不对"。
 *
 * 本测试锁定三条铁律（单位标定：12 单位 = 一个物理档位 = 系统滚动系数，见 scrollFactorPxPerUnit）：
 * 1. 表冠顺时针（+）必须使 ScrollState.value 增大（向下翻页），与阅读页一致；
 * 2. 每档位移必须触发一次齿轮微振；
 * 3. 滚动余量耗尽时静默钳制：不越界、不响齿轮微振、也不响任何边界振感（产品决策：到底不反馈）。
 */
class CrownScrollDispatchTest {

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
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(12f, state, null, null) // 顺时针一档（12 单位）
        assertEquals(64, state.value)
    }

    @Test
    fun crownCounterClockwiseScrollsUp() {
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(24f, state, null, null) // 向下两档
        assertEquals(128, state.value)
        CrownScrollHelper.dispatchScroll(-12f, state, null, null) // 逆时针一档回退
        assertEquals(64, state.value)
    }

    @Test
    fun highResolutionStreamScalesLinearly() {
        // 实机事件流为高分辨率单位：快甩单事件可达 ±50+，必须全量线性换算而非跳档
        val state = newScrollState()
        CrownScrollHelper.dispatchScroll(55f, state, null, null)
        assertEquals((55f * 64f / 12f).toInt(), state.value)
    }

    @Test
    fun gearTickFiresPerNotch() {
        CrownScrollHelper.resetFeedbackGatesForTest()
        val state = newScrollState()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            CrownScrollHelper.dispatchScroll(12f, state, null, null)
            CrownScrollHelper.dispatchScroll(12f, state, null, null)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals(2, ticks)
        assertEquals(128, state.value)
    }

    @Test
    fun bottomEdgeClampsSilently() {
        CrownScrollHelper.resetFeedbackGatesForTest()
        val state = newScrollState(maxValue = 128)
        CrownScrollHelper.dispatchScroll(24f, state, null, null) // 到达底部
        assertEquals(128, state.value)

        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            CrownScrollHelper.dispatchScroll(12f, state, null, null) // 底部继续向下
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("触边后不应再走齿轮微振", 0, ticks)
        assertEquals("触边后不应越界滚动", 128, state.value)
    }

    @Test
    fun idleTickFiresPerNotchOnNonScrollablePage() {
        CrownScrollHelper.resetFeedbackGatesForTest()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            CrownScrollHelper.dispatchIdleTick(12f, null, null)
            CrownScrollHelper.dispatchIdleTick(12f, null, null)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("空转每档应给出齿轮微振", 2, ticks)
    }

    @Test
    fun adjustTickFiresPerDetent() {
        // 调速档位微振：每物理档位（12 单位）恰好一振，杜绝调速时逐事件连震
        CrownScrollHelper.resetFeedbackGatesForTest()
        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(6) { CrownScrollHelper.dispatchAdjustTick(2f, null, null) } // 12 单位 → 1 振
            assertEquals(1, ticks)
            CrownScrollHelper.dispatchAdjustTick(-11f, null, null) // 11 单位不足一档，余量跨事件保留
            assertEquals(1, ticks)
            CrownScrollHelper.dispatchAdjustTick(2f, null, null) // 11 + 2 → 1 振
            assertEquals(2, ticks)
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
    }

    @Test
    fun adjustTickCoalescesFastFlickToSingleTick() {
        // 快甩单事件 ±50+ 单位：门限内合并为一次微振（与滚动页防粘连契约一致），
        // 余量 50 % 12 = 2 单位跨事件保留，后续小额转动补齐门限后再次响振
        CrownScrollHelper.resetFeedbackGatesForTest()
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
        // 惯性速度估计（逆向 HeyLauncher）：速度 = 窗口累计位移 × 700 / 窗口毫秒数，钳制 ±2000
        assertEquals(1050f, CrownScrollHelper.computeFlingVelocity(300f, 200L), 0.01f)
        assertEquals(-700f, CrownScrollHelper.computeFlingVelocity(-100f, 100L), 0.01f)
        // 慢速滚动不应达到起振门限（500）：60Hz × 5.33px 稳态 ≈ 223
        assertTrue(CrownScrollHelper.computeFlingVelocity(640f, 2000L) < CrownScrollHelper.FLING_MIN_VELOCITY_PX_S)
        // 快甩钳制在 ±2000
        assertEquals(2000f, CrownScrollHelper.computeFlingVelocity(1000f, 100L), 0.01f)
        assertEquals(-2000f, CrownScrollHelper.computeFlingVelocity(-1000f, 10L), 0.01f)
        // 窗口无效或零位移不给速度
        assertEquals(0f, CrownScrollHelper.computeFlingVelocity(100f, 0L), 0.01f)
        assertEquals(0f, CrownScrollHelper.computeFlingVelocity(0f, 100L), 0.01f)
    }

    @Test
    fun continuedSpinAtBottomEdgeStaysSilentlyClamped() {
        // 安全边界：触底后持续同向空转，完全静默钳制（无齿轮微振、无越界、无任何边界振感）
        CrownScrollHelper.resetFeedbackGatesForTest()
        var now = 1000L
        CrownScrollHelper.timeSourceMs = { now }
        val state = newScrollState(maxValue = 128)
        CrownScrollHelper.dispatchScroll(24f, state, null, null) // 到达底部
        assertEquals(128, state.value)

        var ticks = 0
        CrownScrollHelper.gearTickProbe = { _, _ -> ticks++ }
        try {
            repeat(6) {
                now += 50 // 持续转动
                CrownScrollHelper.dispatchScroll(12f, state, null, null)
            }
        } finally {
            CrownScrollHelper.gearTickProbe = null
        }
        assertEquals("空转不得触发齿轮微振", 0, ticks)
        assertEquals("触底后不应越界滚动", 128, state.value)
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
        var received = 0f
        val state = newScrollState()
        val target = CrownScrollTarget { delta ->
            received = delta
            CrownScrollHelper.dispatchScroll(delta, state, null, null)
            true
        }
        CrownScrollTargetRegistry.activate(target)
        try {
            CrownScrollTargetRegistry.active?.onCrownDelta(12f)
            assertEquals(12f, received, 0.001f)
            assertEquals(64, state.value)
        } finally {
            CrownScrollTargetRegistry.deactivate(target)
        }
    }
}
