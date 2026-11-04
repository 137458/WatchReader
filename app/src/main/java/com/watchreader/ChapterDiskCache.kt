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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * 章节索引磁盘持久化缓存管理器（Version 3 协议）
 *
 * 协议升级特性 (Version 3)：
 * 1. 章节索引现含超长章节的封顶续节（见 `MAX_CHAPTER_RENDER_CHARS`）；Version 2 旧表不含续节，
 *    继续沿用会绕过切分，故升版使其整体失效重建。
 *
 * 协议升级特性 (Version 2)：
 * 1. 精确持久化 totalChars: Int，消除大文件二次冷启动时全量流式估算的 CPU 与 I/O 开销。
 * 2. 强类型向下兼容：当检测到 Version 1 缓存时，安全清理旧缓存并返回 null，由上层重建 Version 2 缓存。
 * 3. 同步原子落盘：唯一命名临时文件 + 原子替换（失败不删除旧缓存），杜绝断电或进程强杀导致的半写入。
 */
object ChapterDiskCache {

    private const val TAG = "ChapterDiskCache"
    private const val CACHE_DIR_NAME = "chapter_index"
    private const val MAGIC_HEADER = 0x57524349 // "WRCI"
    private const val FORMAT_VERSION = 3

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
     * 缓存加载结果：把 [load] 返回 null 时的三种含义（无缓存 / 坏缓存已丢弃 / 协议版本过期已丢弃）
     * 显式化，便于上层记日志与测试断言"坏档确实被丢弃"，避免把坏缓存误当"从未缓存"。
     */
    internal data class LoadOutcome(val data: ChapterCacheData?, val discardedCorrupt: Boolean)

    /**
     * 从 Context 关联的磁盘缓存加载章节索引与总字符数
     */
    fun load(context: Context, rawKey: String): ChapterCacheData? {
        return load(getCacheDir(context), rawKey)
    }

    /**
     * 从指定目录加载章节索引列表与总字符数
     *
     * 返回 null 可能是"无缓存"、"坏缓存已被删除"或"协议版本过期已被删除"三者之一，
     * 需要区分时用 [loadWithOutcome]。
     */
    fun load(cacheDir: File, rawKey: String): ChapterCacheData? =
        loadWithOutcome(cacheDir, rawKey).data

    /**
     * 同 [load]，但额外报告本次是否丢弃了不可用的缓存文件
     */
    internal fun loadWithOutcome(cacheDir: File, rawKey: String): LoadOutcome {
        if (rawKey.isEmpty()) return LoadOutcome(null, false)
        val cacheFile = File(cacheDir, "${hashKey(rawKey)}.idx")
        if (!cacheFile.exists()) return LoadOutcome(null, false)
        // 魔数与版本号共 8 字节都读不满：半写入产物，直接丢弃
        if (cacheFile.length() < 8) return LoadOutcome(null, discardCacheFile(cacheFile))

        var corrupt = false
        val data = try {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 16384)).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC_HEADER) {
                    corrupt = true
                    return@use null
                }
                // Version 1/2 与当前协议不兼容：整体作废重建（V2 旧表无封顶续节，V3 起含续节）
                if (dis.readInt() != FORMAT_VERSION) {
                    corrupt = true
                    return@use null
                }

                val totalChars = dis.readInt()
                val count = dis.readInt()
                if (count <= 0 || count > 50000) {
                    corrupt = true
                    return@use null
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
            corrupt = true
            null
        }
        // 删除必须发生在读流关闭之后：Windows 上仍被打开的文件 delete() 会失败，
        // 导致"已判坏但坏档残留"，下次启动重复解析同一坏文件
        return LoadOutcome(data, corrupt && discardCacheFile(cacheFile))
    }

    /** 删除不可用缓存文件；删除失败不改变"本次不返回数据"的结论 */
    private fun discardCacheFile(cacheFile: File): Boolean {
        return try {
            cacheFile.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 同步原子落盘保存章节索引列表与精确总字符数
     *
     * @return true=目标文件已是本次写入的内容；false=未落盘（旧缓存保持原样，仍可继续使用）
     */
    fun save(context: Context, rawKey: String, chapters: List<Chapter>, totalChars: Int = 0): Boolean {
        return save(getCacheDir(context), rawKey, chapters, totalChars)
    }

    /**
     * 指定目录同步原子落盘保存
     *
     * 先写唯一命名的临时文件，再整体替换目标文件：替换前旧缓存始终可用，
     * 替换失败也不删除旧文件——宁可继续沿用旧索引，也不留下半写入的索引。
     */
    fun save(cacheDir: File, rawKey: String, chapters: List<Chapter>, totalChars: Int = 0): Boolean {
        if (rawKey.isEmpty() || chapters.isEmpty()) return false
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val hash = hashKey(rawKey)
        val finalFile = File(cacheDir, "$hash.idx")
        // 临时文件名唯一：并发保存同一本书时互不踩写
        val tmpFile = try {
            File.createTempFile("${hash}_", ".tmp", cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create temp cache file for key: $rawKey", e)
            return false
        }

        return try {
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
            moveIntoPlace(tmpFile, finalFile)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to save chapter index cache for key: $rawKey", e)
            try { tmpFile.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * 原子替换目标文件：同目录 rename 是单步原子操作，替换期间不会出现"目标缺失"或"目标半写入"。
     *
     * [StandardCopyOption.ATOMIC_MOVE] 不被文件系统支持时回退普通替换移动；
     * 两条路径都不删除旧文件、都不忽略失败结果。
     */
    private fun moveIntoPlace(tmpFile: File, finalFile: File): Boolean {
        return try {
            Files.move(
                tmpFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            try {
                Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to move ${tmpFile.name} to ${finalFile.name}", e2)
                try { tmpFile.delete() } catch (_: Exception) {}
                false
            }
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
