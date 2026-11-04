package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架进度的纯参数口径：百分比 → 进度条比例 / 文案。
 * 两条口径原先各写一遍散在继续阅读卡与书籍行里，现在由 ShelfProgress 收口。
 */
class ShelfProgressTest {

    @Test
    fun testZeroPercentFillsNothing() {
        assertEquals(0f, ShelfProgress.fraction(0), 1e-6f)
    }

    @Test
    fun testMidPercentMapsToRatio() {
        // 两组差异化数据：单位数百分比与半数，防止"永远返回 0/1"的假实现
        assertEquals(0.37f, ShelfProgress.fraction(37), 1e-6f)
        assertEquals(0.5f, ShelfProgress.fraction(50), 1e-6f)
    }

    @Test
    fun testFullPercentFillsTrack() {
        assertEquals(1f, ShelfProgress.fraction(100), 1e-6f)
    }

    @Test
    fun testOutOfRangePercentIsClamped() {
        // 进度字段越界时进度条不得画出轨道（负数）或溢出（>100）
        assertEquals(0f, ShelfProgress.fraction(-5), 1e-6f)
        assertEquals(1f, ShelfProgress.fraction(140), 1e-6f)
    }

    @Test
    fun testLabelKeepsRawIntegerWithoutRounding() {
        assertEquals("0%", ShelfProgress.label(0))
        assertEquals("7%", ShelfProgress.label(7))
        assertEquals("100%", ShelfProgress.label(100))
    }

    @Test
    fun testClampedRatioAndRawLabelAreIndependent() {
        // 越界值：比例被钳到 1，文案仍显示真实值 —— 两个口径刻意不一致，锁定该差异
        assertEquals(1f, ShelfProgress.fraction(140), 1e-6f)
        assertEquals("140%", ShelfProgress.label(140))
    }
}

/**
 * 选卷浮层的占位判定与首次定位：分卷模型本身由 ChapterRangesTest 锁定，
 * 这里锁「章节 → 段」的映射与 (当前段 - 1) 的定位偏移。
 */
class RangePickerStyleTest {

    /** 151 章 → 步长 50 → [0,49] [50,99] [100,149] [150,150] */
    private val ranges151 = generateChapterRanges(151)

    @Test
    fun testSegmentsAreClosedIntervals() {
        val second = ranges151[1]
        assertTrue(RangePickerStyle.isCurrent(second, 50))
        assertTrue(RangePickerStyle.isCurrent(second, 99))
        assertFalse(RangePickerStyle.isCurrent(second, 49))
        assertFalse(RangePickerStyle.isCurrent(second, 100))
    }

    @Test
    fun testActiveRangeIndexAtSegmentBoundaries() {
        assertEquals(0, RangePickerStyle.activeRangeIndex(ranges151, 0))
        assertEquals(0, RangePickerStyle.activeRangeIndex(ranges151, 49))
        assertEquals(1, RangePickerStyle.activeRangeIndex(ranges151, 50))
        assertEquals(2, RangePickerStyle.activeRangeIndex(ranges151, 149))
        assertEquals(3, RangePickerStyle.activeRangeIndex(ranges151, 150))
    }

    @Test
    fun testActiveRangeIndexOnLargeBookTail() {
        val ranges = generateChapterRanges(601)
        // 601 章 → 步长 100 → 末段 [600,600] 只有第 601 章一章；599 属第 6 段 [500,599]
        assertEquals(5, RangePickerStyle.activeRangeIndex(ranges, 599))
        assertEquals(6, RangePickerStyle.activeRangeIndex(ranges, 600))
    }

    @Test
    fun testNoMatchReturnsMinusOne() {
        assertTrue(generateChapterRanges(1).isEmpty())
        assertEquals(-1, RangePickerStyle.activeRangeIndex(emptyList(), 0))
        // 未打开书时当前章是 -1，越界章节也不属于任何段
        assertEquals(-1, RangePickerStyle.activeRangeIndex(ranges151, -1))
        assertEquals(-1, RangePickerStyle.activeRangeIndex(ranges151, 151))
    }

    @Test
    fun testEveryChapterMapsToExactlyOneSegment() {
        val ranges = generateChapterRanges(601)
        for (chapter in 0 until 601) {
            val hits = ranges.count { RangePickerStyle.isCurrent(it, chapter) }
            assertEquals("chapter=$chapter 必须恰好命中一段", 1, hits)
            assertEquals(
                "chapter=$chapter 索引映射必须与逐段扫描一致",
                ranges.indexOfFirst { RangePickerStyle.isCurrent(it, chapter) },
                RangePickerStyle.activeRangeIndex(ranges, chapter)
            )
        }
    }

    @Test
    fun testInitialSelectionLeavesOneRowAboveCurrentSegment() {
        // 首次定位的既有口径：当前段上方保留一段，首段则停在顶端
        assertEquals(0, RangePickerStyle.initialSelection(ranges151, 0))
        assertEquals(0, RangePickerStyle.initialSelection(ranges151, 60))
        assertEquals(1, RangePickerStyle.initialSelection(ranges151, 120))
    }

    @Test
    fun testInitialSelectionOnLastSegment() {
        assertEquals(2, RangePickerStyle.initialSelection(ranges151, 150))
        assertEquals(5, RangePickerStyle.initialSelection(generateChapterRanges(601), 600))
    }

    @Test
    fun testInitialSelectionWithoutMatchFallsBackToTop() {
        assertEquals(0, RangePickerStyle.initialSelection(emptyList(), 0))
        assertEquals(0, RangePickerStyle.initialSelection(ranges151, -1))
    }

    @Test
    fun testRowLabelMarksOnlyCurrentSegment() {
        val second = ranges151[1]
        assertEquals("第 51 ~ 100 章 • 正在读", RangePickerStyle.rowLabel(second, 50))
        assertEquals("第 51 ~ 100 章", RangePickerStyle.rowLabel(second, 100))
        assertEquals("第 51 ~ 100 章", RangePickerStyle.rowLabel(second, 49))
    }
}
