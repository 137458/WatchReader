package com.watchreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳章代际守卫：后发先至的正文装载不得把屏幕写回旧章节
 *
 * 覆盖 P2-2：连续两次跳章时，只有最后请求的那一代允许落地。
 */
class NavigationGuardTest {

    @Test
    fun onlyLatestNavigationIsCurrent() {
        val guard = NavigationGuard()
        val first = guard.begin()
        val second = guard.begin()

        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun firstNavigationStaysCurrentUntilNextRequest() {
        val guard = NavigationGuard()
        val token = guard.begin()
        assertTrue(guard.isCurrent(token))
    }

    @Test
    fun invalidateDropsInFlightNavigations() {
        val guard = NavigationGuard()
        val token = guard.begin()
        guard.invalidate()
        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun staleTokenNeverBecomesCurrentAgain() {
        val guard = NavigationGuard()
        val stale = guard.begin()
        guard.begin()
        guard.begin()
        assertFalse(guard.isCurrent(stale))
    }
}
