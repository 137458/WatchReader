package com.watchreader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章节加载域对磁盘的最小依赖面：书架、书签、阅读位置、上次页面。
 *
 * 这四件事全部是越过进程的持久化，而不是章节加载逻辑本身。把它们挡在接口后面，
 * [ChapterNavigator] 的装载编排才不依赖 DataStore、Context 与文件系统；
 * 删掉本接口，装载失败分支里的「清位置 + 重读书架」只能挤回 ViewModel 并用真实存储测试。
 */
internal interface ReaderProgressStore {

    suspend fun loadShelf(): List<BookItem>

    suspend fun updateInShelf(
        uri: Uri,
        charOffset: Int,
        totalChars: Int,
        chapterTitle: String
    ): List<BookItem>

    suspend fun loadBookmarks(uriString: String): List<Bookmark>

    suspend fun savePosition(uri: Uri, charOffset: Int, totalChars: Int, chapterTitle: String)

    suspend fun clearPosition()

    suspend fun saveLastScreenHome()
}

/** [ReaderProgressStore] 的生产实现：一次转发到 [DataStoreManager]，不附加任何语义 */
internal class DataStoreReaderProgressStore(private val context: Context) : ReaderProgressStore {

    override suspend fun loadShelf(): List<BookItem> = DataStoreManager.loadBookShelf(context)

    override suspend fun updateInShelf(
        uri: Uri,
        charOffset: Int,
        totalChars: Int,
        chapterTitle: String
    ): List<BookItem> = DataStoreManager.updateBookInShelf(context, uri, charOffset, totalChars, chapterTitle)

    override suspend fun loadBookmarks(uriString: String): List<Bookmark> =
        DataStoreManager.loadBookmarks(context, uriString)

    override suspend fun savePosition(uri: Uri, charOffset: Int, totalChars: Int, chapterTitle: String) =
        DataStoreManager.saveReadingPosition(context, uri, charOffset, totalChars, chapterTitle)

    override suspend fun clearPosition() = DataStoreManager.clearReadingPosition(context)

    override suspend fun saveLastScreenHome() = DataStoreManager.saveLastScreen(context, "home")
}

/**
 * 章节加载域：装载一本书、跳章、章节前后翻、把位置写回磁盘，以及驱动它们的两个 Job。
 *
 * 收口前这些逻辑散在 ReaderViewModel 的 loadFile / syncReadingState / goToChapter /
 * goToNextChapter / goToPrevChapter / updateCharOffset / flushReadingPosition 七处，
 * 真实正文来源（[ReadingSession]）与屏幕状态（ReaderUiState）由 ViewModel 同时持有时，
 * 「会话快照 → 屏幕字段」的映射关系只能靠读七段代码确认。现在只有 [syncReadingState] 一个出口，
 * 装载失败/成功两条路径的屏幕落点都在这一个类里（deletion test：删掉它，这七段会原样摊回
 * ViewModel，且 loadJob / savePositionJob 的取消语义会重新与「谁持有 Job」分家）。
 *
 * 线程约定：所有对外方法立即返回；装载与落盘分别在 [scope] 上的独立 Job 内完成，
 * 磁盘写入走 [io]（生产即 IO）。[loadJob] 取消即放弃上一次装载，[savePositionJob] 取消即合并
 * 同一次阅读事件里的重复写盘。
 */
