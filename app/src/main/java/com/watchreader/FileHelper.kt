package com.watchreader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
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
    val lastReadTime: Long
) {
    val progressPercent: Int
        get() = if (totalChars > 0) ((charOffset.toFloat() / totalChars) * 100).toInt().coerceIn(0, 100) else 0
}

/**
 * 文件与持久化工具类
 */
private const val PREFS_NAME = "watch_reader"
private const val KEY_LAST_URI = "last_uri"
private const val KEY_LAST_CHAR_OFFSET = "last_char_offset"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_BOOK_SHELF = "book_shelf_json"
private const val KEY_DARK_MODE = "is_dark_mode"
private const val DEFAULT_FONT_SIZE = 14

fun getPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** 保存上次阅读位置并同步更新书架记录 */
fun saveReadingPosition(
    context: Context,
    uri: Uri,
    charOffset: Int,
    totalChars: Int = 0,
    chapterTitle: String = ""
) {
    val prefs = getPrefs(context)
    prefs.edit()
        .putString(KEY_LAST_URI, uri.toString())
        .putInt(KEY_LAST_CHAR_OFFSET, charOffset)
        .apply()

    updateBookInShelf(context, uri, charOffset, totalChars, chapterTitle)
}

/** 读取上次阅读位置 */
fun loadReadingPosition(context: Context): Pair<Uri, Int>? {
    val prefs = getPrefs(context)
    val uriStr = prefs.getString(KEY_LAST_URI, null) ?: return null
    val offset = prefs.getInt(KEY_LAST_CHAR_OFFSET, 0)
    return Uri.parse(uriStr) to offset
}

/** 清除当前正在阅读的记录（但不删书架历史） */
fun clearReadingPosition(context: Context) {
    getPrefs(context).edit().remove(KEY_LAST_URI).remove(KEY_LAST_CHAR_OFFSET).apply()
}

/** 保存字号设置 */
fun saveFontSize(context: Context, size: Int) {
    getPrefs(context).edit().putInt(KEY_FONT_SIZE, size).apply()
}

/** 读取字号设置 */
fun loadFontSize(context: Context): Int =
    getPrefs(context).getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)

/** 保存深色模式设置 */
fun saveDarkMode(context: Context, isDark: Boolean) {
    getPrefs(context).edit().putBoolean(KEY_DARK_MODE, isDark).apply()
}

/** 读取深色模式设置（默认夜间/深色或亮色） */
fun loadDarkMode(context: Context): Boolean =
    getPrefs(context).getBoolean(KEY_DARK_MODE, false)

// ═══════════════════════════════════════
//  书架管理功能
// ═══════════════════════════════════════

/** 读取书架列表（按最近阅读时间倒序） */
fun loadBookShelf(context: Context): List<BookItem> {
    val jsonStr = getPrefs(context).getString(KEY_BOOK_SHELF, null) ?: return emptyList()
    val list = mutableListOf<BookItem>()
    try {
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                BookItem(
                    uriString = obj.getString("uri"),
                    title = obj.optString("title", "未命名小说"),
                    charOffset = obj.optInt("offset", 0),
                    totalChars = obj.optInt("total", 0),
                    lastChapterTitle = obj.optString("chapter", ""),
                    lastReadTime = obj.optLong("time", 0L)
                )
            )
        }
    } catch (_: Exception) {}
    return list.sortedByDescending { it.lastReadTime }
}

/** 更新或添加书籍到书架 */
fun updateBookInShelf(
    context: Context,
    uri: Uri,
    charOffset: Int,
    totalChars: Int = 0,
    chapterTitle: String = ""
) {
    val uriStr = uri.toString()
    val currentList = loadBookShelf(context).toMutableList()
    val existingIdx = currentList.indexOfFirst { it.uriString == uriStr }
    val title = if (existingIdx >= 0) currentList[existingIdx].title else getFileName(context, uri)
    val total = if (totalChars > 0) totalChars else (if (existingIdx >= 0) currentList[existingIdx].totalChars else 0)
    val chapter = if (chapterTitle.isNotEmpty()) chapterTitle else (if (existingIdx >= 0) currentList[existingIdx].lastChapterTitle else "")

    val updatedItem = BookItem(
        uriString = uriStr,
        title = title,
        charOffset = charOffset,
        totalChars = total,
        lastChapterTitle = chapter,
        lastReadTime = System.currentTimeMillis()
    )

    if (existingIdx >= 0) {
        currentList[existingIdx] = updatedItem
    } else {
        currentList.add(0, updatedItem)
    }

    saveBookShelfList(context, currentList)
}

/** 从书架中删除一本书 */
fun removeBookFromShelf(context: Context, uriString: String) {
    val currentList = loadBookShelf(context).filter { it.uriString != uriString }
    saveBookShelfList(context, currentList)
    val lastUri = getPrefs(context).getString(KEY_LAST_URI, null)
    if (lastUri == uriString) {
        clearReadingPosition(context)
    }
}

