package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 请求头解析：解析后输入必须停在 body 起点，坏输入一律返回 null 而不是抛异常，
 * 让连接层能用「关连接」表达所有畸形请求。
 */
class HttpRequestParserTest {

    private fun parse(raw: String): HttpRequest? =
        HttpRequestParser.read(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `解析请求行与头后输入恰好停在 body 起点`() {
        val input = ByteArrayInputStream(
            (
                "POST /upload?filename=a.txt HTTP/1.1\r\n" +
                    "Content-Length: 5\r\n" +
                    "X-Filename: b.txt\r\n\r\n" +
                    "hello"
                ).toByteArray(Charsets.UTF_8)
        )
        val request = HttpRequestParser.read(input)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/upload?filename=a.txt", request.target)
        assertEquals("5", request.headers.of("content-length"))
        assertEquals("b.txt", request.headers.of("x-filename"))
        assertEquals("hello", String(input.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `头名统一小写且同名头保留最后一次出现`() {
        val request = parse("GET / HTTP/1.1\r\nToken: first\r\nTOKEN:  second \r\n\r\n")!!
        // 值已 trim，键大小写不敏感
        assertEquals("second", request.headers.of("token"))
        assertEquals("second", request.headers.of("ToKeN"))
        assertNull(request.headers.of("referer"))
    }

    @Test
    fun `空输入与畸形请求行返回 null 由连接层关连接`() {
        assertNull(parse(""))
        assertNull(parse("\r\n\r\n"))
        // 请求行只有方法没有路径
        assertNull(parse("GET\r\n\r\n"))
    }

    @Test
    fun `EOF 前没有空行也按已有字节解析`() {
        val request = parse("GET /status HTTP/1.1\r\nHost: watch\r\n")
        assertNotNull(request)
        assertEquals("/status", request!!.target)
        assertEquals("watch", request.headers.of("host"))
    }

    @Test
    fun `超过字节上限立即停止且不吞掉后续字节`() {
        val raw = "GET /status HTTP/1.1\r\nX-Long: " + "a".repeat(200) + "\r\n\r\n"
        val input = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        val request = HttpRequestParser.read(input, maxHeaderBytes = 32)
        assertNotNull(request)
        assertEquals("/status", request!!.target)
        assertTrue("上限之后仍有未读取字节", input.available() > 0)
    }

    @Test
    fun `query 惰性解析且 queryOf 按参数名顺序取第一个命中`() {
        val request = parse("GET /api/download?filename=%E4%B8%89%E4%BD%93.txt&token=t HTTP/1.1\r\n\r\n")!!
        assertEquals("三体.txt", request.queryOf("name", "filename"))
        assertEquals("t", request.query["token"])
        assertNull(request.queryOf("name"))
    }
}
