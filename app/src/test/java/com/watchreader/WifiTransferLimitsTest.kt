package com.watchreader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传书服务硬约束：上传体积上限与书籍清单 JSON 的转义安全。
 * 旧实现无任何体积上限，且 handleGetBooks / 上传成功响应均手写 JSON 拼接（只转义 \ 与 "）。
 */
class WifiTransferLimitsTest {

    @Test
    fun `上传上限为 50MB 且恰好等于上限时放行`() {
        assertEquals(52_428_800L, WifiTransferServer.MAX_UPLOAD_BYTES)
        assertFalse(WifiTransferServer.uploadExceedsLimit(WifiTransferServer.MAX_UPLOAD_BYTES))
        assertTrue(WifiTransferServer.uploadExceedsLimit(WifiTransferServer.MAX_UPLOAD_BYTES + 1))
        assertTrue(WifiTransferServer.uploadExceedsLimit(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `未声明长度的上传不预判由流式累计兜底`() {
        assertFalse(WifiTransferServer.uploadExceedsLimit(-1L))
        assertFalse(WifiTransferServer.uploadExceedsLimit(0L))
    }

    @Test
    fun `书籍清单 JSON 对特殊文件名可原样往返`() {
        val names = listOf("正常.epub", "带\"引号\".txt", "带\\反斜杠.txt", "带\n换行.txt", "中文 空格.mobi")
        val entries = names.mapIndexed { i, name -> BookFileEntry(name, i.toLong(), i.toLong() * 2) }
        val json = WifiTransferServer.bookListJson(entries)
        val array = JSONArray(json)
        assertEquals(names.size, array.length())
        names.forEachIndexed { i, name ->
            assertEquals(name, array.getJSONObject(i).getString("name"))
            assertEquals(i.toLong(), array.getJSONObject(i).getLong("size"))
        }
    }

    @Test
    fun `空清单编码为空数组`() {
        assertEquals(0, JSONArray(WifiTransferServer.bookListJson(emptyList())).length())
    }
}
