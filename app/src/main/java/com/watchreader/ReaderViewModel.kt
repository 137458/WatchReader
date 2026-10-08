package com.watchreader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    val nextChapterTitle: String,
    // 正文内各段落起始索引（与 rawParagraphStarts 一一对应），空数组表示未提供映射（按正文坐标=原文坐标处理）
    val bodyParagraphStarts: IntArray = IntArray(0),
    // 原文切片内各段落首字符索引
    val rawParagraphStarts: IntArray = IntArray(0),
    // 格式化时为每个段落补入的缩进字符数（净化态=2 全角空格，原样态=0 逐字符 1:1），供坐标换算扣除
    val paragraphIndentChars: Int = 2
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
    val wifiToken: String? = null,
    val wifiUploadedCount: Int = 0,
    val isTransferring: Boolean = false,
    val transferProgress: Float = 0f,
    val transferFileName: String = "",
    val rsvpSpeed: Float = 350f,
    val themeMode: Int = 0,
    val tapPageArea: Int = 0,
    val cleanTypography: Boolean = true,
    val fontType: Int = 0,
    val readDurationSec: Long = 0L,
    val readDays: Map<String, Long> = emptyMap(),
    val readGoalMinutes: Int = 0,
    val readGoalCelebrated: String = "",
    val lineSpacing: Int = LineSpacingMode.STANDARD.value,
    val letterSpacing: Int = LetterSpacingMode.STANDARD.value,
    val isSearching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val searchScannedChapters: Int = 0,
    val searchTotalChapters: Int = 0,
    val infoMessage: String? = null
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

    // 转换类格式（MOBI/FB2/HTML）的派生读取源：转换产物文本缓存与其格式。
    // 章节索引与正文一律从缓存文件读取，书架/进度仍持久化原始 URI
    @Volatile
    private var convertedFormat: BookFormat? = null
    @Volatile
    private var convertedReadUri: Uri? = null

    // 全局章节索引内存缓存（URI+大小为 Key，二次打开 0.00ms 秒开）
    private val chapterIndexCache = ConcurrentHashMap<String, List<Chapter>>()

    // 前后相邻章节预排版内容缓存池（翻章 0.00ms 绝对秒开）
    private val chapterContentCache = ConcurrentHashMap<Int, ChapterContent>()
    private var prefetchJob: Job? = null
    private var cleanTypographyJob: Job? = null
    private var searchJob: Job? = null

    // 异步防抖持久化 Job
    private var savePositionJob: Job? = null
    @Volatile
    private var currentReadingOffset: Int = 0

    // Wi-Fi 传书协程监听生命周期托管
    private var wifiCollectJob: Job? = null

    // 活跃阅读时长统计器（uptimeMillis：间隔计算不受系统时间调整影响）
    private var readingTimerJob: Job? = null

    @Volatile
    private var lastActiveTime: Long = android.os.SystemClock.uptimeMillis()

    @Volatile
    private var initialized = false

    /**
     * 初始化：单次 I/O 批量读取 DataStore 配置，按最后活跃页面智能秒开
     * （Activity 重建会再次触发 onCreate，重入直接返回，避免整本书重载）
     */
    fun init() {
        if (initialized) return
        initialized = true
        // 中文宋体面是 20MB 级字面，启动即后台预热，避免首次切换字体时主线程同步加载
        viewModelScope.launch(Dispatchers.IO) { CjkSerifFont.preload() }
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) {
                DataStoreManager.loadInitialConfig(appCtx)
            }

            if (config.lastScreen == "reader" && config.lastUri != null) {
                _uiState.update {
                    it.copy(
                        fontSize = config.fontSize,
                        autoScrollSpeed = config.autoScrollSpeed,
                        appBrightness = config.appBrightness,
                        bookshelf = config.bookshelf,
                        themeMode = config.themeMode,
                        tapPageArea = config.tapPageArea,
                        cleanTypography = config.cleanTypography,
                        fontType = config.fontType,
                        readDurationSec = config.readDurationSec,
                        readDays = config.readDays,
                        readGoalMinutes = config.readGoalMinutes,
                        readGoalCelebrated = config.readGoalCelebrated,
                        lineSpacing = config.lineSpacing,
                        letterSpacing = config.letterSpacing,
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
                        autoScrollSpeed = config.autoScrollSpeed,
                        appBrightness = config.appBrightness,
                        bookshelf = config.bookshelf,
                        themeMode = config.themeMode,
                        tapPageArea = config.tapPageArea,
                        cleanTypography = config.cleanTypography,
                        fontType = config.fontType,
                        readDurationSec = config.readDurationSec,
                        readDays = config.readDays,
                        readGoalMinutes = config.readGoalMinutes,
                        readGoalCelebrated = config.readGoalCelebrated,
                        lineSpacing = config.lineSpacing,
                        letterSpacing = config.letterSpacing,
                        screen = Screen.Home,
                        isLoading = false,
                        currentUri = null
                    )
                }
            }
            startReadingTimer()
        }
    }

    /**
     * 书架主页「日 / 夜」快捷开关：直接切主题本身
     *
     * 这里过去只翻转独立的 isDarkMode 布尔量，而真正决定配色的是 themeMode ——
     * 于是主页按下"夜"后界面纹丝不动，只有重启时 loadInitialConfig 用 isDarkMode
     * 反推 themeMode 才生效（即"需要重启应用"的根因）。现在两个入口（主页快捷开关 /
     * 菜单主题轮换）共用同一状态源，均即时生效。
     */
    fun toggleDarkMode() {
        val current = ThemeMode.fromValue(_uiState.value.themeMode)
        // 按主题自身明暗属性定向：亮色档（羊皮纸 / HyperOS 亮）一律去极光黑，反之回羊皮纸
        setThemeMode(if (current.isDark) ThemeMode.PARCHMENT else ThemeMode.DARK)
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
     * 携最新阅读偏移返回阅读页：菜单各入口（上一章/下一章/加书签/开滚屏/返回）的
     * 统一出口。偏移取 VM 内的最新值，UI 层不再跨层读取 volatile 字段
     */
    fun returnToReader() {
        _uiState.update { it.copy(screen = Screen.Reader(currentReadingOffset, it.currentChapterIndex)) }
    }

    /**
     * 异步加载书籍文件（按需流式加载 + 内存即时释放）
     */
    fun loadFile(uri: Uri, initialOffset: Int = 0) {
        viewModelScope.launch {
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    screen = Screen.Loading,
                    currentUri = uri,
                    errorMessage = null,
                    isSearching = false,
                    searchResults = emptyList()
                )
            }

            try {
                val format = withContext(Dispatchers.IO) {
                    BookTextConverter.resolveFormat(appCtx, uri)
                }

                val fileName: String
                val chapters: List<Chapter>
                val fullLen: Int

                if (format == BookFormat.EPUB) {
                    // 解析结果由 EpubParser.metadataCache 会话级缓存（URI+大小为键），
                    // 章节索引磁盘缓存对 EPUB 无消费方，读写皆是死 I/O
                    val epubMeta = withContext(Dispatchers.IO) {
                        EpubParser.parseEpub(appCtx, uri)
                    }
                    fileName = epubMeta.title
                    chapters = epubMeta.chapters
                    fullLen = epubMeta.totalChars
                } else {
                    fileName = getFileName(appCtx, uri)
                    val (scannedChapters, scannedLen) = withContext(Dispatchers.IO) {
                        convertedFormat = null
                        convertedReadUri = null
                        var readUri = uri
                        val encoding: String
                        if (format == BookFormat.TXT) {
                            encoding = detectFileEncoding(appCtx, uri)
                        } else {
                            // 转换类格式先流式生成 UTF-8 文本缓存，随后全部读取走缓存文件
                            val cacheFile = BookTextConverter.convertToCacheFile(appCtx, uri, format)
                            convertedFormat = format
                            convertedReadUri = Uri.fromFile(cacheFile)
                            readUri = convertedReadUri!!
                            encoding = "UTF-8"
                        }
                        currentEncoding = encoding

                        val fileSize = getFileSize(appCtx, uri)
                        scanChapterIndex(readUri, "${uri}_${fileSize}", encoding, fileSize)
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
     * 流式装载章节索引（TXT 与转换类格式共用）：优先内存/磁盘缓存，未命中则
     * 以恒定 < 64KB 峰值内存单趟流式扫描。[readUri] 为实际读取源；
     * [fileSizeHint] 供磁盘缓存缺失总长时估算，章节索引键保持书架原始 URI 域稳定
     */
    private fun scanChapterIndex(
        readUri: Uri,
        cacheKey: String,
        encoding: String,
        fileSizeHint: Long
    ): Pair<List<Chapter>, Int> {
        var detected = chapterIndexCache[cacheKey]
        var cachedTotalChars = 0
        if (detected == null) {
            val cachedData = ChapterDiskCache.load(appCtx, cacheKey)
            if (cachedData != null) {
                detected = cachedData.chapters
                cachedTotalChars = cachedData.totalChars
            }
        }

        val totalChars: Int
        if (detected == null) {
            val (scanned, scannedChars) = detectChaptersStream(appCtx, readUri, encoding)
            if (scanned.isEmpty() && scannedChars == 0) {
                throw IllegalStateException("文件为空或无法读取")
            }
            totalChars = scannedChars
            ChapterDiskCache.save(appCtx, cacheKey, scanned, scannedChars)
            chapterIndexCache[cacheKey] = scanned
            detected = scanned
        } else {
            chapterIndexCache[cacheKey] = detected
            totalChars = if (cachedTotalChars > 0) {
                cachedTotalChars
            } else {
                val lastChap = detected.lastOrNull()
                val estimated = (fileSizeHint / (if (encoding.startsWith("UTF-16")) 2 else 1)).toInt()
                if (lastChap != null) {
                    maxOf(estimated, lastChap.charOffset + 3000)
                } else estimated
            }
        }
        return detected to totalChars
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
            val chapterContent = chapterContentCache[chapterCacheKey(chapterIndex, state.cleanTypography)]
                ?: withContext(Dispatchers.IO) {
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
     * 按需获取或流式加载单章排版内容（净化态与原样态各持一份，切换开关不再丢弃预热成果）
     */
    private fun getOrLoadChapterContent(
        uri: Uri,
        chapters: List<Chapter>,
        chapterIndex: Int,
        totalChars: Int
    ): ChapterContent {
        val clean = _uiState.value.cleanTypography
        chapterContentCache[chapterCacheKey(chapterIndex, clean)]?.let { return it }

        if (chapters.isEmpty() || chapterIndex !in chapters.indices) {
            return ChapterContent(0, "", "", 0, 0, false, "", false, "")
        }

        val formatted = when {
            convertedFormat != null -> loadChunkContent(uri, chapters, chapterIndex, totalChars)
            EpubParser.isEpubFile(appCtx, uri) -> EpubParser.readChapterContent(appCtx, uri, chapterIndex, chapters, clean)
            else -> loadChunkContent(uri, chapters, chapterIndex, totalChars)
        }

        chapterContentCache[chapterCacheKey(chapterIndex, clean)] = formatted
        return formatted
    }

    /** TXT 与转换类格式共用的分块正文装载：从读取源取原文切片后按章节排版 */
    private fun loadChunkContent(
        originalUri: Uri,
        chapters: List<Chapter>,
        chapterIndex: Int,
        totalChars: Int,
        clean: Boolean = _uiState.value.cleanTypography
    ): ChapterContent {
        val readUri = if (convertedFormat != null) resolveConvertedReadUri(originalUri) else originalUri
        val startOffset = chapters[chapterIndex].charOffset.coerceIn(0, totalChars)
        val endOffset = endOffsetOf(chapters, chapterIndex, totalChars).coerceIn(startOffset, totalChars)

        val rawChunk = readChapterChunkFromUri(appCtx, readUri, currentEncoding, startOffset, endOffset)
        return formatChapterRawText(rawChunk, chapters, chapterIndex, startOffset, endOffset, clean)
    }

    /**
     * 解析转换类格式的实际读取源。文本缓存可能被系统清理：确定性转换保证
     * 字符偏移域不变，重转换后进度、书签与章节索引依旧有效
     */
    private fun resolveConvertedReadUri(originalUri: Uri): Uri {
        val cached = convertedReadUri
        if (cached != null) {
            val path = cached.path
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.length() > 0) return cached
            }
        }
        val format = convertedFormat ?: return cached ?: originalUri
        return try {
            val fresh = Uri.fromFile(BookTextConverter.convertToCacheFile(appCtx, originalUri, format))
            convertedReadUri = fresh
            fresh
        } catch (_: Exception) {
            cached ?: originalUri
        }
    }

    private fun endOffsetOf(chapters: List<Chapter>, chapterIndex: Int, totalChars: Int): Int =
        if (chapterIndex + 1 < chapters.size) chapters[chapterIndex + 1].charOffset else totalChars

    /** 章节正文缓存键：章节索引左移一位，末位编码净化态，两种排版并存互不驱逐 */
    private fun chapterCacheKey(chapterIndex: Int, clean: Boolean): Int =
        (chapterIndex shl 1) or if (clean) 1 else 0

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
        val clean = _uiState.value.cleanTypography
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = intArrayOf(centerIdx + 1, centerIdx - 1, centerIdx + 2, centerIdx - 2)
            for (idx in targets) {
                if (idx in chapters.indices && !chapterContentCache.containsKey(chapterCacheKey(idx, clean))) {
                    getOrLoadChapterContent(uri, chapters, idx, totalChars)
                }
            }
            // 维持轻量缓存窗口（净化态与原样态各 8 章），及时释放较远章节以节省内存
            if (chapterContentCache.size > 16) {
                val keysToRemove = chapterContentCache.keys.filter { Math.abs((it shr 1) - centerIdx) > 3 }
                for (k in keysToRemove) {
                    chapterContentCache.remove(k)
                }
            }
        }
    }

    /**
     * 搜索用单章装载：固定净化关闭态（正文 = 原文切片后缀，坐标可直接换算），
     * 且绕过章节内容缓存——全书扫描不得污染 16 章预热窗口
     */
    private suspend fun loadChapterContentForSearch(
        uri: Uri,
        chapters: List<Chapter>,
        chapterIndex: Int
    ): ChapterContent = withContext(Dispatchers.IO) {
        val totalChars = _uiState.value.fullTextLength
        when {
            convertedFormat != null -> loadChunkContent(uri, chapters, chapterIndex, totalChars, clean = false)
            EpubParser.isEpubFile(appCtx, uri) -> EpubParser.readChapterContent(appCtx, uri, chapterIndex, chapters, false)
            else -> loadChunkContent(uri, chapters, chapterIndex, totalChars, clean = false)
        }
    }

    /**
     * 书内全文搜索：非 EPUB 走全书单趟流式扫描（顺序解码 + [ChapterSliceAssembler]
     * 边界切片，替代每章从头 skip 解码的 O(n²)），EPUB 逐章读取（各条目独立，
     * 总代价本就是各章之和）；命中即增量上报，全书命中总数受 MAX_RESULTS 封顶
     */
    fun searchInBook(query: String) {
        val state = _uiState.value
        val uri = state.currentUri ?: return
        val chapters = state.chapters
        val q = query.trim()
        if (chapters.isEmpty() || q.isEmpty()) return

        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearching = true,
                searchResults = emptyList(),
                searchScannedChapters = 0,
                searchTotalChapters = chapters.size
            )
        }
        searchJob = viewModelScope.launch {
            val hits = mutableListOf<SearchHit>()
            try {
                withContext(Dispatchers.IO) {
                    val totalChars = state.fullTextLength
                    if (convertedFormat == null && EpubParser.isEpubFile(appCtx, uri)) {
                        for ((idx, chapter) in chapters.withIndex()) {
                            if (!isActive || hits.size >= BookSearchEngine.MAX_RESULTS) break
                            scanChapterByLoading(uri, chapters, idx, q, hits)
                            reportSearchProgress(idx + 1, hits)
                        }
                    } else {
                        val readUri = if (convertedFormat != null) resolveConvertedReadUri(uri) else uri
                        val ends = IntArray(chapters.size) { i -> endOffsetOf(chapters, i, totalChars) }
                        val assembler = ChapterSliceAssembler(ends)
                        val reader = openBookCharStream(appCtx, readUri, currentEncoding)
                        if (reader == null) {
                            // 单趟流打开失败（如 provider 不支持 fd）回退逐章装载：坐标域一致，仅慢
                            for ((idx, chapter) in chapters.withIndex()) {
                                if (!isActive || hits.size >= BookSearchEngine.MAX_RESULTS) break
                                scanChapterByLoading(uri, chapters, idx, q, hits)
                                reportSearchProgress(idx + 1, hits)
                            }
                        } else {
                            reader.use { r ->
                                val buf = CharArray(24576)
                                while (isActive && hits.size < BookSearchEngine.MAX_RESULTS) {
                                    val n = r.read(buf)
                                    if (n < 0) break
                                    if (n > 0) assembler.feed(buf, 0, n)
                                    val done = assembler.drain()
                                    if (done.isNotEmpty()) {
                                        for ((idx, slice) in done) {
                                            if (!isActive || hits.size >= BookSearchEngine.MAX_RESULTS) break
                                            scanChapterSlice(slice, chapters, idx, totalChars, q, hits)
                                        }
                                        reportSearchProgress(done.last().first + 1, hits)
                                    }
                                }
                                assembler.finish()
                                for ((idx, slice) in assembler.drain()) {
                                    if (!isActive || hits.size >= BookSearchEngine.MAX_RESULTS) break
                                    scanChapterSlice(slice, chapters, idx, totalChars, q, hits)
                                }
                                reportSearchProgress(chapters.size, hits)
                            }
                        }
                    }
                }
            } finally {
                if (isActive) {
                    _uiState.update { it.copy(isSearching = false, searchResults = hits.toList()) }
                }
            }
        }
    }

    /** 停止全书扫描（离开搜索页 / 跳转命中时调用）：已扫到的命中保留，转圈态立即复位 */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false) }
    }

    /** 单章装载→匹配（EPUB 与流打开失败的回退路径），坐标语义与流式切片一致 */
    private suspend fun scanChapterByLoading(
        uri: Uri,
        chapters: List<Chapter>,
        chapterIndex: Int,
        query: String,
        hits: MutableList<SearchHit>
    ) {
        val content = loadChapterContentForSearch(uri, chapters, chapterIndex)
        appendSearchHits(content.formattedBody, content.rawParagraphStarts.firstOrNull() ?: 0, content.startCharOffset, chapters, chapterIndex, query, hits)
    }

    /** 流式切片→与逐章装载同语义的净化关闭态排版→匹配（单趟扫描核心步） */
    private fun scanChapterSlice(
        slice: String,
        chapters: List<Chapter>,
        chapterIndex: Int,
        totalChars: Int,
        query: String,
        hits: MutableList<SearchHit>
    ) {
        if (chapterIndex !in chapters.indices || slice.isEmpty()) return
        val startOffset = chapters[chapterIndex].charOffset.coerceIn(0, totalChars)
        val endOffset = endOffsetOf(chapters, chapterIndex, totalChars).coerceIn(startOffset, totalChars)
        val content = formatChapterRawText(slice, chapters, chapterIndex, startOffset, endOffset, cleanTypography = false)
        appendSearchHits(content.formattedBody, content.rawParagraphStarts.firstOrNull() ?: 0, startOffset, chapters, chapterIndex, query, hits)
    }

    private fun appendSearchHits(
        body: String,
        bodyStartInRaw: Int,
        baseCharOffset: Int,
        chapters: List<Chapter>,
        chapterIndex: Int,
        query: String,
        hits: MutableList<SearchHit>
    ) {
        if (body.isEmpty()) return
        hits += BookSearchEngine.findMatches(
            body = body,
            query = query,
            bodyStartInRaw = bodyStartInRaw,
            baseCharOffset = baseCharOffset,
            chapterIndex = chapterIndex,
            chapterTitle = chapters[chapterIndex].title,
            maxHits = BookSearchEngine.MAX_RESULTS - hits.size
        )
    }

    private fun reportSearchProgress(scanned: Int, hits: List<SearchHit>) {
        _uiState.update {
            it.copy(searchScannedChapters = scanned, searchResults = hits.toList())
        }
    }

    /** 跳转至搜索命中位置（章节 + 全书原始字符偏移精准定位），跳转即停止后台扫描 */
    fun jumpToSearchHit(hit: SearchHit) {
        cancelSearch()
        goToChapter(hit.chapterIndex, hit.charOffset)
    }

    /**
     * 切换至下一章
     */
    fun goToNextChapter() {
        val nextIdx = _uiState.value.currentChapterIndex + 1
        if (nextIdx < _uiState.value.chapters.size) {
            goToChapter(nextIdx)
        } else {
            setAutoScrolling(false)
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
        notifyUserActive()

        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500L)
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
                },
                onBookDeleted = { fileName ->
                    notifyBookDeletedFromWeb(fileName)
                }
            )
        }
        // 先切页，再后台绑定：ServerSocket bind（最多尝试 6 个端口）与网卡枚举是阻塞 IO，
        // 从 onClick 主线程直调存在 ANR 风险
        _uiState.update { it.copy(screen = Screen.WifiTransfer, wifiIpAddress = null, wifiToken = null, wifiUploadedCount = 0) }
        viewModelScope.launch {
            val server = wifiServer ?: return@launch
            val started = withContext(Dispatchers.IO) { server.start() }
            val ip = withContext(Dispatchers.IO) { server.getLocalIpAddress() }
            _uiState.update {
                it.copy(
                    isWifiServerRunning = started,
                    wifiIpAddress = ip,
                    wifiPort = server.activePort,
                    wifiToken = server.accessToken
                )
            }
        }
        wifiCollectJob?.cancel()
        wifiCollectJob = viewModelScope.launch {
            launch {
                wifiServer?.uploadedCount?.collect { count ->
                    _uiState.update { it.copy(wifiUploadedCount = count) }
                }
            }
            launch {
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
    }

    /**
     * 关闭局域网 Wi-Fi 传书服务
     */
    fun closeWifiTransfer() {
        wifiServer?.stop()
        wifiCollectJob?.cancel()
        wifiCollectJob = null
        viewModelScope.launch(Dispatchers.IO) {
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update {
                it.copy(
                    screen = Screen.Home,
                    isWifiServerRunning = false,
                    isTransferring = false,
                    transferProgress = 0f,
                    transferFileName = "",
                    wifiToken = null,
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
            is Screen.Menu, is Screen.ChapterList, is Screen.Rsvp -> {
                returnToReader()
                true
            }
            is Screen.Search -> {
                // 离开搜索页即停扫：O(n) 后仍不应在阅读/书架页后台空转
                cancelSearch()
                returnToReader()
                true
            }
            is Screen.WifiTransfer -> {
                closeWifiTransfer()
                true
            }
            is Screen.Reader -> {
                closeBookInternal()
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
    fun closeBook() = closeBookInternal()

    /** 关书统一路径：落盘进度 + 取消预热/搜索 + 清空章节缓存 + 复位到书架 */
    private fun closeBookInternal() {
        flushReadingPosition()
        prefetchJob?.cancel()
        searchJob?.cancel()
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
                    screen = Screen.Home,
                    isSearching = false,
                    searchResults = emptyList()
                )
            }
        }
    }

    fun getCurrentReadingOffset(): Int = currentReadingOffset

    // RSVP 表冠调速阻尼器（算法见 RsvpRotaryDamper，测试覆盖 RsvpRotaryDamperTest）
    private val rsvpDamper = RsvpRotaryDamper()

    /**
     * 响应硬件物理表冠旋转（强阻尼 + 防抖滤波 + 10字/分稳健步进）
     */
    fun handleRotaryScroll(delta: Float): Boolean {
        when (_uiState.value.screen) {
            is Screen.Rsvp -> {
                val now = android.os.SystemClock.uptimeMillis()
                val step = rsvpDamper.onDelta(delta, now)
                if (step != 0f) {
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

    /**
     * 标记用户在阅读器中有活跃操作
     */
    fun notifyUserActive() {
        lastActiveTime = android.os.SystemClock.uptimeMillis()
    }

    /**
     * 启动阅读时长后台统计循环
     * 每 10s 结算一次：总时长与当日秒数同事务落盘；每日目标首次达成时轻振庆祝
     */
    private fun startReadingTimer() {
        readingTimerJob?.cancel()
        readingTimerJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                val state = _uiState.value
                if (state.screen is Screen.Reader) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastActiveTime <= 60_000L) {
                        val todayKey = ReadingStats.dateKeyOf(System.currentTimeMillis())
                        DataStoreManager.addReadingSeconds(appCtx, 10L, todayKey)

                        val newSec = state.readDurationSec + 10L
                        val newDays = ReadingStats.addSeconds(state.readDays, todayKey, 10L)
                        var celebrated = state.readGoalCelebrated
                        if (ReadingStats.shouldCelebrate(newDays[todayKey] ?: 0L, state.readGoalMinutes, celebrated, todayKey)) {
                            RotaryHapticManager.performSuccessFeedback(appCtx)
                            DataStoreManager.saveReadGoalCelebrated(appCtx, todayKey)
                            celebrated = todayKey
                        }
                        _uiState.update {
                            it.copy(
                                readDurationSec = newSec,
                                readDays = newDays,
                                readGoalCelebrated = celebrated
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 设置每日阅读目标（分钟档位见 ReadingStats.GOAL_OPTIONS_MINUTES，0 = 关闭）
     */
    fun setReadGoalMinutes(minutes: Int) {
        val safe = if (ReadingStats.GOAL_OPTIONS_MINUTES.contains(minutes)) minutes else 0
        _uiState.update { it.copy(readGoalMinutes = safe) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.setReadGoalMinutes(appCtx, safe)
        }
    }

    /**
     * 设置正文行距档位（LineSpacingMode，仅改排版参数，不触发章节重格式化）
     */
    fun setLineSpacing(mode: Int) {
        val safe = LineSpacingMode.fromValue(mode).value
        _uiState.update { it.copy(lineSpacing = safe) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveLineSpacing(appCtx, safe)
        }
    }

    /**
     * 设置正文字距档位（LetterSpacingMode，仅改排版参数，不触发章节重格式化）
     */
    fun setLetterSpacing(mode: Int) {
        val safe = LetterSpacingMode.fromValue(mode).value
        _uiState.update { it.copy(letterSpacing = safe) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveLetterSpacing(appCtx, safe)
        }
    }

    // ── 书架备份与恢复 ──

    /** 生成备份 JSON（调用方负责写入 SAF 目标文件） */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        DataStoreManager.exportBackupJson(appCtx)
    }

    /**
     * 从备份 JSON 恢复：成功后重载全部配置与书架到当前状态
     * @return 是否恢复成功
     */
    suspend fun restoreBackup(json: String): Boolean {
        val count = withContext(Dispatchers.IO) { DataStoreManager.restoreBackup(appCtx, json) }
        if (count < 0) return false
        val config = withContext(Dispatchers.IO) { DataStoreManager.loadInitialConfig(appCtx) }
        _uiState.update {
            it.copy(
                fontSize = config.fontSize,
                autoScrollSpeed = config.autoScrollSpeed,
                appBrightness = config.appBrightness,
                bookshelf = config.bookshelf,
                themeMode = config.themeMode,
                tapPageArea = config.tapPageArea,
                cleanTypography = config.cleanTypography,
                fontType = config.fontType,
                readDurationSec = config.readDurationSec,
                readDays = config.readDays,
                readGoalMinutes = config.readGoalMinutes,
                readGoalCelebrated = config.readGoalCelebrated,
                infoMessage = "已恢复 ${count} 本书与全部设置"
            )
        }
        return true
    }

    /** 备份导出结果提示（导出本身不改变状态，无需重载） */
    fun notifyBackupExported(success: Boolean) {
        _uiState.update {
            if (success) {
                it.copy(infoMessage = "备份已导出")
            } else {
                it.copy(errorMessage = "备份导出失败：无法写入所选文件")
            }
        }
    }

    fun notifyBackupRestoreFailed() {
        _uiState.update { it.copy(errorMessage = "恢复失败：不是有效的备份文件") }
    }

    /** 信息条自动消失 */
    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    /**
     * 设置主题模式 (0: 羊皮纸/浅色, 1: 极光黑/深色, 2: 纯黑深红夜视)
     */
    fun setThemeMode(mode: Int) {
        setThemeMode(ThemeMode.fromValue(mode))
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode.value) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveThemeMode(appCtx, mode.value)
        }
    }

    /**
     * 设置点按翻页热区 (0: 上下翻页, 1: 左右翻页, 2: 关闭点按)
     */
    fun setTapPageArea(area: Int) {
        setTapPageArea(TapPageArea.fromValue(area))
    }

    fun setTapPageArea(area: TapPageArea) {
        _uiState.update { it.copy(tapPageArea = area.value) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveTapPageArea(appCtx, area.value)
        }
    }

    /**
     * 开关智能排版净化
     *
     * 只替换正文、不改 screen —— 与字号/主题/字体档一致，菜单保持打开。
     * 此前复用 goToChapter 重载正文，会把 screen 导航成 Screen.Reader，
     * 使阅读控制界面被无过渡地直接踢掉。
     */
    fun setCleanTypography(enabled: Boolean) {
        _uiState.update { it.copy(cleanTypography = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreManager.saveCleanTypography(appCtx, enabled)
        }

        val state = _uiState.value
        val uri = state.currentUri ?: return
        val chapterIndex = state.currentChapterIndex
        if (state.chapters.isEmpty() || chapterIndex !in state.chapters.indices) return

        // 快速连点时取消上一次切换：只有最新排版态的正文允许落回 UI，避免旧协程晚归覆盖
        cleanTypographyJob?.cancel()
        cleanTypographyJob = viewModelScope.launch {
            val chapterContent = chapterContentCache[chapterCacheKey(chapterIndex, enabled)]
                ?: withContext(Dispatchers.IO) {
                    getOrLoadChapterContent(uri, state.chapters, chapterIndex, state.fullTextLength)
                }
            if (isActive && _uiState.value.cleanTypography == enabled) {
                _uiState.update { it.copy(currentChapterContent = chapterContent) }
                prefetchAdjacentChapters(uri, state.chapters, chapterIndex, state.fullTextLength)
            }
        }
    }

    /**
     * 设置字体类型 (0: 黑体, 1: 宋体/衬线)
     */
    fun setFontType(type: Int) {
        setFontType(FontType.fromValue(type))
    }

    fun setFontType(type: FontType) {
        _uiState.update { it.copy(fontType = type.value) }
        viewModelScope.launch(Dispatchers.IO) {
            CjkSerifFont.preload()
            DataStoreManager.saveFontType(appCtx, type.value)
        }
    }

    /**
     * Web 端删除书籍同步通知：同步清理 DataStore 书架持久化记录并关闭当前已开书籍
     */
    fun notifyBookDeletedFromWeb(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            val matchedBook = shelf.firstOrNull { book ->
                val uri = try { Uri.parse(book.uriString) } catch (_: Exception) { null }
                val pathEnd = uri?.lastPathSegment ?: book.uriString.substringAfterLast('/')
                pathEnd.equals(fileName, ignoreCase = true) ||
                    book.title.equals(EpubParser.cleanBookTitle(fileName), ignoreCase = true)
            }
            if (matchedBook != null) {
                DataStoreManager.removeBookFromShelf(appCtx, matchedBook.uriString)
            }
            val updatedShelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update { state ->
                val isCurrent = state.fileName.equals(fileName, ignoreCase = true) ||
                    (matchedBook != null && state.currentUri?.toString() == matchedBook.uriString)
                state.copy(
                    bookshelf = updatedShelf,
                    currentUri = if (isCurrent) null else state.currentUri,
                    currentChapterContent = if (isCurrent) null else state.currentChapterContent,
                    chapters = if (isCurrent) emptyList() else state.chapters,
                    screen = if (isCurrent) Screen.Home else state.screen
                )
            }
        }
    }
}
