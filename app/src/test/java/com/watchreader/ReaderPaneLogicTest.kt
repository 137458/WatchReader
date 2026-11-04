package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderPaneLogic] 的纯 JVM 驱动测试。
 *
 * 这些判定原先内联在 672 行的 ReaderPane 里、埋在 View 监听器与布局回调之中，只能靠真机观察；
 * 抽成纯函数后逐条钉死具体数值，任何一条口径被改动都会在 JVM 单测里立刻红灯。
 */
class ReaderPaneLogicTest {

    // --- 被覆盖页 / 自动滚屏引擎同步 ---

    @Test
    fun coveredStopsRunningEngine() {
        assertEquals(
            ReaderPaneLogic.EngineAction.STOP,
            ReaderPaneLogic.engineSyncAction(covered = true, isAutoScrolling = true, engineRunning = true)
        )
    }

    @Test
    fun coveredWithIdleEngineDoesNothing() {
        assertEquals(
            ReaderPaneLogic.EngineAction.NONE,
            ReaderPaneLogic.engineSyncAction(covered = true, isAutoScrolling = false, engineRunning = false)
        )
    }

    @Test
    fun enabledAutoScrollStartsIdleEngine() {
        assertEquals(
            ReaderPaneLogic.EngineAction.START,
            ReaderPaneLogic.engineSyncAction(covered = false, isAutoScrolling = true, engineRunning = false)
        )
    }

    @Test
    fun enabledAutoScrollKeepsRunningEngine() {
        assertEquals(
            ReaderPaneLogic.EngineAction.NONE,
            ReaderPaneLogic.engineSyncAction(covered = false, isAutoScrolling = true, engineRunning = true)
        )
    }

    @Test
    fun disabledAutoScrollStopsRunningEngine() {
        assertEquals(
            ReaderPaneLogic.EngineAction.STOP,
            ReaderPaneLogic.engineSyncAction(covered = false, isAutoScrolling = false, engineRunning = true)
        )
    }

    @Test
    fun disabledAutoScrollWithIdleEngineDoesNothing() {
        assertEquals(
            ReaderPaneLogic.EngineAction.NONE,
            ReaderPaneLogic.engineSyncAction(covered = false, isAutoScrolling = false, engineRunning = false)
        )
    }

    // --- 常亮超时 ---

    @Test
    fun keepScreenOnTimeoutArmedOnlyForStaticReading() {
        assertTrue(ReaderPaneLogic.shouldArmKeepScreenOnTimeout(isAutoScrolling = false))
        assertFalse(ReaderPaneLogic.shouldArmKeepScreenOnTimeout(isAutoScrolling = true))
    }

    // --- 滚动偏移换算 ---

    @Test
    fun lineDomainRequiresBothLineCountAndHeight() {
        assertFalse(ReaderPaneLogic.isLineDomainReady(layoutLineCount = 0, bodyHeightPx = 480))
        assertFalse(ReaderPaneLogic.isLineDomainReady(layoutLineCount = 12, bodyHeightPx = 0))
        assertTrue(ReaderPaneLogic.isLineDomainReady(layoutLineCount = 12, bodyHeightPx = 480))
    }

    @Test
    fun clampBodyScrollYClampsToBodyBand() {
        // 正文从 y=100 起、高 200：可滚动区间是 [0, 199]
        assertEquals(199, ReaderPaneLogic.clampBodyScrollY(scrollY = 350, bodyTop = 100, bodyHeightPx = 200))
        assertEquals(0, ReaderPaneLogic.clampBodyScrollY(scrollY = 50, bodyTop = 100, bodyHeightPx = 200))
        assertEquals(80, ReaderPaneLogic.clampBodyScrollY(scrollY = 180, bodyTop = 100, bodyHeightPx = 200))
    }

    @Test
    fun clampCharOffsetClampsToBodyLength() {
        assertEquals(0, ReaderPaneLogic.clampCharOffset(charOffset = -5, bodyLength = 100))
        assertEquals(100, ReaderPaneLogic.clampCharOffset(charOffset = 105, bodyLength = 100))
        assertEquals(42, ReaderPaneLogic.clampCharOffset(charOffset = 42, bodyLength = 100))
    }

    @Test
    fun rawOffsetByRatioMapsScrollToRawOffset() {
        // 滚到正文半程：本章原文跨度 1000 字（1000..2000）→ 落到 1500
        assertEquals(
            1500,
            ReaderPaneLogic.rawOffsetByRatio(
                scrollY = 200,
                bodyTop = 100,
                bodyHeightPx = 200,
                startCharOffset = 1000,
                endCharOffset = 2000
            )
        )
    }

