package com.watchreader

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

/**
 * 纯 java.io 的编码探测与按字符偏移取切片实现（无 Android 类型，可纯 JVM 单测）。
 *
 * [detectEncoding]、[readChapterChunkFromUri]、[openBookCharStream] 都经本 object 落地，
 * 于是最容易出错的两处分支（BOM 与偏移域、GB18030 回退）有了直接的单测入口。
 */
internal object TextStreams {

    /** 快速检测字节数组编码（支持 BOM、UTF-8 状态机校验与 GB18030 回退） */
    fun detect(bytes: ByteArray, length: Int): String {
        if (length >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return "UTF-8"
        }
        if (length >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        }

        // 严谨校验 UTF-8 编码结构
        var isUtf8 = true
        var i = 0
        var hasMultiByte = false

        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b <= 0x7F) {
                i++
            } else if (b in 0xC2..0xDF) {
                if (i + 1 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b2 !in 0x80..0xBF) {
                    isUtf8 = false
                    break
                }
                hasMultiByte = true
                i += 2
            } else if (b in 0xE0..0xEF) {
                if (i + 2 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF) {
                    isUtf8 = false
                    break
                }
                hasMultiByte = true
                i += 3
            } else if (b in 0xF0..0xF4) {
                if (i + 3 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                val b4 = bytes[i + 3].toInt() and 0xFF
                if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF || b4 !in 0x80..0xBF) {
                    isUtf8 = false
                    break
                }
                hasMultiByte = true
                i += 4
            } else {
                isUtf8 = false
                break
            }
        }

        return if (isUtf8 && (hasMultiByte || length < 100)) "UTF-8" else "GB18030"
    }

    /**
     * 从 [stream] 起点跳过 BOM 与 [startCharOffset] 个字符，再读 [targetLen] 个字符。
     *
     * BOM 不是正文字符：若把 3 字节 BOM 解码成 U+FEFF 计入偏移，全书字符偏移会整体错位一位。
     * skip 无法推进（偏移超过文件长度、流不可定位）即返回 null，由调用方判定为读失败。
     */
    internal fun readChunk(
        stream: InputStream,
        encoding: String,
        startCharOffset: Int,
        targetLen: Int
    ): String? {
        val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 32768)
        if (encoding.equals("UTF-8", ignoreCase = true)) stripUtf8Bom(buffered)

        val reader = BufferedReader(InputStreamReader(buffered, Charset.forName(encoding)), 32768)
        var toSkip = startCharOffset.toLong()
        while (toSkip > 0) {
            val skipped = reader.skip(toSkip)
            if (skipped <= 0) return null
            toSkip -= skipped
        }

        val sb = StringBuilder(targetLen + 64)
        val charBuf = CharArray(minOf(8192, targetLen))
        var remaining = targetLen
        while (remaining > 0) {
            val toRead = minOf(charBuf.size, remaining)
            val readChars = reader.read(charBuf, 0, toRead)
            if (readChars == -1) break
            sb.append(charBuf, 0, readChars)
            remaining -= readChars
        }
        return sb.toString()
    }

    /**
     * [readChunk] 专用 BOM 处理：带 BOM 时保持已消费的 3 字节位置，不带时回退到起点。
     * （与 probeUtf8Bom + skipBytes 的组合不同：这里只在无 BOM 时 reset。）
     */
    private fun stripUtf8Bom(stream: BufferedInputStream) {
        stream.mark(4)
        val head = ByteArray(3)
        var filled = 0
        while (filled < 3) {
            val n = stream.read(head, filled, 3 - filled)
            if (n <= 0) break
            filled += n
        }
        val hasBom = filled == 3 &&
                head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (!hasBom) stream.reset()
    }

    /** 探测流是否以 UTF-8 BOM 开头；探针读完立即 reset，不消费流 */
    private fun probeUtf8Bom(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val head = ByteArray(4)
        val probed = stream.read(head, 0, 4)
        val hasUtf8Bom = probed >= 3 &&
                head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        stream.reset()
        return hasUtf8Bom
    }

    /** 逐字节丢弃 count 个（BOM 只有 3 字节，够用且不依赖流的可定位性） */
    private fun skipBytes(stream: InputStream, count: Int) {
        var skipped = 0
        while (skipped < count) {
            if (stream.read() < 0) break
            skipped++
        }
    }

    /**
     * 全书单趟顺序读取的 Reader 构造：先探测 BOM，需要时跳掉 3 字节，
     * 与 [readChunk] 的 BOM 口径一致——两条读取路径必须落在同一个字符偏移域。
     */
    internal fun buildReader(stream: InputStream, encoding: String): Reader {
        val buffered = BufferedInputStream(stream, 32768)
        val hasUtf8Bom = probeUtf8Bom(buffered)
        if (hasUtf8Bom && encoding == "UTF-8") skipBytes(buffered, 3)
        return BufferedReader(InputStreamReader(buffered, Charset.forName(encoding)), 32768)
    }

    /** 把 Reader 与底层资源（pfd 之类）绑成一条关闭链：Reader 生命周期长于打开它的函数 */
    internal fun closeWithReader(reader: Reader, resource: Closeable?): Reader = object : Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int = reader.read(cbuf, off, len)

        override fun ready(): Boolean = reader.ready()

        override fun close() {
            try {
                reader.close()
            } finally {
                resource?.close()
            }
        }
    }
}
