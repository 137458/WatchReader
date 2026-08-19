package com.watchreader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单章正文内容模型（轻量化渲染单元）
 */
@Immutable
data class ChapterContent(
    val chapterIndex: Int,
    val title: String,
    val formattedBody: String,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val hasPrevChapter: Boolean,
    val prevChapterTitle: String,
    val hasNextChapter: Boolean,
    val nextChapterTitle: String
)

/**
 * 阅读器 UI 状态
 */
data class ReaderUiState(
    val screen: Screen = Screen.Home,
    val currentUri: Uri? = null,
    val fileName: String = "",
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterContent: ChapterContent? = null,
    val fullTextLength: Int = 0,
    val fontSize: Int = 14,
    val isDarkMode: Boolean = false,
    val autoScrollSpeed: Float = 45f,
    val isAutoScrolling: Boolean = false,
    val appBrightness: Float = -1.0f,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val bookshelf: List<BookItem> = emptyList()
)

/**
 * ReaderViewModel — 极致性能架构（AndroidViewModel + DataStore 响应式驱动 + 章节预热缓存 + 0 重组）
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val appCtx: Context get() = getApplication<Application>()

    // 内存中持有的当前书籍文本（单份引用）
    @Volatile
    private var cachedFullText: String = ""

    // 全局章节索引内存缓存（URI+大小为 Key，二次打开 0.00ms 秒开）
    private val chapterIndexCache = ConcurrentHashMap<String, List<Chapter>>()

    // 前后相邻章节预排版内容缓存池（翻章 0.00ms 绝对秒开）
    private val chapterContentCache = ConcurrentHashMap<Int, ChapterContent>()
    private var prefetchJob: Job? = null

    // 异步防抖持久化 Job
    private var savePositionJob: Job? = null
    @Volatile
    private var currentReadingOffset: Int = 0

    /**
     * 初始化：单次 I/O 批量读取 DataStore 配置，按最后活跃页面智能秒开
     */
    fun init() {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                DataStoreManager.loadInitialConfig(appCtx)
            }

            if (config.lastScreen == "reader" && config.lastUri != null) {
                _uiState.update {
                    it.copy(
                        fontSize = config.fontSize,
                        isDarkMode = config.isDarkMode,
                        autoScrollSpeed = config.autoScrollSpeed,
                        appBrightness = config.appBrightness,
                        bookshelf = config.bookshelf,
                        screen = Screen.Loading,
                        isLoading = true,
                        currentUri = config.lastUri
                    )
                }
                loadFile(config.lastUri, config.lastCharOffset)
            } else {
                // 上次退出时在书架/非阅读页：0ms 极速进入书架，不读取大文件
                _uiState.update {
                    it.copy(
                        fontSize = config.fontSize,
                        isDarkMode = config.isDarkMode,
                        autoScrollSpeed = config.autoScrollSpeed,
                        appBrightness = config.appBrightness,
                        bookshelf = config.bookshelf,
                        screen = Screen.Home,
                        isLoading = false,
                        currentUri = null
                    )
                }
            }
        }
    }

    /**
     * 切换深色模式 / 浅色模式
     */
    fun toggleDarkMode() {
        val newMode = !_uiState.value.isDarkMode
        _uiState.update { it.copy(isDarkMode = newMode) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveDarkMode(appCtx, newMode)
        }
    }

    /**
     * 调整独立屏幕亮度
     */
    fun updateAppBrightness(brightness: Float) {
        val target = if (brightness < 0f) -1.0f else brightness.coerceIn(0.01f, 1.0f)
        _uiState.update { it.copy(appBrightness = target) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveAppBrightness(appCtx, target)
        }
    }

    /**
     * 调整自动滚屏速度
     */
    fun updateAutoScrollSpeed(speed: Float) {
        val clamped = speed.coerceIn(15f, 200f)
        _uiState.update { it.copy(autoScrollSpeed = clamped) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveAutoScrollSpeed(appCtx, clamped)
        }
    }

    /**
     * 更新自动滚屏运行状态
     */
    fun setAutoScrolling(isScrolling: Boolean) {
        _uiState.update { it.copy(isAutoScrolling = isScrolling) }
    }

    /**
     * 刷新书架列表
     */
    fun refreshBookshelf() {
        viewModelScope.launch(Dispatchers.IO) {
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update { it.copy(bookshelf = shelf) }
        }
    }

    /**
     * 异步加载书籍文件（流式加载 + 极速提取首章）
     */
    fun loadFile(uri: Uri, initialOffset: Int = 0) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    screen = Screen.Loading,
                    currentUri = uri,
                    errorMessage = null
                )
            }

            try {
                val fileName = getFileName(appCtx, uri)
                val (text, chapters) = withContext(Dispatchers.IO) {
                    val fullText = readTextFromUri(appCtx, uri)
                    if (fullText.isEmpty()) {
                        throw IllegalStateException("文件为空或无法读取")
                    }
                    val cacheKey = "${uri}_${fullText.length}"
                    val detected = chapterIndexCache.getOrPut(cacheKey) {
                        detectChapters(fullText)
                    }
                    fullText to detected
                }

                cachedFullText = text
                chapterContentCache.clear()
                val fullLen = text.length
                val safeOffset = initialOffset.coerceIn(0, fullLen)
                val chapterIndex = if (chapters.isNotEmpty()) {
                    findCurrentChapterIndex(chapters, safeOffset).coerceIn(0, chapters.lastIndex)
                } else 0

                val chapterContent = buildChapterContent(text, chapters, chapterIndex)
                chapterContentCache[chapterIndex] = chapterContent
                val chapterTitle = chapterContent.title.ifEmpty { fileName }
                currentReadingOffset = safeOffset

                // 异步预热相邻章节
                prefetchAdjacentChapters(text, chapters, chapterIndex)

                val updatedShelf = withContext(Dispatchers.IO) {
                    DataStoreManager.updateBookInShelf(appCtx, uri, safeOffset, fullLen, chapterTitle)
                }

                _uiState.update {
                    it.copy(
                        fileName = fileName,
                        chapters = chapters,
                        currentChapterIndex = chapterIndex,
                        currentChapterContent = chapterContent,
                        fullTextLength = fullLen,
                        isLoading = false,
                        bookshelf = updatedShelf,
                        screen = Screen.Reader(safeOffset, chapterIndex)
                    )
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.IO) {
                    DataStoreManager.clearReadingPosition(appCtx)
                }
                val shelf = withContext(Dispatchers.IO) { DataStoreManager.loadBookShelf(appCtx) }
                _uiState.update {
                    it.copy(
                        currentUri = null,
                        chapters = emptyList(),
                        currentChapterContent = null,
                        isLoading = false,
                        bookshelf = shelf,
                        errorMessage = "读取小说失败: ${e.localizedMessage ?: "文件不存在或无权限"}",
                        screen = Screen.Home
                    )
                }
            }
        }
    }

    /**
     * 极速跳转至指定章节（优先读取预排版缓存，0.00ms 绝对秒开）
     */
    fun goToChapter(chapterIndex: Int, targetCharOffset: Int = -1) {
        val state = _uiState.value
        val chapters = state.chapters
        if (chapterIndex !in chapters.indices) return

        val text = cachedFullText
        val chapterContent = chapterContentCache.getOrPut(chapterIndex) {
            buildChapterContent(text, chapters, chapterIndex)
        }
        val offset = if (targetCharOffset >= 0) targetCharOffset else chapterContent.startCharOffset
        currentReadingOffset = offset

        _uiState.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentChapterContent = chapterContent,
                screen = Screen.Reader(offset, chapterIndex)
            )
        }

        // 后台异步预热周围章节
        prefetchAdjacentChapters(text, chapters, chapterIndex)

        val uri = state.currentUri ?: return
        val totalLen = state.fullTextLength
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            DataStoreManager.saveReadingPosition(appCtx, uri, offset, totalLen, chapterContent.title)
        }
    }

    /**
     * 异步后台预热前后相邻章节（N+1, N-1, N+2, N-2），消除翻章排版计算
     */
    private fun prefetchAdjacentChapters(
        text: String,
        chapters: List<Chapter>,
        centerIdx: Int
    ) {
        if (text.isEmpty() || chapters.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.Default) {
            val targets = intArrayOf(centerIdx + 1, centerIdx - 1, centerIdx + 2, centerIdx - 2)
            for (idx in targets) {
                if (idx in chapters.indices && !chapterContentCache.containsKey(idx)) {
                    val built = buildChapterContent(text, chapters, idx)
                    chapterContentCache[idx] = built
                }
            }
            // 维持轻量缓存窗口，及时释放较远章节以节省内存
            if (chapterContentCache.size > 10) {
                val keysToRemove = chapterContentCache.keys.filter { Math.abs(it - centerIdx) > 4 }
                for (k in keysToRemove) {
                    chapterContentCache.remove(k)
                }
            }
        }
    }

    /**
     * 切换至下一章
     */
    fun goToNextChapter() {
        val nextIdx = _uiState.value.currentChapterIndex + 1
        if (nextIdx < _uiState.value.chapters.size) {
            goToChapter(nextIdx)
        }
    }

    /**
     * 切换至上一章
     */
    fun goToPrevChapter() {
        val prevIdx = _uiState.value.currentChapterIndex - 1
        if (prevIdx >= 0) {
            goToChapter(prevIdx)
        }
    }

    /**
     * 从书架点击打开书籍
     */
    fun openFromShelf(book: BookItem) {
        try {
            val uri = Uri.parse(book.uriString)
            loadFile(uri, book.charOffset)
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "无法解析书籍路径") }
        }
    }

    /**
     * 从书架删除书籍
     */
    fun deleteFromShelf(book: BookItem) {
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.removeBookFromShelf(appCtx, book.uriString)
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update {
                val isCurrent = it.currentUri?.toString() == book.uriString
                it.copy(
                    bookshelf = shelf,
                    currentUri = if (isCurrent) null else it.currentUri,
                    currentChapterContent = if (isCurrent) null else it.currentChapterContent,
                    chapters = if (isCurrent) emptyList() else it.chapters
                )
            }
        }
    }

    /**
     * 静默更新阅读进度（只进行后台防抖持久化，绝对不触发 Compose 顶层重组）
     */
    fun updateCharOffset(offset: Int) {
        val state = _uiState.value
        val uri = state.currentUri ?: return
        currentReadingOffset = offset

        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            val currentChapTitle = state.currentChapterContent?.title ?: ""
            DataStoreManager.saveReadingPosition(
                appCtx,
                uri,
                offset,
                state.fullTextLength,
                currentChapTitle
            )
        }
    }

    /**
     * 立即刷新保存当前位置（退出或切换页面时）
     */
    fun flushReadingPosition() {
        val state = _uiState.value
        val uri = state.currentUri
        val currentScreen = state.screen

        if (currentScreen is Screen.Home) {
            viewModelScope.launch(Dispatchers.IO) {
                DataStoreManager.saveLastScreen(appCtx, "home")
            }
            return
        }

        if (uri == null) return
        val offset = currentReadingOffset
        savePositionJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val currentChapTitle = state.currentChapterContent?.title ?: ""
            DataStoreManager.saveReadingPosition(
                appCtx,
                uri,
                offset,
                state.fullTextLength,
                currentChapTitle
            )
        }
    }

    /**
     * 调整字号
     */
    fun updateFontSize(newSize: Int) {
        val clamped = newSize.coerceIn(10, 24)
        _uiState.update { it.copy(fontSize = clamped) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveFontSize(appCtx, clamped)
        }
    }

    /**
     * 页面路由跳转
     */
    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }

    /**
     * 处理系统返回手势
     */
    fun handleBack(): Boolean {
        return when (_uiState.value.screen) {
            is Screen.Menu -> {
                val state = _uiState.value
                navigateTo(Screen.Reader(currentReadingOffset, state.currentChapterIndex))
                true
            }
            is Screen.ChapterList -> {
                val state = _uiState.value
                navigateTo(Screen.Reader(currentReadingOffset, state.currentChapterIndex))
                true
            }
            is Screen.Reader -> {
                flushReadingPosition()
                prefetchJob?.cancel()
                cachedFullText = ""
                chapterContentCache.clear()
                viewModelScope.launch(Dispatchers.IO) {
                    DataStoreManager.saveLastScreen(appCtx, "home")
                    val shelf = DataStoreManager.loadBookShelf(appCtx)
                    _uiState.update {
                        it.copy(
                            currentUri = null,
                            fileName = "",
                            chapters = emptyList(),
                            currentChapterIndex = 0,
                            currentChapterContent = null,
                            bookshelf = shelf,
                            screen = Screen.Home
                        )
                    }
                }
                true
            }
            is Screen.Loading -> {
                prefetchJob?.cancel()
                viewModelScope.launch(Dispatchers.IO) {
                    DataStoreManager.saveLastScreen(appCtx, "home")
                    val shelf = DataStoreManager.loadBookShelf(appCtx)
                    _uiState.update {
                        it.copy(
                            currentUri = null,
                            isLoading = false,
                            bookshelf = shelf,
                            screen = Screen.Home
                        )
                    }
                }
                true
            }
            is Screen.Home -> {
                viewModelScope.launch(Dispatchers.IO) {
                    DataStoreManager.saveLastScreen(appCtx, "home")
                }
                false
            }
        }
    }

    /**
     * 关闭当前书籍返回主页
     */
    fun closeBook() {
        flushReadingPosition()
        prefetchJob?.cancel()
        cachedFullText = ""
        chapterContentCache.clear()
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveLastScreen(appCtx, "home")
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update {
                it.copy(
                    currentUri = null,
                    fileName = "",
                    chapters = emptyList(),
                    currentChapterIndex = 0,
                    currentChapterContent = null,
                    bookshelf = shelf,
                    screen = Screen.Home
                )
            }
        }
    }

    fun getCurrentReadingOffset(): Int = currentReadingOffset
}

