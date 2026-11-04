package com.watchreader

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 阅读会话的对外快照：只有「这一本书现在读到哪」需要的字段。
 *
 * 屏幕路由、书架、书签、搜索策略、传书、备份都不在这里——本类只回答阅读域的问题，
 * 所以它的状态可以整体替换给阅读页，而不需要带上整份 ReaderUiState。
 */
data class ReadingSessionState(
    val bookUri: Uri? = null,
    val title: String = "",
    val chapters: List<Chapter> = emptyList(),
    val totalChars: Int = 0,
    val chapterIndex: Int = 0,
    val content: ChapterContent? = null,
    val cleanTypography: Boolean = true
) {
    val isOpen: Boolean get() = bookUri != null && chapters.isNotEmpty()
}

/** [ReadingSession.open] 的结果：成功给出书目摘要，失败给出可展示的原因 */
sealed interface SessionOpenResult {

    data class Opened(val title: String, val chapterCount: Int, val totalChars: Int) : SessionOpenResult

    /** [reason] 已是可展示文案；[cause] 仅用于日志 */
    data class Failed(val reason: String, val cause: Throwable? = null) : SessionOpenResult
}

/** 书源工厂接缝：单测注入假书源即可覆盖打开、跳章、缓存与净化态切换的全部逻辑 */
fun interface BookSourceOpener {
    fun open(context: Context, uri: Uri): BookSource
}

/**
 * 阅读会话：一本书的正文、章节定位、缓存与预热全部归它所有。
 *
 * 收口前这些状态散在 ReaderViewModel 的四个哨兵字段（currentEncoding / convertedFormat /
 * convertedReadUri / currentReadingOffset）加两张缓存表里，任何一种组合错了都表现为「串书」
 * 或「进度错位」，且无一可从外部观察到；调用方想取一章正文，必须自己拼出「读取源 + 编码 +
 * 章节边界 + 排版态」四件事。现在调用方只有一个动作：[goToChapter]，正文由 [state] 交付。
 *
 * 接口刻意窄：打开 / 跳章 / 记位置 / 切排版态 / 关书。缓存驱逐、相邻预热、净化态双份缓存
 * 都是实现细节，不进接口——删掉本类，这些逻辑会原样分摊到 ReaderViewModel 与阅读页，
 * 复杂度不消失只扩散（deletion test）。
 *
 * 线程约定：本类所有 suspend 方法在调用方协程上下文里执行，内部只在 IO 线程做 I/O；
 * [updateOffset] 与 [currentOffset] 是任意线程可调的事件路径（@Volatile 字段，无锁）。
 */
class ReadingSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val opener: BookSourceOpener = BookSourceOpener { ctx, uri -> BookSource.open(ctx, uri) }
) {

    private val _state = MutableStateFlow(ReadingSessionState())
    val state: StateFlow<ReadingSessionState> = _state.asStateFlow()

    private var source: BookSource? = null

    /** 章节正文缓存：键为「章节索引左移一位 | 净化态」，两种排版并存互不驱逐 */
    private val contentCache = ConcurrentHashMap<Int, ChapterContent>()

    private var prefetchJob: Job? = null

    /**
     * 当前阅读位置（原书字符偏移域）。
     *
     * 事件路径每帧写、组合读，故为普通 volatile 字段而不是状态：写状态会让每次滚动都重组阅读页，
     * 这正是收口前阅读页逐帧整页重组的一个来源。
     */
    @Volatile
    private var offset: Int = 0

    val currentOffset: Int get() = offset

    val chapters: List<Chapter> get() = _state.value.chapters

    val totalChars: Int get() = _state.value.totalChars

    fun isOpen(): Boolean = _state.value.isOpen

    /**
     * 打开一本书并把会话定位到 [initialOffset] 所在章节。
     *
     * 失败一律通过 [SessionOpenResult.Failed] 返回，不抛给调用方：装载失败在阅读器里是
     * 常规路径（文件被删、权限失效、DRM），调用方要做的是回书架并展示原因，而不是崩掉。
     * 协程取消照常抛出，不被这里吞掉。
     */
    suspend fun open(uri: Uri, initialOffset: Int = 0): SessionOpenResult {
        close()
        return try {
            val opened = withContext(Dispatchers.IO) { opener.open(context, uri) }
            val safeOffset = initialOffset.coerceIn(0, opened.totalChars)
            val index = if (opened.chapters.isNotEmpty()) {
                findCurrentChapterIndex(opened.chapters, safeOffset).coerceIn(0, opened.chapters.lastIndex)
            } else {
                0
            }
            val content = withContext(Dispatchers.IO) { opened.readChapter(index, cleanTypographyOf()) }
                ?: return SessionOpenResult.Failed("章节正文读取失败")

            source = opened
            offset = safeOffset
            _state.value = ReadingSessionState(
                bookUri = uri,
                title = opened.title,
                chapters = opened.chapters,
                totalChars = opened.totalChars,
                chapterIndex = index,
                content = content,
                cleanTypography = cleanTypographyOf()
            )
            prefetchAround(index)
            SessionOpenResult.Opened(opened.title, opened.chapters.size, opened.totalChars)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            close()
            SessionOpenResult.Failed(e.localizedMessage ?: "文件不存在或无权限", e)
        }
    }

    /**
     * 跳章：[targetOffset] 为负表示落到章首。
     *
     * 正文读取失败时保持在当前章并返回 false——跳章失败不该把读者赶到空白页，
     * 调用方据此把原因透出为一次性提示。
     */
    suspend fun goToChapter(index: Int, targetOffset: Int = -1): Boolean {
        val current = _state.value
        val book = source ?: return false
        if (index !in book.chapters.indices) return false

        val content = contentCache[cacheKey(index, current.cleanTypography)]
            ?: withContext(Dispatchers.IO) { book.readChapter(index, current.cleanTypography) }
            ?: return false
        contentCache[cacheKey(index, current.cleanTypography)] = content

        val newOffset = if (targetOffset >= 0) targetOffset else content.startCharOffset
        offset = newOffset
        _state.value = current.copy(chapterIndex = index, content = content)
        prefetchAround(index)
        return true
    }

    /** 记录阅读位置：滚动与翻页的事件路径，只写字段，不触发状态更新 */
    fun updateOffset(newOffset: Int) {
        offset = newOffset
    }

    /**
     * 切换净化排版态并重排当前章。
     *
     * 两种排版各占一份缓存（缓存键末位），切回旧态不再重新排版；关闭净化时章节索引
     * 不变，只是正文按原文保留缩进与空行。
     */
    suspend fun setCleanTypography(clean: Boolean): Boolean {
        val current = _state.value
        if (!current.isOpen) {
            _state.value = current.copy(cleanTypography = clean)
            return true
        }
        val book = source ?: return false
        _state.value = current.copy(cleanTypography = clean)
        val content = contentCache[cacheKey(current.chapterIndex, clean)]
            ?: withContext(Dispatchers.IO) { book.readChapter(current.chapterIndex, clean) }
            ?: return false
        contentCache[cacheKey(current.chapterIndex, clean)] = content
        _state.value = _state.value.copy(content = content)
        return true
    }

    /**
     * 搜索用单章读取：固定原样态（正文 = 原文切片，坐标可直接换算），且绕过缓存——
     * 全书扫描不得把 16 章预热窗口挤掉。
     */
    suspend fun readChapterForSearch(index: Int): ChapterContent? {
        val book = source ?: return null
        if (index !in book.chapters.indices) return null
        return withContext(Dispatchers.IO) { book.readChapter(index, cleanTypography = false) }
    }

    /** 全书单趟字符流（搜索用）：null 表示该格式没有连续文本或打开失败 */
    fun openCharStream(): Reader? = source?.openCharStream()

    /** 各章右端偏移，见 [BookSource.chapterEndOffsets] */
    fun chapterEndOffsets(): IntArray = BookSource.chapterEndOffsets(_state.value.chapters, totalChars)

    /**
     * 预热相邻章节（N+1, N-1, N+2, N-2）并维持轻量缓存窗口（两种排版态各 8 章）。
     *
     * 预热是纯收益动作：失败静默（下次真正跳章会再读一次），不与用户可见的错误路径争抢。
     */
    private fun prefetchAround(centerIndex: Int) {
        val book = source ?: return
        if (book.chapters.isEmpty()) return
        prefetchJob?.cancel()
        val clean = _state.value.cleanTypography
        prefetchJob = scope.launch(Dispatchers.IO) {
            // 预热是纯收益动作：读失败不该把异常抛到会话作用域（会连带取消 viewModelScope），
            // 只静默跳过，下次真正跳章再读一次
            try {
                val targets =
                    intArrayOf(centerIndex + 1, centerIndex - 1, centerIndex + 2, centerIndex - 2)
                for (idx in targets) {
                    if (idx in book.chapters.indices && !contentCache.containsKey(cacheKey(idx, clean))) {
                        val loaded = book.readChapter(idx, clean) ?: continue
                        contentCache[cacheKey(idx, clean)] = loaded
                    }
                }
                if (contentCache.size > CACHE_WINDOW) {
                    contentCache.keys
                        .filter { abs((it shr 1) - centerIndex) > PREFETCH_RADIUS }
                        .forEach { contentCache.remove(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG_SESSION, "章节预热失败（已忽略）", e)
            }
        }
    }

    /** 关书：释放缓存、取消预热、清空状态。重复调用安全 */
    fun close() {
        prefetchJob?.cancel()
        prefetchJob = null
        contentCache.clear()
        source = null
        offset = 0
        _state.value = ReadingSessionState()
    }

    private fun cleanTypographyOf(): Boolean = _state.value.cleanTypography

    private fun cacheKey(chapterIndex: Int, clean: Boolean): Int =
        (chapterIndex shl 1) or if (clean) 1 else 0

    private companion object {
        const val TAG_SESSION = "ReadingSession"

        /** 缓存窗口上限：两种排版态各 8 章 */
        const val CACHE_WINDOW = 16

        /** 预热半径：中心章 ±3 以外的缓存条目允许被驱逐 */
        const val PREFETCH_RADIUS = 3
    }
}