    @Test
    fun rawOffsetByRatioFallsBackToChapterStartWhenHeightIsZero() {
        assertEquals(
            1000,
            ReaderPaneLogic.rawOffsetByRatio(
                scrollY = 100,
                bodyTop = 100,
                bodyHeightPx = 0,
                startCharOffset = 1000,
                endCharOffset = 2000
            )
        )
    }

    // --- 换章 / 换排版时的位置恢复偏移 ---

    @Test
    fun restoreOffsetKeepsLiveOffsetOnlyInsideSameChapter() {
        // 同章换排版：实时偏移仍在本章区间内 → 以实时偏移为准（否则换字体把位置弹回章首）
        assertEquals(
            1200,
            ReaderPaneLogic.chapterChangeRestoreOffset(
                liveOffset = 1200,
                sameChapter = true,
                hasContent = true,
                startCharOffset = 1000,
                endCharOffset = 2000,
                initialCharOffset = 1000
            )
        )
        // 跨章：一律回到章节入口偏移
        assertEquals(
            1000,
            ReaderPaneLogic.chapterChangeRestoreOffset(
                liveOffset = 1200,
                sameChapter = false,
                hasContent = true,
                startCharOffset = 1000,
                endCharOffset = 2000,
                initialCharOffset = 1000
            )
        )
        // 实时偏移已越出本章区间
        assertEquals(
            1000,
            ReaderPaneLogic.chapterChangeRestoreOffset(
                liveOffset = 2500,
                sameChapter = true,
                hasContent = true,
                startCharOffset = 1000,
                endCharOffset = 2000,
                initialCharOffset = 1000
            )
        )
        // 无正文：退回章节入口偏移
        assertEquals(
            777,
            ReaderPaneLogic.chapterChangeRestoreOffset(
                liveOffset = 1200,
                sameChapter = true,
                hasContent = false,
                startCharOffset = 0,
                endCharOffset = 0,
                initialCharOffset = 777
            )
        )
    }

    @Test
    fun chapterTitleTopPaddingFollowsPrevButtonPresence() {
        assertEquals(8, ReaderPaneLogic.chapterTitleTopPaddingDp(hasPrevChapter = true))
        assertEquals(20, ReaderPaneLogic.chapterTitleTopPaddingDp(hasPrevChapter = false))
    }

    // --- 底部进度指示 ---

    @Test
    fun progressPercentTruncatesAndClamps() {
        assertEquals(5, ReaderPaneLogic.progressPercent(currentOffset = 50, fullTextLength = 1000))
        assertEquals(99, ReaderPaneLogic.progressPercent(currentOffset = 999, fullTextLength = 1000))
        assertEquals(100, ReaderPaneLogic.progressPercent(currentOffset = 2000, fullTextLength = 1000))
        assertEquals(0, ReaderPaneLogic.progressPercent(currentOffset = 500, fullTextLength = 0))
    }

    @Test
    fun progressTextOmitsChapterPartForSingleChapterBook() {
        assertEquals("第 3/10 章 · 42%", ReaderPaneLogic.progressText(chapterNum = 3, totalChapters = 10, percent = 42))
        assertEquals("100%", ReaderPaneLogic.progressText(chapterNum = 1, totalChapters = 1, percent = 100))
    }

    // --- 表冠门限 ---

    @Test
    fun crownScrollEpsilonRejectsJitter() {
        assertFalse(ReaderPaneLogic.isCrownScrollDelta(0.001f))
        assertTrue(ReaderPaneLogic.isCrownScrollDelta(0.0015f))
        assertTrue(ReaderPaneLogic.isCrownScrollDelta(-0.01f))
    }

    @Test
    fun crownAdjustEpsilonRejectsJitter() {
        assertFalse(ReaderPaneLogic.isCrownAdjustDelta(0.05f))
        assertTrue(ReaderPaneLogic.isCrownAdjustDelta(0.06f))
        assertTrue(ReaderPaneLogic.isCrownAdjustDelta(-0.06f))
    }

    @Test
    fun autoScrollSpeedDeltaUsesDirectionOnly() {
        assertEquals(5f, ReaderPaneLogic.autoScrollSpeedDelta(0.3f), 0f)
        assertEquals(-5f, ReaderPaneLogic.autoScrollSpeedDelta(-0.3f), 0f)
    }
}
