package com.watchreader

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * HTTP 传输体分帧（纯 JVM）：把 socket 字节流还原成「恰好一个实体体」的字节流。
 *
 * 三种分帧按 RFC 9112 优先级选择：
 * 1. `Transfer-Encoding: chunked` → [ChunkedInputStream] 剥掉块长度行与 CRLF；
 * 2. 有合法的 `Content-Length` → [BoundedInputStream] 严格截断到声明长度；
 * 3. 两者都没声明 → 直接透传原始流，由调用方读到 EOF 或触发上限。
 *
 * 包装流都不关闭底层 socket：生命周期归连接处理方。
 */
internal object EntityBody {

    fun open(headers: HttpHeaders, raw: InputStream): InputStream {
        val encoding = headers.of("transfer-encoding")?.lowercase()
        if (encoding != null && encoding.contains("chunked")) {
            return ChunkedInputStream(raw)
        }
        val declared = headers.of("content-length")?.trim()?.toLongOrNull()
        if (declared != null && declared >= 0) {
            return BoundedInputStream(raw, declared)
        }
        return raw
    }
}

/** 只允许读出 [limit] 个字节的前缀流；到界即 EOF，绝不多读一个字节 */
internal class BoundedInputStream(
    private val source: InputStream,
    private val limit: Long
) : FilterInputStream(source) {

    private var consumed = 0L

    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = source.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (consumed >= limit) return -1
        val allowed = minOf(len.toLong(), limit - consumed).toInt()
        if (allowed <= 0) return -1
        val n = source.read(b, off, allowed)
        if (n > 0) consumed += n
        return n
    }

    override fun available(): Int = minOf(source.available().toLong(), limit - consumed).toInt()

    override fun close() {
        // 底层 socket 由连接处理方关闭，包装流不代劳
    }
}

/**
 * chunked 解码流：按块长度行读取数据块，吃掉块尾 CRLF 与终止块后的 trailer。
 *
 * 不合法分帧一律抛 [IOException]——上传路径据此删半成品而不是把长度行写进书里。
 */
internal class ChunkedInputStream(private val source: InputStream) : InputStream() {

    private var remainingInChunk = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        if (remainingInChunk == 0L && !readNextChunkHeader()) return -1

        val toRead = minOf(len.toLong(), remainingInChunk).toInt()
        val n = source.read(b, off, toRead)
        if (n < 0) throw EOFException("chunked 传输体提前结束")
        remainingInChunk -= n
        if (remainingInChunk == 0L) consumeChunkTerminator()
        return n
    }

    private fun readNextChunkHeader(): Boolean {
        val line = readLine() ?: throw EOFException("缺少 chunk 长度行")
        val size = line.substringBefore(';').trim().toLongOrNull(16)
            ?: throw IOException("非法 chunk 长度行：$line")
        if (size == 0L) {
            finished = true
            skipTrailers()
            return false
        }
        remainingInChunk = size
        return true
    }

    private fun consumeChunkTerminator() {
        while (true) {
            val b = source.read()
            if (b < 0) throw EOFException("chunk 数据后缺少 CRLF")
            if (b == '\n'.code) return
            if (b != '\r'.code) throw IOException("chunk 数据后出现非法字节")
        }
    }

    private fun skipTrailers() {
        while (true) {
            val line = readLine() ?: return
            if (line.isEmpty()) return
        }
    }

    private fun readLine(): String? {
        val builder = StringBuilder()
        while (true) {
            val b = source.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(b.toChar())
        }
    }

    override fun close() {
        // 底层 socket 由连接处理方关闭
    }
}