/**
 * 组装单个章节的正文排版与上下文信息（零中间切片、零 split 数组分配，极速单 pass 扫描）
 */
fun buildChapterContent(
    fullText: String,
    chapters: List<Chapter>,
    chapterIndex: Int
): ChapterContent {
    if (chapters.isEmpty() || fullText.isEmpty() || chapterIndex !in chapters.indices) {
        return ChapterContent(
            chapterIndex = 0,
            title = "",
            formattedBody = fullText,
            startCharOffset = 0,
            endCharOffset = fullText.length,
            hasPrevChapter = false,
            prevChapterTitle = "",
            hasNextChapter = false,
            nextChapterTitle = ""
        )
    }

    val currentChap = chapters[chapterIndex]
    val startOffset = currentChap.charOffset.coerceIn(0, fullText.length)
    val endOffset = (if (chapterIndex + 1 < chapters.size) chapters[chapterIndex + 1].charOffset else fullText.length).coerceIn(startOffset, fullText.length)

    val sliceLen = endOffset - startOffset
    val sb = StringBuilder(sliceLen + 128)
    val chapTitle = currentChap.title.trim()

    var lineStart = startOffset
    while (lineStart < endOffset) {
        var lineEnd = fullText.indexOf('\n', lineStart)
        if (lineEnd == -1 || lineEnd > endOffset) {
            lineEnd = endOffset
        }

        // 跳过行首空白
        var s = lineStart
        while (s < lineEnd && fullText[s].isWhitespace()) {
            s++
        }
        // 跳过行尾空白
        var e = lineEnd
        while (e > s && fullText[e - 1].isWhitespace()) {
            e--
        }

        if (s < e) {
            val lineText = fullText.substring(s, e)
            if (sb.isEmpty() && lineText == chapTitle) {
                // 跳过正文首行与章节标题重复的冗余行
            } else {
                if (sb.isNotEmpty()) {
                    sb.append("\n\n")
                }
                sb.append("\u3000\u3000").append(lineText)
            }
        }

        lineStart = lineEnd + 1
    }

    val hasPrev = chapterIndex > 0
    val prevTitle = if (hasPrev) chapters[chapterIndex - 1].title else ""
    val hasNext = chapterIndex + 1 < chapters.size
    val nextTitle = if (hasNext) chapters[chapterIndex + 1].title else ""

    return ChapterContent(
        chapterIndex = chapterIndex,
        title = currentChap.title,
        formattedBody = sb.toString(),
        startCharOffset = startOffset,
        endCharOffset = endOffset,
        hasPrevChapter = hasPrev,
        prevChapterTitle = prevTitle,
        hasNextChapter = hasNext,
        nextChapterTitle = nextTitle
    )
}
