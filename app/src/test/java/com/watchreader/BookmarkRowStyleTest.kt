package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签行视觉口径：两段式删除键的文案/热区，以及书签摘要单行文案。
 *
 * 热区断言对齐 ADR-018（自定义可点组件最小触控高度 ≥48dp）：
 * 旧实现删除键 minHeight 只有 42dp，在 466px 圆屏上属于必踩的误触区。
 */
class BookmarkRowStyleTest {

    @Test
    fun testDeleteKeyHotZoneMeetsAdr018Minimum() {
        assertTrue(BookmarkRowStyle.deleteVisual(isPending = false).touchDp >= 48)
        assertTrue(BookmarkRowStyle.deleteVisual(isPending = true).touchDp >= 48)
        assertTrue(BookmarkRowStyle.deleteVisual(isPending = false).minWidthDp >= 48)
    }

    @Test
    fun testDeleteKeyTwoStageLabels() {
        val idle = BookmarkRowStyle.deleteVisual(isPending = false)
        val pending = BookmarkRowStyle.deleteVisual(isPending = true)
        assertEquals("✕", idle.label)
        assertEquals("确认删除", pending.label)
        assertFalse(idle.bold)
        assertTrue(pending.bold)
        // 待确认态更宽，避免四字文案被 42dp 挤成两行
        assertTrue(pending.minWidthDp > idle.minWidthDp)
    }

    @Test
    fun testEmptySnippetHasNoLine() {
        assertNull(BookmarkRowStyle.snippetLabel(""))
    }

    @Test
    fun testShortSnippetIsNotEllipsized() {
        // 旧实现无条件追加「…」，完整短摘要被显示成未完片段
        assertEquals("“今天天气不错”", BookmarkRowStyle.snippetLabel("今天天气不错"))
    }

    @Test
    fun testLongSnippetKeepsThirtyCharsAndEllipsis() {
        val label = BookmarkRowStyle.snippetLabel("字".repeat(40))!!
        assertEquals("“" + "字".repeat(30) + "…”", label)
        // 恰好 30 字不截断：省略号只在真正被截断时出现
        assertEquals("“" + "字".repeat(30) + "”", BookmarkRowStyle.snippetLabel("字".repeat(30)))
    }
}
