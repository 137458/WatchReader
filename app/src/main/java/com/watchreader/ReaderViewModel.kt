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
import kotlin.math.abs

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
    val bookmarks: List<Bookmark> = emptyList(),
    val fullTextLength: Int = 0,
    val fontSize: Int = 14,
    val isDarkMode: Boolean = false,
    val autoScrollSpeed: Float = 45f,
    val isAutoScrolling: Boolean = false,
    val appBrightness: Float = -1.0f,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val bookshelf: List<BookItem> = emptyList(),
    val searchQuery: String = "",
    val isWifiServerRunning: Boolean = false,
    val wifiIpAddress: String? = null,
    val wifiPort: Int = 8888,
    val wifiUploadedCount: Int = 0,
    val isTransferring: Boolean = false,
    val transferProgress: Float = 0f,
    val transferFileName: String = "",
    val rsvpSpeed: Float = 350f
)

/**
 * ReaderViewModel — 极致性能架构（AndroidViewModel + DataStore 响应式驱动 + 章节预热缓存 + 0 重组）
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val appCtx: Context get() = getApplication<Application>()

    @Volatile
    private var currentEncoding: String = "UTF-8"

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
     * 异步加载书籍文件（按需流式加载 + 内存即时释放）
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
                val isEpub = withContext(Dispatchers.IO) {
                    EpubParser.isEpubFile(appCtx, uri)
                }

                val fileName: String
                val chapters: List<Chapter>
                val fullLen: Int

                if (isEpub) {
                    val epubMeta = withContext(Dispatchers.IO) {
                        val fileSize = getFileSize(appCtx, uri)
                        val cacheKey = "${uri}_${fileSize}"
                        val cachedChapters = chapterIndexCache[cacheKey] ?: ChapterDiskCache.load(appCtx, cacheKey)
                        if (cachedChapters != null) {
                            chapterIndexCache[cacheKey] = cachedChapters
                        }
                        val meta = EpubParser.parseEpub(appCtx, uri)
                        if (cachedChapters == null) {
                            ChapterDiskCache.save(appCtx, cacheKey, meta.chapters)
                            chapterIndexCache[cacheKey] = meta.chapters
                        }
                        meta
                    }
                    fileName = epubMeta.title
                    chapters = epubMeta.chapters
                    fullLen = epubMeta.totalChars
                } else {
                    fileName = getFileName(appCtx, uri)
                    val (scannedChapters, scannedLen) = withContext(Dispatchers.IO) {
                        currentEncoding = detectFileEncoding(appCtx, uri)
                        val fileSize = getFileSize(appCtx, uri)
                        val cacheKey = "${uri}_${fileSize}"
                        var detected = chapterIndexCache[cacheKey]
                        if (detected == null) {
                            detected = ChapterDiskCache.load(appCtx, cacheKey)
                        }

                        val totalChars: Int
                        if (detected == null) {
                            // 首次分析：纯流式解析，峰值内存恒定 < 64KB，彻底消除大文件堆内存分配
                            val (scanned, scannedChars) = detectChaptersStream(appCtx, uri, currentEncoding)
                            if (scanned.isEmpty() && scannedChars == 0) {
                                throw IllegalStateException("文件为空或无法读取")
                            }
                            totalChars = scannedChars
                            ChapterDiskCache.save(appCtx, cacheKey, scanned)
                            chapterIndexCache[cacheKey] = scanned
                            detected = scanned
                        } else {
                            chapterIndexCache[cacheKey] = detected
                            val lastChap = detected.lastOrNull()
                            val estimated = (fileSize / (if (currentEncoding.startsWith("UTF-16")) 2 else 1)).toInt()
                            totalChars = if (lastChap != null) {
                                maxOf(estimated, lastChap.charOffset + 3000)
                            } else estimated
                        }
                        detected to totalChars
                    }
                    chapters = scannedChapters
                    fullLen = scannedLen
                }

                chapterContentCache.clear()
                val safeOffset = initialOffset.coerceIn(0, fullLen)
                val chapterIndex = if (chapters.isNotEmpty()) {
                    findCurrentChapterIndex(chapters, safeOffset).coerceIn(0, chapters.lastIndex)
                } else 0

                val chapterContent = withContext(Dispatchers.IO) {
                    getOrLoadChapterContent(uri, chapters, chapterIndex, fullLen)
                }
                val chapterTitle = chapterContent.title.ifEmpty { fileName }
                currentReadingOffset = safeOffset

                // 异步预热相邻章节
                prefetchAdjacentChapters(uri, chapters, chapterIndex, fullLen)

                val updatedShelf = withContext(Dispatchers.IO) {
                    DataStoreManager.updateBookInShelf(appCtx, uri, safeOffset, fullLen, chapterTitle)
                }

                val loadedBookmarks = withContext(Dispatchers.IO) {
                    DataStoreManager.loadBookmarks(appCtx, uri.toString())
                }

                _uiState.update {
                    it.copy(
                        fileName = fileName,
                        chapters = chapters,
                        currentChapterIndex = chapterIndex,
                        currentChapterContent = chapterContent,
                        bookmarks = loadedBookmarks,
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
     * 极速跳转至指定章节（优先读取预排版缓存，未命中则流式按需分块加载）
     */
    fun goToChapter(chapterIndex: Int, targetCharOffset: Int = -1) {
        val state = _uiState.value
        val chapters = state.chapters
        val uri = state.currentUri ?: return
        if (chapterIndex !in chapters.indices) return

        viewModelScope.launch {
            val totalLen = state.fullTextLength
            val chapterContent = chapterContentCache[chapterIndex] ?: withContext(Dispatchers.IO) {
                getOrLoadChapterContent(uri, chapters, chapterIndex, totalLen)
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
            prefetchAdjacentChapters(uri, chapters, chapterIndex, totalLen)

            savePositionJob?.cancel()
            savePositionJob = viewModelScope.launch(Dispatchers.IO) {
                DataStoreManager.saveReadingPosition(appCtx, uri, offset, totalLen, chapterContent.title)
            }
        }
    }

    /**
     * 按需获取或流式加载单章排版内容
     */
    private fun getOrLoadChapterContent(
        uri: Uri,
        chapters: List<Chapter>,
        chapterIndex: Int,
        totalChars: Int
    ): ChapterContent {
        chapterContentCache[chapterIndex]?.let { return it }

        if (chapters.isEmpty() || chapterIndex !in chapters.indices) {
            return ChapterContent(0, "", "", 0, 0, false, "", false, "")
        }

        val isEpub = EpubParser.isEpubFile(appCtx, uri)
        val formatted = if (isEpub) {
            EpubParser.readChapterContent(appCtx, uri, chapterIndex, chapters)
        } else {
            val currentChap = chapters[chapterIndex]
            val startOffset = currentChap.charOffset.coerceIn(0, totalChars)
            val endOffset = (if (chapterIndex + 1 < chapters.size) chapters[chapterIndex + 1].charOffset else totalChars).coerceIn(startOffset, totalChars)

            val rawChunk = readChapterChunkFromUri(appCtx, uri, currentEncoding, startOffset, endOffset)
            formatChapterRawText(rawChunk, chapters, chapterIndex, startOffset, endOffset)
        }

        chapterContentCache[chapterIndex] = formatted
        return formatted
    }

    /**
     * 异步后台预热前后相邻章节（N+1, N-1, N+2, N-2），消除翻章排版计算
     */
    private fun prefetchAdjacentChapters(
        uri: Uri,
        chapters: List<Chapter>,
        centerIdx: Int,
        totalChars: Int
    ) {
        if (chapters.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = intArrayOf(centerIdx + 1, centerIdx - 1, centerIdx + 2, centerIdx - 2)
            for (idx in targets) {
                if (idx in chapters.indices && !chapterContentCache.containsKey(idx)) {
                    getOrLoadChapterContent(uri, chapters, idx, totalChars)
                }
            }
            // 维持轻量缓存窗口（最多 8 章），及时释放较远章节以节省内存
            if (chapterContentCache.size > 8) {
                val keysToRemove = chapterContentCache.keys.filter { Math.abs(it - centerIdx) > 3 }
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

    private var wifiServer: WifiTransferServer? = null

    /**
     * 开启局域网 Wi-Fi 传书服务
     */
    fun openWifiTransfer() {
        if (wifiServer == null) {
            wifiServer = WifiTransferServer(
                context = appCtx,
                preferredPort = 8888,
                onBookUploaded = { _ ->
                    viewModelScope.launch(Dispatchers.IO) {
                        val shelf = DataStoreManager.loadBookShelf(appCtx)
                        _uiState.update { it.copy(bookshelf = shelf) }
                    }
                }
            )
        }
        val started = wifiServer?.start() ?: false
        val ip = wifiServer?.getLocalIpAddress()
        val port = wifiServer?.activePort ?: 8888
        _uiState.update {
            it.copy(
                screen = Screen.WifiTransfer,
                isWifiServerRunning = started,
                wifiIpAddress = ip,
                wifiPort = port,
                wifiUploadedCount = 0
            )
        }
        viewModelScope.launch {
            wifiServer?.uploadedCount?.collect { count ->
                _uiState.update { it.copy(wifiUploadedCount = count) }
            }
        }
        viewModelScope.launch {
            wifiServer?.transferProgress?.collect { tp ->
                _uiState.update {
                    it.copy(
                        isTransferring = tp.isTransferring,
                        transferProgress = tp.progress,
                        transferFileName = tp.fileName
                    )
                }
            }
        }
    }

    /**
     * 关闭局域网 Wi-Fi 传书服务
     */
    fun closeWifiTransfer() {
        wifiServer?.stop()
        viewModelScope.launch(Dispatchers.IO) {
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update {
                it.copy(
                    screen = Screen.Home,
                    isWifiServerRunning = false,
                    isTransferring = false,
                    transferProgress = 0f,
                    transferFileName = "",
                    bookshelf = shelf
                )
            }
        }
    }

    /**
     * 切换书籍置顶状态
     */
    fun toggleBookPin(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val shelf = DataStoreManager.toggleBookPin(appCtx, uriString)
            _uiState.update { it.copy(bookshelf = shelf) }
        }
    }

    /**
     * 搜索书架书籍
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * 保存当前阅读位置为书签
     */
    fun addBookmark() {
        val state = _uiState.value
        val uri = state.currentUri ?: return
        val chap = state.currentChapterContent ?: return
        val currentOffset = currentReadingOffset
        val body = chap.formattedBody
        val relOffset = (currentOffset - chap.startCharOffset).coerceIn(0, body.length)
        val snippet = body.substring(relOffset, minOf(relOffset + 40, body.length)).trim().replace("\n", " ")
        val bm = Bookmark(
            chapterIndex = chap.chapterIndex,
            chapterTitle = chap.title,
            charOffset = currentOffset,
            snippet = if (snippet.isNotEmpty()) snippet else chap.title,
            time = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            val updated = DataStoreManager.saveBookmark(appCtx, uri.toString(), bm)
            _uiState.update { it.copy(bookmarks = updated) }
        }
    }

    /**
     * 删除书签
     */
    fun removeBookmark(bookmarkId: String) {
        val uri = _uiState.value.currentUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = DataStoreManager.removeBookmark(appCtx, uri.toString(), bookmarkId)
            _uiState.update { it.copy(bookmarks = updated) }
        }
    }

    /**
     * 跳转至书签精确位置
     */
    fun jumpToBookmark(bm: Bookmark) {
        goToChapter(bm.chapterIndex, bm.charOffset)
    }

    /**
     * 打开 RSVP 闪读模式
     */
    fun openRsvp() {
        _uiState.update { it.copy(screen = Screen.Rsvp) }
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
            is Screen.Rsvp -> {
                val state = _uiState.value
                navigateTo(Screen.Reader(currentReadingOffset, state.currentChapterIndex))
                true
            }
            is Screen.WifiTransfer -> {
                closeWifiTransfer()
                true
            }
            is Screen.Reader -> {
                flushReadingPosition()
                prefetchJob?.cancel()
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

    private var rsvpRotaryAccumulator = 0f
    private var lastRotaryTimeMs = 0L
    private var lastStepTimeMs = 0L

    /**
     * 响应硬件物理表冠旋转（强阻尼 + 防抖滤波 + 10字/分稳健步进）
     */
    fun handleRotaryScroll(delta: Float): Boolean {
        when (_uiState.value.screen) {
            is Screen.Rsvp -> {
                val now = System.currentTimeMillis()
                if (now - lastRotaryTimeMs > 400L) {
                    rsvpRotaryAccumulator = 0f
                }
                lastRotaryTimeMs = now

                rsvpRotaryAccumulator += delta

                // 强阻尼累加门限：较大幅度的物理转动才计为一格有效步进
                val threshold = if (abs(delta) < 5f) 2.5f else 75f
                
                if (abs(rsvpRotaryAccumulator) >= threshold && (now - lastStepTimeMs >= 75L)) {
                    val direction = if (rsvpRotaryAccumulator > 0) 1 else -1
                    rsvpRotaryAccumulator = 0f
                    lastStepTimeMs = now

                    val step = direction * 10f // 每格稳健微调 10 字/分
                    val current = _uiState.value.rsvpSpeed
                    val newSpeed = (current + step).coerceIn(100f, 900f)
                    if (newSpeed != current) {
                        _uiState.update { it.copy(rsvpSpeed = newSpeed) }
                        RotaryHapticManager.performScrollTick(appCtx, null)
                    }
                }
                return true
            }
            else -> return false
        }
    }

    fun updateRsvpSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(100f, 900f)
        _uiState.update { it.copy(rsvpSpeed = safeSpeed) }
        RotaryHapticManager.performScrollTick(appCtx, null)
    }
}

/**
 * 组装单个章节的正文排版与上下文信息（零中间切片、零 split 数组分配，极速单 pass 扫描）
 */
fun formatChapterRawText(
    rawText: String,
    chapters: List<Chapter>,
    chapterIndex: Int,
    startOffset: Int,
    endOffset: Int
): ChapterContent {
    if (chapters.isEmpty() || chapterIndex !in chapters.indices) {
        return ChapterContent(
            chapterIndex = 0,
            title = "",
            formattedBody = rawText,
            startCharOffset = 0,
            endCharOffset = rawText.length,
            hasPrevChapter = false,
            prevChapterTitle = "",
            hasNextChapter = false,
            nextChapterTitle = ""
        )
    }

    val currentChap = chapters[chapterIndex]
    val chapTitle = currentChap.title.trim()
    val sb = StringBuilder(rawText.length + 64)

    var lineStart = 0
    val textLen = rawText.length
    while (lineStart < textLen) {
        var lineEnd = rawText.indexOf('\n', lineStart)
        if (lineEnd == -1) {
            lineEnd = textLen
        }

        var s = lineStart
        while (s < lineEnd && rawText[s].isWhitespace()) {
            s++
        }
        var e = lineEnd
        while (e > s && rawText[e - 1].isWhitespace()) {
            e--
        }

        if (s < e) {
            val lineText = rawText.substring(s, e)
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
    val rawSlice = if (startOffset < endOffset) fullText.substring(startOffset, endOffset) else ""

    return formatChapterRawText(rawSlice, chapters, chapterIndex, startOffset, endOffset)
}
