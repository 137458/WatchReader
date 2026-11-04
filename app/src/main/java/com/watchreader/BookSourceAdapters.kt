package com.watchreader

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.Reader

/**
 * [BookSource] 的实现侧：TXT / 转换缓存 / EPUB 三种读取适配器，外加转换缓存可用性判据。
 *
 * 与门面分文件只为让 [BookSource] 读起来是「一个 open + 一个接口」；这里全部是 internal 类，
 * 读取源（原文件 / 转换缓存 / zip 条目）这个接缝仍封在 [BookSource] 之后，调用方看不到。
 */
/** 转换缓存的可用判据：存在且非空。空文件是上次转换中断的残骸，必须重转 */
internal fun isUsableCacheFile(file: File): Boolean = file.exists() && file.length() > 0

/**
 * TXT 与转换类格式共用的正文适配器：单章 = 原文切片 + 排版，编码探测与 BOM 处理
 * 由 [readChapterChunkFromUri] 承担，本类只负责把偏移域对齐。
 */
internal abstract class TextBookSource(
    private val context: Context,
    private val encoding: String,
    override val title: String,
    override val chapters: List<Chapter>,
    override val totalChars: Int
) : BookSource {

    /**
     * 当前可读文本 URI。TXT 恒为原文件；转换缓存的 URI 可能被系统清理，
     * 实现方负责按需重建（返回 null 表示重建失败，本次读取按失败上报）。
     */
    protected abstract fun resolveReadUri(): Uri?

    final override fun readChapter(index: Int, cleanTypography: Boolean): ChapterContent? {
        val bounds = BookSource.chapterBounds(chapters, index, totalChars) ?: return emptyChapterContent()
        val rawChunk = readRawChunk(bounds.start, bounds.end) ?: return null
        return formatChapterRawText(rawChunk, chapters, index, bounds.start, bounds.end, cleanTypography)
    }

    final override fun readRawChunk(startCharOffset: Int, endCharOffset: Int): String? {
        val readUri = resolveReadUri() ?: return null
        return readChapterChunkFromUri(context, readUri, encoding, startCharOffset, endCharOffset)
    }

    final override fun openCharStream(): Reader? {
        val readUri = resolveReadUri() ?: return null
        return openBookCharStream(context, readUri, encoding)
    }
}

/** 纯文本：直接读原文件，编码在打开时探测一次后固定 */
internal class TxtBookSource(
    context: Context,
    private val uri: Uri,
    encoding: String,
    title: String,
    chapters: List<Chapter>,
    totalChars: Int
) : TextBookSource(context, encoding, title, chapters, totalChars) {

    override fun resolveReadUri(): Uri = uri
}

/**
 * MOBI / FB2 / HTML：正文读取源是转换产物 UTF-8 文本缓存，而不是原文件。
 *
 * 「是否需要转换」这个哨兵状态只存在于本类内部：调用方拿到的就是一本书，
 * 不需要知道它背后是原文件还是缓存文件。缓存被系统清理时按需重转——转换是确定性的，
 * 重转后字符偏移域不变，已持久化的阅读进度与书签继续有效。
 */
internal class ConvertedBookSource(
    private val context: Context,
    private val originalUri: Uri,
    private val format: BookFormat,
    cacheFile: File,
    title: String,
    chapters: List<Chapter>,
    totalChars: Int
) : TextBookSource(context, "UTF-8", title, chapters, totalChars) {

    @Volatile
    private var readUri: Uri? = Uri.fromFile(cacheFile)

    // 预取与搜索可能同时发现缓存失效：只允许一个线程重转，其余等待后复用结果
    private val convertLock = Any()

    override fun resolveReadUri(): Uri? {
        readUri?.let { if (isUsable(it)) return it }
        synchronized(convertLock) {
            readUri?.let { if (isUsable(it)) return it }
            val rebuilt = try {
                Uri.fromFile(BookTextConverter.convertToCacheFile(context, originalUri, format))
            } catch (e: Exception) {
                // 重转失败不退回原文件：把 MOBI 压缩字节当正文读会渲染出乱码并污染进度，
                // 让上层按「读取失败」呈现可重试错误
                Log.w(TAG_BOOK_SOURCE, "转换缓存重建失败: $originalUri ($format)", e)
                return null
            }
            readUri = rebuilt
            return rebuilt
        }
    }

    private fun isUsable(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return isUsableCacheFile(File(path))
    }
}

/** EPUB：章节正文是 zip 条目内的 (X)HTML，读取与条目定位都在 EpubParser 内部 */
internal class EpubBookSource(
    private val context: Context,
    private val uri: Uri,
    private val metadata: EpubMetadata
) : BookSource {

    override val title: String = metadata.title
    override val chapters: List<Chapter> = metadata.chapters
    override val totalChars: Int = metadata.totalChars

    override fun readChapter(index: Int, cleanTypography: Boolean): ChapterContent? {
        if (index !in chapters.indices) return emptyChapterContent()
        return EpubParser.readChapterContent(context, uri, index, chapters, cleanTypography)
    }

    /** EPUB 正文按 zip 条目分别压缩存储，不存在可供切片或单趟扫描的连续字符流 */
    override fun readRawChunk(startCharOffset: Int, endCharOffset: Int): String? = null
}

private const val TAG_BOOK_SOURCE = "BookSource"

