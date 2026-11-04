package com.watchreader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域 store 拆分后的跨模块契约。
 *
 * 门面背后只剩三条真实接缝，这里逐条锁死，避免下次「再拆一次」时把共享实现拆成两份：
 * 1) 书签键名构造——删书联动与整体恢复共用同一口径；
 * 2) 书架 JSON schema——落盘与备份共用同一序列化实现；
 * 3) 备份解码——结构损坏的档必须拒绝，绝不能被解成空数据。
 *
 * 未覆盖：接收 DataStore 事务对象的核心方法（writeShelf / applyAdd / writeRestore）
 * 需要 MutablePreferences 实例，JVM 侧拿不到；为它们造假 adapter 只会多一层
 * 只有测试消费的接缝，故不引入。
 */
class StoreBoundaryTest {

    @Test
    fun `书签键名是删书联动与整体恢复的唯一口径`() {
        val keep = "content://keep"
        val gone = "content://gone"
        assertEquals(BookmarkStore.keyName(gone), BookmarkStore.keyOf(gone).name)
        val stale = BookmarkStore.staleKeyNames(
            existingKeyNames = listOf(BookmarkStore.keyName(keep), BookmarkStore.keyName(gone), "book_shelf_json"),
            keepUris = listOf(keep)
        )
        assertEquals(listOf(BookmarkStore.keyName(gone)), stale)
    }

    @Test
    fun `书架落盘与备份共用同一份 JSON schema`() {
        val books = listOf(BookItem("content://1", "书一", 10, 100, "一", 1L, isPinned = true, finished = false))
        val item = JSONArray(ShelfStore.encodeShelf(books)).getJSONObject(0)
        assertEquals("content://1", item.getString("uri"))
        assertEquals("书一", item.getString("title"))
        assertEquals(10, item.getInt("offset"))
        assertEquals(100, item.getInt("total"))
        assertEquals("一", item.getString("chapter"))
        assertEquals(1L, item.getLong("time"))
        assertTrue(item.getBoolean("pinned"))
        assertFalse(item.getBoolean("finished"))
        assertEquals("书一", ShelfStore.decodeShelf(ShelfStore.encodeShelf(books)).items.single().title)
    }

    @Test
    fun `书架写回保留未识别条目且不改变 schema`() {
        val json = "[{\"uri\":\"content://a\",\"title\":\"A\"},{\"title\":\"坏条目\"}]"
        val rewritten = ShelfStore.rewriteShelf(json) { current ->
            current + BookItem("content://c", "C", 5, 50, "", 0L)
        }
        val snapshot = ShelfStore.decodeShelf(rewritten)
        assertEquals(listOf("A", "C"), snapshot.items.map { it.title })
        assertEquals(1, snapshot.unparsedRaw.size)
    }

    @Test
    fun `备份恢复拒绝结构损坏的书架`() {
        val corrupt = "{\"type\":\"watchreader_backup\",\"version\":1,\"shelf\":[{\"title\":\"缺 uri\"}]}"
        val result = BackupCodec.decodeResult(corrupt)
        assertTrue(result is BackupCodec.DecodeResult.Failure)
        assertEquals(BackupCodec.ERROR_CORRUPT_SHELF, (result as BackupCodec.DecodeResult.Failure).code)
        assertNull(BackupCodec.decode(corrupt))
    }

    @Test
    fun `各领域解码器对空输入退化为空集合`() {
        assertTrue(ShelfStore.decodeShelf(null).items.isEmpty())
        assertTrue(ShelfStore.decodeShelf("").items.isEmpty())
        assertTrue(BookmarkStore.decode(null).isEmpty())
        assertTrue(ReadingStatsStore.decodeDays(null).isEmpty())
    }
}
