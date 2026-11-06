package com.watchreader

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点按翻页动画窗口：表冠停止惯性时不得截断翻页动画
 *
 * 覆盖 P3-3 的 `fling(0)` 副作用。时间源由测试注入，窗口到期与 owner 归属都能直接断言。
 */
class ReaderPageTurnGateTest {

    private var now = 1000L

    @After
    fun tearDown() {
        ReaderPageTurnGate.clear()
    }

    @Test
    fun sameOwnerIsActiveInsideWindow() {
        val view = Any()
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        assertTrue(ReaderPageTurnGate.isActive(view) { now })

        now += 359L
        assertTrue(ReaderPageTurnGate.isActive(view) { now })
    }

    @Test
    fun windowExpiresAfterDuration() {
        val view = Any()
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        now += 360L
        assertFalse(ReaderPageTurnGate.isActive(view) { now })
    }

    @Test
    fun otherOwnerIsNeverActive() {
        val view = Any()
        val other = Any()
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        assertFalse(ReaderPageTurnGate.isActive(other) { now })
    }

    @Test
    fun newerPageTurnRestartsWindow() {
        val view = Any()
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        now += 300L
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        now += 100L
        assertTrue(ReaderPageTurnGate.isActive(view) { now })
    }

    @Test
    fun clearDeactivatesGate() {
        val view = Any()
        ReaderPageTurnGate.begin(view, durationMs = 360L) { now }
        ReaderPageTurnGate.clear()
        assertFalse(ReaderPageTurnGate.isActive(view) { now })
    }
}
