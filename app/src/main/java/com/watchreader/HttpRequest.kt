package com.watchreader

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * 请求头表：名称统一小写，同名头保留最后一次出现（与逐行扫描的既有语义一致）。
 *
 * 头值已 trim；缺失返回 null（调用方需自行决定默认值，本类不臆造默认）。
 */
internal class HttpHeaders(private val values: Map<String, String>) {

    fun of(name: String): String? = values[name.lowercase()]

    val asMap: Map<String, String> get() = values

    companion object {
        val EMPTY = HttpHeaders(emptyMap())
    }
}

/**
 * 一次已解析完成的 HTTP 请求（方法、原始 target、头表）。
 *
 * [target] 保留 query 原文，路由与既有实现一样按「路径 + query」前缀匹配；
 * [query] 惰性解析，键小写、值做 URL 解码。
 */
internal data class HttpRequest(
    val method: String,
    val target: String,
    val headers: HttpHeaders
) {
    val query: Map<String, String> by lazy { parseQuery(target) }

    /** 按顺序取第一个存在的查询参数；全部缺失返回 null */
    fun queryOf(vararg names: String): String? {
        for (name in names) {
            query[name]?.let { return it }
        }
        return null
    }
}

/**
 * 手写 HTTP/1.1 请求头解析（纯 JVM，无 Android 依赖）。
 *
 * 约定：
 * - 读到空行为止（连续两个 LF，CR 仅为分隔符），最多 [MAX_HEADER_BYTES] 字节；
 * - 输入在 EOF 前没有任何字节 → 返回 null（连接已断，调用方直接关 socket）；
 * - 请求行不足两段（畸形） → 返回 null；
 * - 解析成功后 InputStream 恰好停在 body 起点，调用方可直接接着读。
 */
internal object HttpRequestParser {

    const val MAX_HEADER_BYTES: Int = 65536

    fun read(input: InputStream, maxHeaderBytes: Int = MAX_HEADER_BYTES): HttpRequest? {
        val block = readHeaderBlock(input, maxHeaderBytes) ?: return null
        return parseHeaderBlock(block)
    }

    /** 逐字节读到空行结束；返回原始头块文本，EOF 且零字节时返回 null */
    internal fun readHeaderBlock(input: InputStream, maxHeaderBytes: Int = MAX_HEADER_BYTES): String? {
        val buffer = ByteArrayOutputStream()
        var consecutiveNewlines = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            buffer.write(b)
            if (b == '\n'.code) {
                consecutiveNewlines++
                if (consecutiveNewlines >= 2) break
            } else if (b != '\r'.code) {
                consecutiveNewlines = 0
            }
            if (buffer.size() > maxHeaderBytes) break
        }
        if (buffer.size() == 0) return null
        return buffer.toString("UTF-8")
    }

    fun parseHeaderBlock(block: String): HttpRequest? {
        val lines = block.lines()
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            if (name.isEmpty()) continue
            headers[name] = line.substring(colon + 1).trim()
        }
        return HttpRequest(method = parts[0].uppercase(), target = parts[1], headers = HttpHeaders(headers))
    }
}

/**
 * 解析 target 中的查询串：键统一小写，值 URL 解码（失败保留原文），段内必须恰好一个 "="。
 *
 * 空串/无 "?"/畸形段一律跳过而不是抛错——鉴权与路由都要在坏输入上给出确定结果。
 */
internal fun parseQuery(rawPath: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val qIdx = rawPath.indexOf('?')
    if (qIdx >= 0 && qIdx + 1 < rawPath.length) {
        val queryStr = rawPath.substring(qIdx + 1)
        for (p in queryStr.split("&")) {
            val kv = p.split("=")
            if (kv.size == 2 && kv[0].isNotEmpty()) {
                try {
                    map[kv[0].lowercase()] = URLDecoder.decode(kv[1], "UTF-8")
                } catch (_: Exception) {
                    map[kv[0].lowercase()] = kv[1]
                }
            }
        }
    }
    return map
}
