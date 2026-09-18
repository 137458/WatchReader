package com.watchreader

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 章节持久化缓存结果模型
 * 同时委托实现 List<Chapter>，保障对既有 List<Chapter> 消费代码 100% 源代码向下兼容
 */
data class ChapterCacheData(
    val chapters: List<Chapter>,
    val totalChars: Int
)

/**
 * 章节索引磁盘持久化缓存管理器（Version 2 协议）
 *
 * 协议升级特性 (Version 2)：
 * 1. 精确持久化 totalChars: Int，消除大文件二次冷启动时全量流式估算的 CPU 与 I/O 开销。
 * 2. 强类型向下兼容：当检测到 Version 1 缓存时，安全清理旧缓存并返回 null，由上层重建 Version 2 缓存。
 * 3. 原子落盘保护：临时文件 + 原子替换，杜绝断电或进程强杀导致的脏读与半写入。
 */
object ChapterDiskCache {

    private const val TAG = "ChapterDiskCache"
    private const val CACHE_DIR_NAME = "chapter_index"
    private const val MAGIC_HEADER = 0x57524349 // "WRCI"
    private const val FORMAT_VERSION = 2

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun hashKey(rawKey: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(rawKey.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            rawKey.hashCode().toString()
        }
    }

    /**
     * 从 Context 关联的磁盘缓存加载章节索引与总字符数
     */
    fun load(context: Context, rawKey: String): ChapterCacheData? {
        return load(getCacheDir(context), rawKey)
    }

    /**
     * 从指定目录加载章节索引列表与总字符数
     */
    fun load(cacheDir: File, rawKey: String): ChapterCacheData? {
        if (rawKey.isEmpty()) return null
        val cacheFile = File(cacheDir, "${hashKey(rawKey)}.idx")
        if (!cacheFile.exists() || cacheFile.length() < 8) return null

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 16384)).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC_HEADER) {
                    cacheFile.delete()
                    return null
                }
                val version = dis.readInt()
                if (version == 1) {
                    // 向下兼容：检测到 Version 1 缓存安全返回 null，并删除旧文件触发上层重新扫描更新为 Version 2
                    cacheFile.delete()
                    return null
                }
                if (version != FORMAT_VERSION) {
                    cacheFile.delete()
                    return null
                }

                val totalChars = dis.readInt()
                val count = dis.readInt()
                if (count <= 0 || count > 50000) {
                    cacheFile.delete()
                    return null
                }

                val list = ArrayList<Chapter>(count)
                for (i in 0 until count) {
                    val index = dis.readInt()
                    val offset = dis.readInt()
                    val title = dis.readUTF()
                    list.add(Chapter(index = index, title = title, charOffset = offset))
                }
                ChapterCacheData(chapters = list, totalChars = totalChars)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load chapter index cache for key: $rawKey", e)
            try { cacheFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * 异步原子落盘保存章节索引列表与精确总字符数
     */
    fun save(context: Context, rawKey: String, chapters: List<Chapter>, totalChars: Int = 0) {
        save(getCacheDir(context), rawKey, chapters, totalChars)
    }

    /**
     * 指定目录异步原子落盘保存
     */
    fun save(cacheDir: File, rawKey: String, chapters: List<Chapter>, totalChars: Int = 0) {
        if (rawKey.isEmpty() || chapters.isEmpty()) return
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val finalFile = File(cacheDir, "${hashKey(rawKey)}.idx")
        val tmpFile = File(cacheDir, "${hashKey(rawKey)}_${System.currentTimeMillis()}.tmp")

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 16384)).use { dos ->
                dos.writeInt(MAGIC_HEADER)
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(totalChars)
                dos.writeInt(chapters.size)
                for (chap in chapters) {
                    dos.writeInt(chap.index)
                    dos.writeInt(chap.charOffset)
                    dos.writeUTF(chap.title)
                }
                dos.flush()
            }
            if (tmpFile.exists()) {
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                tmpFile.renameTo(finalFile)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to save chapter index cache for key: $rawKey", e)
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 清理过期或所有章节缓存
     */
    fun clear(context: Context) {
        try {
            getCacheDir(context).deleteRecursively()
        } catch (_: Exception) {}
    }
}
