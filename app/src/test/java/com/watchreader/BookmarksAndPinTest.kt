package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksAndPinTest {

    @Test
    fun testBookShelfPinSerialization() {
        val books = listOf(
            BookItem("content://1", "未置顶书", 0, 1000, "第一章", 1000L, isPinned = false),
            BookItem("content://2", "已置顶书", 0, 1000, "第一章", 500L, isPinned = true)
        )

        val json = DataStoreManager.serializeBookShelf(books)
        val parsed = DataStoreManager.parseBookShelf(json)

        assertEquals(2, parsed.size)
        // 置顶的书籍应该排在最前面
        assertEquals("已置顶书", parsed[0].title)
        assertTrue(parsed[0].isPinned)
        assertEquals("未置顶书", parsed[1].title)
        assertFalse(parsed[1].isPinned)
    }

    @Test
    fun testBookmarkCreationAndFields() {
        val bm = Bookmark(
            id = "bm-123",
            chapterIndex = 3,
            chapterTitle = "第四章 绝境逢生",
            charOffset = 5230,
            snippet = "少年站在悬崖边上，回望来时的路。",
            time = 1700000000000L
        )

        assertEquals("bm-123", bm.id)
        assertEquals(3, bm.chapterIndex)
        assertEquals(5230, bm.charOffset)
        assertTrue(bm.snippet.contains("绝境逢生") || bm.snippet.contains("悬崖边上"))
    }
}
