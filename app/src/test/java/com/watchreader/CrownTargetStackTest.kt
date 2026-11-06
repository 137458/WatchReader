package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠目标持有栈：把「谁在前台」从页面自觉改成注册顺序事实
 *
 * 覆盖 P1-2 的根因——单槽覆盖后不恢复：浮层注销必须回退到宿主页面，而不是把槽位清空。
 */
class CrownTargetStackTest {

    private class Target(val name: String)

    @Test
    fun emptyStackHasNoActiveTarget() {
        val stack = CrownTargetStack<Target>()
        assertNull(stack.active)
        assertEquals(0, stack.size)
    }

    @Test
    fun lastRegisteredTargetWins() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        val overlay = Target("overlay")
        stack.push(page)
        stack.push(overlay)
        assertEquals(overlay, stack.active)
        assertEquals(2, stack.size)
    }

    @Test
    fun removingOverlayRestoresHostPage() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        val overlay = Target("overlay")
        stack.push(page)
        stack.push(overlay)

        assertTrue(stack.remove(overlay))
        assertEquals(page, stack.active)
    }

    @Test
    fun removingMiddleTargetKeepsTopmost() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        val middle = Target("middle")
        val overlay = Target("overlay")
        stack.push(page)
        stack.push(middle)
        stack.push(overlay)

        assertTrue(stack.remove(middle))
        assertEquals(overlay, stack.active)
    }

    @Test
    fun removingUnknownTargetLeavesStackUntouched() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        stack.push(page)
        assertFalse(stack.remove(Target("page")))
        assertEquals(page, stack.active)
        assertEquals(1, stack.size)
    }

    @Test
    fun reRegisteringSameTargetMovesItToTop() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        val overlay = Target("overlay")
        stack.push(page)
        stack.push(overlay)
        stack.push(page)

        assertEquals(2, stack.size)
        assertEquals(page, stack.active)
        // 注销后不能残留重复条目反向"复活"
        assertTrue(stack.remove(page))
        assertEquals(overlay, stack.active)
    }

    @Test
    fun removeByTokenOnlyDropsThatRegistration() {
        val stack = CrownTargetStack<Target>()
        val page = Target("page")
        val overlay = Target("overlay")
        val pageToken = stack.push(page)
        stack.push(overlay)

        assertTrue(stack.removeToken(pageToken))
        assertEquals(overlay, stack.active)
        assertFalse(stack.removeToken(pageToken))
    }

    @Test
    fun clearDropsEveryRegistration() {
        val stack = CrownTargetStack<Target>()
        stack.push(Target("page"))
        stack.push(Target("overlay"))
        stack.clear()
        assertNull(stack.active)
        assertEquals(0, stack.size)
    }
}
