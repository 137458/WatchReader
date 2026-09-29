package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wi-Fi 传书访问令牌鉴权纯函数测试：
 * 令牌从请求路径 query 提取并以恒时比较校验，杜绝同网段未授权访问。
 */
class WifiTransferServerAuthTest {

    // ── parseQueryParams（companion 提纯，供令牌提取与既有路由复用） ──

    @Test
    fun `parseQueryParams 提取 token 参数`() {
        val params = WifiTransferServer.parseQueryParams("/api/books?token=abc123&name=x")
        assertEquals("abc123", params["token"])
        assertEquals("x", params["name"])
    }

    @Test
    fun `parseQueryParams 键统一小写并解码 URL 编码值`() {
        val params = WifiTransferServer.parseQueryParams("/api/books?TOKEN=a%20b&Name=%E4%B8%89%E4%BD%93")
        assertEquals("a b", params["token"])
        assertEquals("三体", params["name"])
    }

    @Test
    fun `parseQueryParams 无 query 与畸形参数安全退化`() {
        assertTrue(WifiTransferServer.parseQueryParams("/api/books").isEmpty())
        assertTrue(WifiTransferServer.parseQueryParams("/api/books?").isEmpty())
        assertTrue(WifiTransferServer.parseQueryParams("/api/books?broken").isEmpty())
        // 空值参数按既有契约原样存为空串，授权与否交由 tokenMatches 拒绝
        assertEquals("", WifiTransferServer.parseQueryParams("/?token=")["token"])
    }

    // ── generateToken ──

    @Test
    fun `generateToken 为 12 位十六进制且每次不同`() {
        val a = WifiTransferServer.generateToken()
        val b = WifiTransferServer.generateToken()
        assertEquals(12, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }

    // ── tokenMatches（红灯：当前桩恒 false） ──

    @Test
    fun `tokenMatches 相同令牌通过`() {
        assertTrue(WifiTransferServer.tokenMatches("abc123def456", "abc123def456"))
    }

    @Test
    fun `tokenMatches 令牌不一致与空值拒绝`() {
        assertFalse(WifiTransferServer.tokenMatches("000000000000", "abc123def456"))
        assertFalse(WifiTransferServer.tokenMatches(null, "abc123def456"))
        assertFalse(WifiTransferServer.tokenMatches("", "abc123def456"))
    }
}
