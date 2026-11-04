package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签持久化契约：缺失 id/time 时的派生值必须稳定（旧实现每次读回重新随机 UUID，
 * 导致「删除指定书签」在两次读取之间指向不存在的 id），键名格式保持历史兼容。
 */
class BookmarkPersistenceTest {

    @Test
    fun `缺失 id 与 time 的书签按内容稳定派生`() {
        val json = "[{\"chapterIndex\":2,\"charOffset\":100,\"chapterTitle\":\"二\",\"snippet\":\"s\"}]"
        val first = BookmarkStore.decode(json).single()
        val second = BookmarkStore.decode(json).single()
        assertEquals(first.id, second.id)
        assertEquals("bm-2-100", first.id)
        assertEquals(0L, first.time)
    }

    @Test
    fun `书签键名保持历史哈希格式`() {
        val uri = "content://media/external/file/42"
        assertEquals("bookmarks_" + uri.hashCode(), BookmarkStore.keyName(uri))
    }

    @Test
    fun `删书时只清理该书遗留的书签键`() {
        val keepUri = "content://keep"
        val goneUri = "content://gone"
        val stale = BookmarkStore.staleKeyNames(
            existingKeyNames = listOf(
                "bookmarks_" + keepUri.hashCode(),
                "bookmarks_" + goneUri.hashCode(),
                "book_shelf_json"
            ),
            keepUris = listOf(keepUri)
        )
        assertEquals(listOf("bookmarks_" + goneUri.hashCode()), stale)
    }

    @Test
    fun `写回书签保留既有条目并跳过非对象元素`() {
        val json = "[{\"id\":\"bm1\",\"chapterIndex\":0,\"charOffset\":1,\"time\":5},42]"
        val rewritten = BookmarkStore.rewrite(json) { current ->
            current + Bookmark(id = "bm9", chapterIndex = 1, charOffset = 7, time = 9L)
        }
        val parsed = BookmarkStore.decode(rewritten)
        assertEquals(2, parsed.size)
        assertEquals(listOf("bm9", "bm1"), parsed.map { it.id })
        assertTrue(parsed.none { it.id.isEmpty() })
    }

    @Test
    fun `删书后书签键名仍在集合之外时不被误删`() {
        val keep = "content://keep"
        val names = listOf("bookmarks_" + keep.hashCode())
        assertTrue(BookmarkStore.staleKeyNames(names, listOf(keep)).isEmpty())
    }
}
