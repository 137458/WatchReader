package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架持久化契约：单条坏数据不得截断整本书架，写回不得丢弃未识别条目。
 *
 * 旧实现（DataStoreManager.parseBookShelf 的 for + getJSONObject/getString + 外层
 * catch → return 已解析部分）遇一条坏 JSON 即中止循环，剩余条目全部丢失；
 * 四个写方再把这份残缺列表整段回写，坏数据一次就被永久洗净。
 */
class ShelfPersistenceTest {

    private fun entryJson(uri: String, title: String, time: Long = 1L): String =
        "{\"uri\":\"$uri\",\"title\":\"$title\",\"offset\":10,\"total\":100,\"chapter\":\"一\",\"time\":$time,\"pinned\":false,\"finished\":false}"

    @Test
    fun `一条坏条目不再截断后续条目`() {
        val json = "[" + entryJson("content://a", "A") + ",{\"title\":\"坏条目：缺 uri\"}," + entryJson("content://b", "B") + "]"
        val parsed = DataStoreManager.parseBookShelf(json)
        assertEquals(listOf("A", "B"), parsed.map { it.title })
    }

    @Test
    fun `非对象元素不再截断后续条目`() {
        val json = "[" + entryJson("content://a", "A") + ",42," + entryJson("content://b", "B") + "]"
        assertEquals(listOf("A", "B"), DataStoreManager.parseBookShelf(json).map { it.title })
    }

    @Test
    fun `写回时保留未识别条目的原始 JSON`() {
        val json = "[" + entryJson("content://a", "A") + ",{\"title\":\"坏条目：缺 uri\"}]"
        val rewritten = DataStoreManager.rewriteShelf(json) { current ->
            current + BookItem("content://c", "C", 5, 50, "", 0L)
        }
        val snapshot = DataStoreManager.parseBookShelfSnapshot(rewritten)
        assertEquals(listOf("A", "C"), snapshot.items.map { it.title })
        assertEquals(1, snapshot.unparsedRaw.size)
        assertTrue("坏条目原文必须回填", rewritten.contains("坏条目：缺 uri"))
    }

    @Test
    fun `空快照写入建出新书架且空数组写回为空数组`() {
        val created = DataStoreManager.rewriteShelf(null) { current ->
            current + BookItem("content://d", "D", 0, 10, "", 1L)
        }
        assertEquals(listOf("D"), DataStoreManager.parseBookShelf(created).map { it.title })
        assertEquals("[]", DataStoreManager.rewriteShelf("[]") { it })
    }

    @Test
    fun `整串非法 JSON 退化为空书架而不是抛异常`() {
        assertTrue(DataStoreManager.parseBookShelf("not-a-json-array").isEmpty())
        assertTrue(DataStoreManager.parseBookShelfSnapshot("{").items.isEmpty())
    }
}