private fun saveBookShelfList(context: Context, list: List<BookItem>) {
    val array = JSONArray()
    for (item in list) {
        val obj = JSONObject().apply {
            put("uri", item.uriString)
            put("title", item.title)
            put("offset", item.charOffset)
            put("total", item.totalChars)
            put("chapter", item.lastChapterTitle)
            put("time", item.lastReadTime)
        }
        array.put(obj)
    }
    getPrefs(context).edit().putString(KEY_BOOK_SHELF, array.toString()).apply()
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

/**
 * 高性能流式从 URI 读取全文（NIO FileChannel 内核直读 + 单流采样探测编码 + 0 扩容数组拷贝）
 */
fun readTextFromUri(context: Context, uri: Uri): String {
    val cr = context.contentResolver

    // 1. 优先尝试 ParcelFileDescriptor 内核通道极速直读（提速 3~5 倍）
    try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val fis = java.io.FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel

            // 读取前 8KB 探测编码
            val byteBuf = java.nio.ByteBuffer.allocate(8192)
            val readBytes = channel.read(byteBuf)
            if (readBytes <= 0) return ""

            byteBuf.flip()
            val sampleArray = ByteArray(readBytes)
            byteBuf.get(sampleArray)

            val encoding = detectEncoding(sampleArray, readBytes)
            val hasBom = readBytes >= 3 &&
                    sampleArray[0] == 0xEF.toByte() &&
                    sampleArray[1] == 0xBB.toByte() &&
                    sampleArray[2] == 0xBF.toByte()

            // 定位回头部（跳过 BOM）
            channel.position(if (hasBom && encoding == "UTF-8") 3L else 0L)

            val estimatedChars = if (size > 0) {
                (size / (if (encoding.startsWith("UTF-16")) 2 else 1)).toInt() + 1024
            } else {
                readBytes * 4
            }

            val initialCapacity = if (estimatedChars in 1..(64 * 1024 * 1024)) estimatedChars else 1024 * 1024
            val reader = BufferedReader(InputStreamReader(fis, Charset.forName(encoding)), 131072) // 128KB 极速块
            val sb = StringBuilder(initialCapacity)
            val charBuf = CharArray(65536)
            var readChars: Int
            var prevWasCr = false

            while (reader.read(charBuf).also { readChars = it } != -1) {
                for (idx in 0 until readChars) {
                    val c = charBuf[idx]
                    if (c == '\r') {
                        sb.append('\n')
                        prevWasCr = true
                    } else if (c == '\n') {
                        if (!prevWasCr) {
                            sb.append('\n')
                        }
                        prevWasCr = false
                    } else {
                        sb.append(c)
                        prevWasCr = false
                    }
                }
            }
            reader.close()
            return sb.toString()
        }
    } catch (_: Exception) {}

    // 2. 回退通道：基于 BufferedInputStream 的 mark/reset 单流读取
    return try {
        val rawStream = cr.openInputStream(uri) ?: return ""
        val bufferedStream = BufferedInputStream(rawStream, 65536)

        bufferedStream.mark(8192)
        val sampleBuffer = ByteArray(8192)
        val sampleLen = bufferedStream.read(sampleBuffer)
        if (sampleLen <= 0) {
            bufferedStream.close()
            return ""
        }

        val encoding = detectEncoding(sampleBuffer, sampleLen)
        val hasBom = sampleLen >= 3 &&
                sampleBuffer[0] == 0xEF.toByte() &&
                sampleBuffer[1] == 0xBB.toByte() &&
                sampleBuffer[2] == 0xBF.toByte()

        bufferedStream.reset()
        if (hasBom && encoding == "UTF-8") {
            bufferedStream.skip(3)
        }

        val fileSize = getFileSize(context, uri)
        val estimatedChars = if (fileSize > 0) {
            (fileSize / (if (encoding.startsWith("UTF-16")) 2 else 1)).toInt() + 1024
        } else {
            sampleLen * 4
        }

        val initialCapacity = if (estimatedChars in 1..(64 * 1024 * 1024)) estimatedChars else 1024 * 1024
        val reader = BufferedReader(InputStreamReader(bufferedStream, Charset.forName(encoding)), 65536)
        val sb = StringBuilder(initialCapacity)
        val charBuf = CharArray(65536)
        var readChars: Int
        var prevWasCr = false

        while (reader.read(charBuf).also { readChars = it } != -1) {
            for (idx in 0 until readChars) {
                val c = charBuf[idx]
                if (c == '\r') {
                    sb.append('\n')
                    prevWasCr = true
                } else if (c == '\n') {
                    if (!prevWasCr) {
                        sb.append('\n')
                    }
                    prevWasCr = false
                } else {
                    sb.append(c)
                    prevWasCr = false
                }
            }
        }
        reader.close()
        sb.toString()
    } catch (_: Exception) {
        ""
    }
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
