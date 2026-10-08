package com.watchreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

/**
 * 书架书籍条目数据模型（@Immutable 确保 Compose 列表项稳定重用）
 */
@Immutable
data class BookItem(
    val uriString: String,
    val title: String,
    val charOffset: Int,
    val totalChars: Int,
    val lastChapterTitle: String,
    val lastReadTime: Long,
    val isPinned: Boolean = false,
    // 进度贴近全书结尾后置位，一次性置位不回退（阅读统计「读完本数」数据源）
    val finished: Boolean = false
) {
    val progressPercent: Int
        get() = if (totalChars > 0) ((charOffset.toFloat() / totalChars) * 100).toInt().coerceIn(0, 100) else 0
}



// ═══════════════════════════════════════
//  文件与高性能流式编码检测
// ═══════════════════════════════════════

/** 快速检测字节数组编码（支持 BOM、UTF-8 状态机校验与 GB18030 回退） */
fun detectEncoding(bytes: ByteArray, length: Int): String {
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

/** 兼容旧接口重载 */
fun detectEncoding(bytes: ByteArray): String = detectEncoding(bytes, bytes.size)

/** 获取文件近似大小（用于精准预分配内存容量，消除 StringBuilder 扩容拷贝） */
fun getFileSize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size > 0) return size
        }
    } catch (_: Exception) {}
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                val size = cursor.getLong(sizeIndex)
                if (size > 0) return size
            }
        }
    } catch (_: Exception) {}
    return 0L
}

/** 从 URI 获取文件名 */
fun getFileName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrEmpty()) return name
            }
        }
    } catch (_: Exception) {}
    val lastSeg = uri.lastPathSegment
    return if (!lastSeg.isNullOrEmpty()) lastSeg.substringAfterLast('/') else "本地小说"
}

/**
 * 极速探测文件的字符编码（仅采样头部 8KB，耗时 < 1ms）
 */
fun detectFileEncoding(context: Context, uri: Uri): String {
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val fis = java.io.FileInputStream(pfd.fileDescriptor)
            val sample = ByteArray(8192)
            val readBytes = fis.read(sample)
            if (readBytes > 0) {
                return detectEncoding(sample, readBytes)
            }
        }
    } catch (_: Exception) {}
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val sample = ByteArray(8192)
            val readBytes = stream.read(sample)
            if (readBytes > 0) {
                return detectEncoding(sample, readBytes)
            }
        }
    } catch (_: Exception) {}
    return "UTF-8"
}

/**
 * 高性能按需流式读取单个章节的原始文本（仅读取当前章节对应的少量字符，内存占用 < 50KB）
 */
fun readChapterChunkFromUri(
    context: Context,
    uri: Uri,
    encoding: String,
    startCharOffset: Int,
    endCharOffset: Int
): String {
    if (endCharOffset <= startCharOffset || startCharOffset < 0) return ""
    val targetLen = endCharOffset - startCharOffset
    val safeEncoding = if (encoding.isNotEmpty()) encoding else "UTF-8"

    val cr = context.contentResolver
    try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            val fis = java.io.FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel

            // 探测头部是否带 BOM
            val byteBuf = java.nio.ByteBuffer.allocate(4)
            val readBytes = channel.read(byteBuf)
            val hasBom = readBytes >= 3 &&
                    byteBuf.get(0) == 0xEF.toByte() &&
                    byteBuf.get(1) == 0xBB.toByte() &&
                    byteBuf.get(2) == 0xBF.toByte()

            channel.position(if (hasBom && safeEncoding == "UTF-8") 3L else 0L)

            val reader = BufferedReader(InputStreamReader(fis, Charset.forName(safeEncoding)), 32768)
            var toSkip = startCharOffset.toLong()
            while (toSkip > 0) {
                val skipped = reader.skip(toSkip)
                if (skipped <= 0) break
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
            reader.close()
            return sb.toString()
        }
    } catch (_: Exception) {}

    // 回退流式通道
    return try {
        cr.openInputStream(uri)?.use { rawStream ->
            val bufferedStream = BufferedInputStream(rawStream, 32768)
            val reader = BufferedReader(InputStreamReader(bufferedStream, Charset.forName(safeEncoding)), 32768)
            var toSkip = startCharOffset.toLong()
            while (toSkip > 0) {
                val skipped = reader.skip(toSkip)
                if (skipped <= 0) break
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
            sb.toString()
        } ?: ""
    } catch (_: Exception) {
        ""
    }
}

/**
 * 全书单趟顺序字符流（BOM 处理与 [readChapterChunkFromUri] 完全一致）：
 * 供书内全文搜索等需要线性遍历全书的场景。逐章 skip 定位是"解码后丢弃"，
 * 全书代价为字符量 × 章节数；单趟顺序解码把总代价压回字符量本身。
 * 调用方负责 close（同时释放底层文件描述符）。
 */
fun openBookCharStream(context: Context, uri: Uri, encoding: String): Reader? {
    val safeEncoding = if (encoding.isNotEmpty()) encoding else "UTF-8"
    val cr = context.contentResolver

    fun buildReader(stream: java.io.InputStream): Reader {
        val buffered = BufferedInputStream(stream, 32768)
        buffered.mark(4)
        val head = ByteArray(4)
        val probed = buffered.read(head, 0, 4)
        val hasUtf8Bom = probed >= 3 &&
                head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        buffered.reset()
        if (hasUtf8Bom && safeEncoding == "UTF-8") {
            var skipped = 0
            while (skipped < 3) {
                if (buffered.read() < 0) break
                skipped++
            }
        }
        return BufferedReader(InputStreamReader(buffered, Charset.forName(safeEncoding)), 32768)
    }

    // pfd 不能在返回前关闭：Reader 生命周期长于本函数，关闭链一并接管 pfd
    fun closeWithReader(reader: Reader, pfd: android.os.ParcelFileDescriptor?): Reader =
        object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int =
                reader.read(cbuf, off, len)

            override fun ready(): Boolean = reader.ready()

            override fun close() {
                try {
                    reader.close()
                } finally {
                    pfd?.close()
                }
            }
        }

    return try {
        val pfd = cr.openFileDescriptor(uri, "r")
        if (pfd != null) {
            try {
                closeWithReader(buildReader(java.io.FileInputStream(pfd.fileDescriptor)), pfd)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        } else null
    } catch (_: Exception) {
        null
    } ?: try {
        cr.openInputStream(uri)?.let { buildReader(it) }
    } catch (_: Exception) {
        null
    }
}
