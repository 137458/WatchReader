package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节范围分卷：段数、每段长度、最后一段不越界，以及 30 章以内不分卷的阈值。
 */
class ChapterRangesTest {

    @Test
    fun testZeroChaptersYieldsNoRanges() {
        assertTrue(generateChapterRanges(0).isEmpty())
    }

    @Test
    fun testThresholdAtThirtyChapters() {
        // 阈值本身（<=30）不分卷，31 章才开始分卷
        assertTrue(generateChapterRanges(30).isEmpty())
        assertEquals(2, generateChapterRanges(31).size)
    }

    @Test
    fun testSmallBookUsesTwentyFiveStep() {
        val ranges = generateChapterRanges(31)
        assertEquals(ChapterRange(0, 24, "第 1 ~ 25 章"), ranges[0])
        assertEquals(ChapterRange(25, 30, "第 26 ~ 31 章"), ranges[1])
    }

    @Test
    fun testExactlyDivisibleUsesFullLastChunk() {
        val ranges = generateChapterRanges(150)
        assertEquals(6, ranges.size)
        assertTrue(ranges.all { it.endIndex - it.startIndex + 1 == 25 })
        assertEquals(ChapterRange(125, 149, "第 126 ~ 150 章"), ranges.last())
    }

    @Test
    fun testNonDivisibleRemainderBecomesShortLastChunk() {
        // 151 章 → 步长 50 → 50/50/50/1
        val ranges = generateChapterRanges(151)
        assertEquals(4, ranges.size)
        assertEquals(listOf(50, 50, 50, 1), ranges.map { it.endIndex - it.startIndex + 1 })
        assertEquals(ChapterRange(150, 150, "第 151 ~ 151 章"), ranges.last())
    }

    @Test
    fun testLargeBookUsesHundredStepAndStaysInBounds() {
        val ranges = generateChapterRanges(601)
        assertEquals(7, ranges.size)
        assertEquals(600, ranges.last().endIndex)
        assertEquals(1, ranges.last().endIndex - ranges.last().startIndex + 1)
    }

    @Test
    fun testRangesAreContiguousAndBounded() {
        for (total in intArrayOf(31, 151, 601, 1000, 10000)) {
            val ranges = generateChapterRanges(total)
            assertTrue("total=$total 应产生分卷", ranges.isNotEmpty())
            assertEquals("total=$total 首段从 0 起", 0, ranges.first().startIndex)
            assertEquals("total=$total 末段覆盖到 total-1", total - 1, ranges.last().endIndex)
            for (i in ranges.indices) {
                assertTrue("total=$total 第 $i 段长度必须为正", ranges[i].endIndex >= ranges[i].startIndex)
                if (i > 0) {
                    assertEquals("total=$total 第 $i 段必须紧接上一段", ranges[i - 1].endIndex + 1, ranges[i].startIndex)
                }
            }
        }
    }
}

/**
 * 两段式删除的待确认状态机：至多一个挂起项，撤销/放行都回到唯一确定的状态。
 * 定时器留在适配器侧（Android Handler），这里只锁 id 归属与回滚语义。
 */
class BookmarkDeleteGateTest {

    @Test
    fun testRequestMarksSinglePendingId() {
        val gate = BookmarkDeleteGate()
        assertNull(gate.pendingId)
        gate.request("a")
        assertTrue(gate.isPending("a"))
        // 请求另一个 id 不产生第二个挂起项，旧 id 立即失效
        gate.request("b")
        assertTrue(gate.isPending("b"))
        assertFalse(gate.isPending("a"))
        assertEquals("b", gate.pendingId)
    }

    @Test
    fun testCancelRestoresIdleRowVisual() {
        val gate = BookmarkDeleteGate()
        gate.request("a")
        assertTrue(gate.cancel())
        assertNull(gate.pendingId)
        // 撤销后该行按 isPending=false 重新渲染：回到 ✕ 与 48dp 最小宽度，列表顺序不做任何改动
        val visual = BookmarkRowStyle.deleteVisual(gate.isPending("a"))
        assertEquals("✕", visual.label)
        assertEquals(48, visual.minWidthDp)
        assertEquals(48, visual.touchDp)
    }

    @Test
    fun testConfirmHandsOutPendingIdOnlyOnce() {
        val gate = BookmarkDeleteGate()
        gate.request("a")
        assertEquals("a", gate.confirm())
        assertNull(gate.pendingId)
        assertNull(gate.confirm())
        assertFalse(gate.isPending("a"))
    }

    @Test
    fun testCancelWithoutPendingIsNoOp() {
        val gate = BookmarkDeleteGate()
        assertFalse(gate.cancel())
        assertNull(gate.pendingId)
    }

    @Test
    fun testRevertWindowMatchesShelfDelete() {
        assertEquals(3200L, BookmarkDeleteGate.REVERT_DELAY_MS)
    }
}
