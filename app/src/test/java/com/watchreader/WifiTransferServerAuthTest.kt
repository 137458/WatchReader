package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `generateToken 为 12 位 base62 且抽样无碰撞`() {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val tokens = List(64) { WifiTransferServer.generateToken() }
        tokens.forEach { token ->
            assertEquals(12, token.length)
            assertTrue("非 base62 字符：$token", token.all { it in alphabet })
        }
        assertEquals("64 次抽样出现重复令牌", tokens.size, tokens.toSet().size)
        // 旧实现取 UUID 去掉横线后前 12 位，字符集恒为小写十六进制
        assertTrue(
            "令牌字符集仍停留在十六进制",
            tokens.any { token -> token.any { it !in "0123456789abcdef" } }
        )
    }

    // ── tokenMatches ──

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
