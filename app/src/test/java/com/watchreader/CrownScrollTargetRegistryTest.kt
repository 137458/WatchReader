package com.watchreader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 注册表行为测试：宿主让位、浮层注销后恢复、阻塞浮层挂起表冠
 *
 * 覆盖 P1-2（浮层关闭后表冠失效）与 P1-3（阻塞浮层期间表冠滚动底层页面）两条故障。
 */
class CrownScrollTargetRegistryTest {

    private class RecordingTarget(val name: String) : CrownScrollTarget {
        val deltas = mutableListOf<Float>()
        override fun onCrownDelta(delta: Float): Boolean {
            deltas += delta
            return true
        }
    }

    @Before
    fun setUp() {
        CrownScrollTargetRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        CrownScrollTargetRegistry.resetForTest()
    }

    @Test
    fun noRegistrationMeansNoActiveTarget() {
        assertNull(CrownScrollTargetRegistry.active)
    }

    @Test
    fun overlayRegistrationOverridesHostAndHostRecoversOnDispose() {
        val page = RecordingTarget("menu-scroll")
        val picker = RecordingTarget("theme-picker")
        CrownScrollTargetRegistry.activate(page)
        CrownScrollTargetRegistry.activate(picker)
        assertEquals(picker, CrownScrollTargetRegistry.active)

        // 浮层关闭：注销浮层后宿主必须自动恢复，而不是留下空槽位
        CrownScrollTargetRegistry.deactivate(picker)
        assertEquals(page, CrownScrollTargetRegistry.active)

        CrownScrollTargetRegistry.active!!.onCrownDelta(3f)
        assertEquals(listOf(3f), page.deltas)
        assertTrue(picker.deltas.isEmpty())
    }

    @Test
    fun deactivatingNonActiveTargetKeepsCurrentOwner() {
        val page = RecordingTarget("page")
        val overlay = RecordingTarget("overlay")
        CrownScrollTargetRegistry.activate(page)
        CrownScrollTargetRegistry.activate(overlay)

        CrownScrollTargetRegistry.deactivate(page)
        assertEquals(overlay, CrownScrollTargetRegistry.active)
    }

    @Test
    fun suspendSwallowsInputWhileNested() {
        val page = RecordingTarget("page")
        CrownScrollTargetRegistry.activate(page)
        CrownScrollTargetRegistry.suspendInput()
        CrownScrollTargetRegistry.suspendInput()

        // 挂起期顶层是"吞掉事件"的目标：既不滚底层页面，也不漏给原生 View 树
        val active = CrownScrollTargetRegistry.active
        assertNotNull(active)
        assertTrue(active!!.onCrownDelta(5f))
        assertTrue(page.deltas.isEmpty())

        CrownScrollTargetRegistry.resumeInput()
        assertTrue(CrownScrollTargetRegistry.active!!.onCrownDelta(2f))
        assertTrue(page.deltas.isEmpty())

        CrownScrollTargetRegistry.resumeInput()
        assertEquals(page, CrownScrollTargetRegistry.active)
        CrownScrollTargetRegistry.active!!.onCrownDelta(1f)
        assertEquals(listOf(1f), page.deltas)
    }

    @Test
    fun suspendWithoutAnyRegistrationStillSwallowsInput() {
        CrownScrollTargetRegistry.suspendInput()
        assertTrue(CrownScrollTargetRegistry.active!!.onCrownDelta(1f))

        CrownScrollTargetRegistry.resumeInput()
        assertNull(CrownScrollTargetRegistry.active)
    }

    @Test
    fun resumeWithoutSuspendKeepsOwner() {
        val page = RecordingTarget("page")
        CrownScrollTargetRegistry.activate(page)
        CrownScrollTargetRegistry.resumeInput()
        assertEquals(page, CrownScrollTargetRegistry.active)
    }

    @Test
    fun registrationDuringSuspendDoesNotStealInputUntilResume() {
        val page = RecordingTarget("page")
        val overlay = RecordingTarget("overlay")
        CrownScrollTargetRegistry.activate(page)
        CrownScrollTargetRegistry.suspendInput()
        CrownScrollTargetRegistry.activate(overlay)

        // 挂起期注册的浮层只能排队，表冠仍被吞掉
        assertTrue(CrownScrollTargetRegistry.active!!.onCrownDelta(4f))
        assertTrue(overlay.deltas.isEmpty())

        CrownScrollTargetRegistry.resumeInput()
        assertEquals(overlay, CrownScrollTargetRegistry.active)
    }
}
