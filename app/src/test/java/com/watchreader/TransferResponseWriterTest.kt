package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 响应写回器：状态行/头/体的字节形态必须与既有手写 sendResponse 完全一致，
 * 包括文本响应固定追加 `; charset=utf-8` 与下载响应不追加字符集。
 */
class TransferResponseWriterTest {

    private fun write(response: TransferResponse): String {
        val out = ByteArrayOutputStream()
        HttpResponseWriter.write(out, response)
        return out.toString("UTF-8")
    }

    @Test
    fun `文本响应固定追加字符集并以 close 收尾`() {
        val raw = write(TransferResponse.Text(200, "application/json", "{}"))
        assertTrue(raw, raw.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(raw, raw.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertTrue(raw, raw.contains("Content-Length: 2\r\n"))
        assertTrue(raw, raw.contains("Connection: close\r\n\r\n"))
        assertEquals("{}", raw.substringAfter("\r\n\r\n"))
    }

    @Test
    fun `状态码文案覆盖既有全部映射`() {
        assertEquals("OK", HttpResponseWriter.statusText(200))
        assertEquals("No Content", HttpResponseWriter.statusText(204))
        assertEquals("Bad Request", HttpResponseWriter.statusText(400))
        assertEquals("Unauthorized", HttpResponseWriter.statusText(401))
        assertEquals("Not Found", HttpResponseWriter.statusText(404))
        assertEquals("Payload Too Large", HttpResponseWriter.statusText(413))
        assertEquals("Internal Server Error", HttpResponseWriter.statusText(500))
    }

    @Test
    fun `下载响应按文件长度写头并原样拷出文件内容`() {
        val file = File.createTempFile("watchreader-dl", ".txt")
        file.writeText("abc")
        val raw = write(TransferResponse.Download(200, file, "text/plain; charset=utf-8", "三 体.txt"))
        assertTrue(raw, raw.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(raw, raw.contains("Content-Type: text/plain; charset=utf-8\r\n"))
        assertTrue(raw, raw.contains("Content-Length: 3\r\n"))
        assertTrue(raw, raw.contains("Content-Disposition: attachment; filename*=UTF-8''%E4%B8%89%20%E4%BD%93.txt\r\n"))
        assertEquals("abc", raw.substringAfter("\r\n\r\n"))
        file.delete()
    }

    @Test
    fun `下载文件名空格编码为百分号而非加号`() {
        assertEquals("%E4%B8%89%20%E4%BD%93.txt", HttpResponseWriter.encodeDownloadName("三 体.txt"))
        assertEquals("a%2Bb.txt", HttpResponseWriter.encodeDownloadName("a+b.txt"))
    }
}
