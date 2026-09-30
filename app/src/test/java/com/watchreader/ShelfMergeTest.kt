package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架条目合并核心测试：saveReadingPosition 与 updateBookInShelf 的共享纯函数。
 *
 * 语义约束（单事务原子合并，[ADR-003]）：
 * - 新条目插入首位，标题取惰性 fallback（仅新条目触发，避免每次保存查询 ContentResolver）；
 * - 已有条目原位更新（不挪位），保留原标题与置顶态；
 * - totalChars=0 / 空章节标题表示"未提供"，保留旧值而非清空。
 */
class ShelfMergeTest {

    @Test
    fun testNewBookInsertedAtFrontWithFallbackTitle() {
        val merged = mergeBookEntry(
            currentList = emptyList(),
            uriStr = "content://a",
            charOffset = 120,
            totalChars = 9000,
            chapterTitle = "第二章",
            fallbackTitle = { "回退标题.epub" },
            nowMs = 7777L
        )

        assertEquals(1, merged.size)
        val item = merged[0]
        assertEquals("content://a", item.uriString)
        assertEquals("回退标题.epub", item.title)
        assertEquals(120, item.charOffset)
        assertEquals(9000, item.totalChars)
        assertEquals("第二章", item.lastChapterTitle)
        assertEquals(7777L, item.lastReadTime)
        assertFalse(item.isPinned)
    }

    @Test
    fun testExistingBookUpdatedInPlaceKeepsTitleAndPin() {
        val existing = BookItem(
            uriString = "content://a",
            title = "原有书名",
            charOffset = 10,
            totalChars = 5000,
            lastChapterTitle = "第一章",
            lastReadTime = 100L,
            isPinned = true
        )
        val other = BookItem("content://b", "另一本", 0, 100, "第一章", 50L, isPinned = false)

        val merged = mergeBookEntry(
            currentList = listOf(other, existing),
            uriStr = "content://a",
            charOffset = 250,
            totalChars = 0,
            chapterTitle = "第三章",
            fallbackTitle = { throw AssertionError("已有条目不得触发 fallback 标题查询") },
            nowMs = 999L
        )

        assertEquals(2, merged.size)
        // 原位更新：仍占据原下标，不因最近阅读被挪到首位
        val updated = merged[1]
        assertEquals("content://a", updated.uriString)
        assertEquals("原有书名", updated.title)
        assertTrue(updated.isPinned)
        assertEquals(250, updated.charOffset)
        // totalChars=0 表示未提供，保留旧值
        assertEquals(5000, updated.totalChars)
        assertEquals(999L, updated.lastReadTime)
        // 未触及的另一本保持原样
        assertEquals("另一本", merged[0].title)
    }

    @Test
    fun testEmptyChapterTitleKeepsOldTitle() {
        val existing = BookItem(
            uriString = "content://a",
            title = "书名",
            charOffset = 10,
            totalChars = 5000,
            lastChapterTitle = "旧章节",
            lastReadTime = 100L,
            isPinned = false
        )

        val merged = mergeBookEntry(
            currentList = listOf(existing),
            uriStr = "content://a",
            charOffset = 30,
            totalChars = 6000,
            chapterTitle = "",
            fallbackTitle = { "不应触发" },
            nowMs = 1234L
        )

        assertEquals("旧章节", merged[0].lastChapterTitle)
        assertEquals(6000, merged[0].totalChars)
        assertEquals(30, merged[0].charOffset)
    }
}