internal class ChapterNavigator(
    private val uiState: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val session: ReadingSession,
    private val store: ReaderProgressStore,
    private val resetSearch: () -> Unit,
    private val onUserActive: () -> Unit,
    private val stopAutoScroll: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    // 装载与位置落盘各一个 Job：同一个 Job 才谈得上「后一次取消前一次」
    private var loadJob: Job? = null
    private var savePositionJob: Job? = null

    /** 当前阅读位置（原书字符偏移域） */
    fun currentReadingOffset(): Int = session.currentOffset

    /** 重入即取消上一次装载：两本书的流程交错会互相覆盖会话状态 */
    fun cancelPendingLoad() {
        loadJob?.cancel()
    }

    /** 退出前取消未完成的位置写入（磁盘内容保持上一次成功写入的值） */
    fun cancelPendingSave() {
        savePositionJob?.cancel()
    }

    /**
     * 异步加载书籍文件：格式分流、章节索引、首章正文与相邻预热全部交给 [ReadingSession]
     */
    fun loadFile(uri: Uri, initialOffset: Int = 0) {
        loadJob?.cancel()
        loadJob = scope.launch {
            resetSearch()
            uiState.update {
                it.copy(
                    isLoading = true,
                    screen = Screen.Loading,
                    currentUri = uri,
                    errorMessage = null
                )
            }

            // 净化排版态是用户设置，装载前先交给会话，首章直接按该态排版
            session.setCleanTypography(uiState.value.cleanTypography)

            when (val opened = session.open(uri, initialOffset)) {
                is SessionOpenResult.Failed -> {
                    // 装载失败是常规路径（文件被删、权限失效、DRM）：回书架并说明原因
                    withContext(io) { store.clearPosition() }
                    val shelf = withContext(io) { store.loadShelf() }
                    uiState.update {
                        it.copy(
                            currentUri = null,
                            chapters = emptyList(),
                            currentChapterContent = null,
                            isLoading = false,
                            bookshelf = shelf,
                            errorMessage = "读取小说失败: ${opened.reason}",
                            screen = Screen.Home
                        )
                    }
                }

                is SessionOpenResult.Opened -> {
                    syncReadingState()
                    val chapterTitle = session.state.value.content?.title.orEmpty()
                        .ifEmpty { opened.title }
                    val updatedShelf = withContext(io) {
                        store.updateInShelf(uri, session.currentOffset, opened.totalChars, chapterTitle)
                    }
                    val loadedBookmarks = withContext(io) { store.loadBookmarks(uri.toString()) }
                    uiState.update {
                        it.copy(
                            fileName = opened.title,
                            bookmarks = loadedBookmarks,
                            isLoading = false,
                            bookshelf = updatedShelf,
                            screen = Screen.Reader(session.currentOffset, session.state.value.chapterIndex)
                        )
                    }
                }
            }
        }
    }

    /**
     * 会话快照 → 屏幕状态：阅读域字段只有这一个出口。
     *
     * 屏幕状态保持扁平是因为多个页面共用同一份 ReaderUiState（书架、菜单、搜索都读它），
     * 但「这份数据从哪来」收敛在这里，阅读页不再需要知道章节正文是怎么装载的。
     */
    fun syncReadingState() {
        val snapshot = session.state.value
        uiState.update {
            it.copy(
                chapters = snapshot.chapters,
                currentChapterIndex = snapshot.chapterIndex,
                currentChapterContent = snapshot.content,
                fullTextLength = snapshot.totalChars,
                cleanTypography = snapshot.cleanTypography,
                fileName = snapshot.title.ifEmpty { it.fileName }
            )
        }
    }

    /**
     * 跳转到指定章节：优先命中预热缓存，未命中按需流式装载
     */
    fun goToChapter(chapterIndex: Int, targetCharOffset: Int = -1) {
        val state = uiState.value
        val uri = state.currentUri ?: return
        if (chapterIndex !in state.chapters.indices) return

        scope.launch {
            if (!session.goToChapter(chapterIndex, targetCharOffset)) {
                // 正文读取失败不跳章：留在当前页，把原因透出给 UI
                uiState.update { it.copy(errorMessage = "读取章节正文失败") }
                return@launch
            }
            syncReadingState()
            val offset = session.currentOffset
            uiState.update { it.copy(screen = Screen.Reader(offset, chapterIndex)) }

            savePositionJob?.cancel()
            savePositionJob = scope.launch(io) {
                val title = session.state.value.content?.title.orEmpty()
                store.savePosition(uri, offset, session.totalChars, title)
            }
        }
    }

    /** 下一章：末章时不再跳章，改为停掉自动滚屏（屏幕已无法再向下推进） */
    fun goToNextChapter() {
        val state = uiState.value
        val next = nextChapterIndex(state.currentChapterIndex, state.chapters.size)
        if (next != null) goToChapter(next) else stopAutoScroll()
    }

    /** 上一章：首章时不动（没有可回退的章节） */
    fun goToPrevChapter() {
        val previous = previousChapterIndex(uiState.value.currentChapterIndex)
        if (previous != null) goToChapter(previous)
    }

    /**
     * 静默更新阅读进度（只进行后台防抖持久化，绝对不触发 Compose 顶层重组）
     */
    fun updateCharOffset(offset: Int) {
        val state = uiState.value
        val uri = state.currentUri ?: return
        session.updateOffset(offset)
        onUserActive()

        savePositionJob?.cancel()
        savePositionJob = scope.launch(io) {
            delay(500L)
            val currentChapTitle = state.currentChapterContent?.title ?: ""
            store.savePosition(uri, offset, state.fullTextLength, currentChapTitle)
        }
    }

    /**
     * 立即刷新保存当前位置（退出或切换页面时）
     */
    fun flushReadingPosition() {
        val state = uiState.value
        val uri = state.currentUri
        val currentScreen = state.screen

        if (currentScreen is Screen.Home) {
            scope.launch(io) { store.saveLastScreenHome() }
            return
        }

        if (uri == null) return
        val offset = session.currentOffset
        savePositionJob?.cancel()
        scope.launch(io) {
            val currentChapTitle = state.currentChapterContent?.title ?: ""
            store.savePosition(uri, offset, state.fullTextLength, currentChapTitle)
        }
    }

    internal companion object {

        /** 下一章索引；null 表示已在最后一章 */
        fun nextChapterIndex(currentIndex: Int, chapterCount: Int): Int? {
            val next = currentIndex + 1
            return if (next < chapterCount) next else null
        }

        /** 上一章索引；null 表示已在第一章 */
        fun previousChapterIndex(currentIndex: Int): Int? {
            val previous = currentIndex - 1
            return if (previous >= 0) previous else null
        }
    }
}
