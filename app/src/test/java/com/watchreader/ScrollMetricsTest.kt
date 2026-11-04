package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 最大滚动量口径：阅读页底 padding 与 child 顶偏移都必须计入
 */
class ScrollMetricsTest {

    @Test
    fun testReaderViewportIncludesBottomPadding() {
        // 466 视口、内容 bottom 2000、底 padding 56 → 可滚动 1590（而非 1534）
        assertEquals(1590, ScrollMetrics.maxScrollY(contentBottom = 2000, paddingBottom = 56, viewportHeight = 466))
    }

    @Test
    fun testChildTopOffsetCountsViaBottom() {
        // child.top = 50、height = 1000 → bottom 1050；用 bottom 而非 height
        assertEquals(640, ScrollMetrics.maxScrollY(contentBottom = 1050, paddingBottom = 56, viewportHeight = 466))
        // 等价校验：漏掉 top 会少 50px，正是双口径分歧的来源
        assertEquals(590, ScrollMetrics.maxScrollY(contentBottom = 1000, paddingBottom = 56, viewportHeight = 466))
    }

    @Test
    fun testShortContentClampsToZero() {
        assertEquals(0, ScrollMetrics.maxScrollY(contentBottom = 300, paddingBottom = 56, viewportHeight = 466))
        assertEquals(0, ScrollMetrics.maxScrollY(contentBottom = 466, paddingBottom = 0, viewportHeight = 466))
    }

    @Test
    fun testZeroViewportNeverNegative() {
        assertEquals(0, ScrollMetrics.maxScrollY(contentBottom = 0, paddingBottom = 0, viewportHeight = 0))
    }

    @Test
    fun testShortLaidOutContentAdvancesChapter() {
        // 内容不足一屏：maxScrollY 为 0，但内容已布局，必须翻章而非空转
        assertEquals(true, ScrollMetrics.shouldAdvance(currentY = 0, maxScrollY = 0, contentLaidOut = true))
    }

    @Test
    fun testUnlaidOutContentDoesNotAdvance() {
        // 尚未布局（无子视图 / 高度 0）：本帧跳过，否则首次组合就误触翻章
        assertEquals(false, ScrollMetrics.shouldAdvance(currentY = 0, maxScrollY = 0, contentLaidOut = false))
    }

    @Test
    fun testNormalBoundaryAdvancesOnlyAtEnd() {
        assertEquals(false, ScrollMetrics.shouldAdvance(currentY = 1589, maxScrollY = 1590, contentLaidOut = true))
        assertEquals(true, ScrollMetrics.shouldAdvance(currentY = 1590, maxScrollY = 1590, contentLaidOut = true))
        assertEquals(true, ScrollMetrics.shouldAdvance(currentY = 1595, maxScrollY = 1590, contentLaidOut = true))
    }
}
