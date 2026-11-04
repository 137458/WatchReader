package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份解码契约：结构损坏的备份必须拒绝，而不是解出「空书架」让恢复流程把用户数据洗掉。
 * 旧实现 `DataStoreManager.parseBookShelf(root.optJSONArray("shelf")?.toString())`：
 * shelf 是字符串/全坏条目时 optJSONArray 返回 null → 空列表 → decode 成功 → restore 写入空书架。
 */
class BackupCodecContractTest {

    private val head = "{\"type\":\"watchreader_backup\",\"version\":1"

    private fun failCode(json: String): String {
        val result = BackupCodec.decodeResult(json)
        assertTrue("期望解码失败但成功：$json", result is BackupCodec.DecodeResult.Failure)
        return (result as BackupCodec.DecodeResult.Failure).code
    }

    @Test
    fun `shelf 字段不是数组时拒绝解码`() {
        assertNull(BackupCodec.decode(head + ",\"shelf\":\"oops\"}"))
        assertEquals(BackupCodec.ERROR_CORRUPT_SHELF, failCode(head + ",\"shelf\":\"oops\"}"))
    }

    @Test
    fun `shelf 条目全部无法解析时拒绝解码`() {
        assertNull(BackupCodec.decode(head + ",\"shelf\":[{\"title\":\"无 uri\"}]}"))
        assertEquals(BackupCodec.ERROR_CORRUPT_SHELF, failCode(head + ",\"shelf\":[{\"title\":\"无 uri\"}]}"))
    }

    @Test
    fun `bookmarks 结构损坏时拒绝解码`() {
        val json = head + ",\"shelf\":[],\"bookmarks\":[{\"uri\":\"content://a\",\"list\":\"x\"}]}"
        assertNull(BackupCodec.decode(json))
        assertEquals(BackupCodec.ERROR_CORRUPT_BOOKMARKS, failCode(json))
    }

    @Test
    fun `失败结果携带明确错误码`() {
        assertEquals(BackupCodec.ERROR_EMPTY, failCode(""))
        assertEquals(BackupCodec.ERROR_MALFORMED_JSON, failCode("not json at all"))
        assertEquals(BackupCodec.ERROR_WRONG_TYPE, failCode("{}"))
        assertEquals(BackupCodec.ERROR_UNSUPPORTED_VERSION, failCode("{\"type\":\"watchreader_backup\",\"version\":99}"))
    }

    @Test
    fun `合法备份成功解码且保留书签`() {
        val payload = BackupPayload(
            shelf = listOf(BookItem("content://a", "A", 1, 2, "c", 3L)),
            bookmarks = mapOf("content://a" to listOf(Bookmark(id = "bm1", chapterIndex = 0, charOffset = 5, time = 7L)))
        )
        val result = BackupCodec.decodeResult(BackupCodec.encode(payload, 123L))
        assertTrue(result is BackupCodec.DecodeResult.Success)
        assertEquals(payload, (result as BackupCodec.DecodeResult.Success).payload)
    }
}
