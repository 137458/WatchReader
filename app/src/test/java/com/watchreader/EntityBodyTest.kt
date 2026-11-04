package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 传输体分帧：content-length 严格截断、chunked 剥帧、两者皆无则透传。
 * 旧实现只按 content-length 读固定字节数，chunked 上传会把块长度行写进书里。
 */
class EntityBodyTest {

    private fun headers(vararg pairs: Pair<String, String>) = HttpHeaders(pairs.toMap())

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).let { ByteArrayInputStream(it) }

    private fun readAll(body: java.io.InputStream): String = String(body.readBytes(), Charsets.UTF_8)

    @Test
    fun `content-length 精确截断且不多读一个字节`() {
        val raw = bytes("abcdef")
        val body = EntityBody.open(headers("content-length" to "3"), raw)
        assertEquals("abc", readAll(body))
        assertEquals("def", readAll(raw))
    }

    @Test
    fun `无分帧头时原样透传底层流`() {
        val raw = bytes("raw body")
        val body = EntityBody.open(headers(), raw)
        assertSame(raw, body)
        assertEquals("raw body", readAll(body))
    }

    @Test
    fun `chunked 单块解码剥离长度行`() {
        val body = EntityBody.open(headers("transfer-encoding" to "chunked"), bytes("5\r\nhello\r\n0\r\n\r\n"))
        assertEquals("hello", readAll(body))
    }

    @Test
    fun `chunked 多块与分块扩展与 trailer 全被剥离`() {
        val raw = "3;ext=1\r\nabc\r\n2\r\nde\r\n0\r\nX-Trace: 1\r\n\r\n"
        val body = EntityBody.open(headers("transfer-encoding" to "chunked"), bytes(raw))
        assertEquals("abcde", readAll(body))
    }

    @Test(expected = IOException::class)
    fun `chunked 非法长度行抛 IO 异常`() {
        val body = EntityBody.open(headers("transfer-encoding" to "chunked"), bytes("zz\r\nabc\r\n0\r\n\r\n"))
        readAll(body)
    }

    @Test
    fun `包装流关闭不关闭底层 socket 流`() {
        val raw = bytes("abcdef")
        val body = EntityBody.open(headers("content-length" to "3"), raw)
        assertEquals("abc", readAll(body))
        body.close()
        // 已读到 3 字节，关闭包装流后底层流仍停在同一位置且未被关闭
        assertEquals('d'.code, raw.read())
    }
}
