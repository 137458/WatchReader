package com.watchreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.FileInputStream
import java.io.Reader

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

/** 快速检测字节数组编码（支持 BOM、UTF-8 状态机校验与 GB18030 回退）；状态机在 [TextStreams.detect] */
fun detectEncoding(bytes: ByteArray, length: Int): String = TextStreams.detect(bytes, length)

/** 兼容旧接口重载 */
fun detectEncoding(bytes: ByteArray): String = TextStreams.detect(bytes, bytes.size)

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
 *
 * @return 章节原文切片；null 表示读取失败（无法打开流、编码不被支持、字符偏移超过文件长度
 * 使 skip 无法推进）。失败时绝不退化为"从当前位置开始读"——错位正文会被当成本章内容排版
 * 并写入阅读进度。空区间（end <= start）返回空串：空章与读失败是两件事。
 */
fun readChapterChunkFromUri(
    context: Context,
    uri: Uri,
    encoding: String,
    startCharOffset: Int,
    endCharOffset: Int
): String? {
    if (endCharOffset <= startCharOffset || startCharOffset < 0) return ""
    val targetLen = endCharOffset - startCharOffset
    val safeEncoding = if (encoding.isNotEmpty()) encoding else "UTF-8"

    val cr = context.contentResolver

    // 主通道：直接用文件描述符定位（无 SAF 代理层，skip 成本最低）
    val fromDescriptor = try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                TextStreams.readChunk(fis, safeEncoding, startCharOffset, targetLen)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG_FILE_HELPER, "主通道读取章节失败: $uri", e)
        null
    }
    if (fromDescriptor != null) return fromDescriptor

    // 回退通道：部分 SAF 提供方不支持 openFileDescriptor
    return try {
        cr.openInputStream(uri)?.use { rawStream ->
            TextStreams.readChunk(rawStream, safeEncoding, startCharOffset, targetLen)
        }
    } catch (e: Exception) {
        Log.w(TAG_FILE_HELPER, "回退通道读取章节失败: $uri", e)
        null
    }
}

private const val TAG_FILE_HELPER = "FileHelper"

/**
 * 全书单趟顺序字符流（BOM 处理与 [readChapterChunkFromUri] 完全一致）：
 * 供书内全文搜索等需要线性遍历全书的场景。逐章 skip 定位是"解码后丢弃"，
 * 全书代价为字符量 × 章节数；单趟顺序解码把总代价压回字符量本身。
 * 调用方负责 close（同时释放底层文件描述符）。
 */
fun openBookCharStream(context: Context, uri: Uri, encoding: String): Reader? {
    val safeEncoding = if (encoding.isNotEmpty()) encoding else "UTF-8"
    val cr = context.contentResolver

    // pfd 不能在返回前关闭：Reader 生命周期长于本函数，关闭链一并接管 pfd
    return try {
        val pfd = cr.openFileDescriptor(uri, "r")
        if (pfd != null) {
            try {
                TextStreams.closeWithReader(
                    TextStreams.buildReader(FileInputStream(pfd.fileDescriptor), safeEncoding),
                    pfd
                )
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        } else null
    } catch (_: Exception) {
        null
    } ?: try {
        cr.openInputStream(uri)?.let { TextStreams.buildReader(it, safeEncoding) }
    } catch (_: Exception) {
        null
    }
}
