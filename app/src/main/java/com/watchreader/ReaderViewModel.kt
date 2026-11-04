package com.watchreader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    val bookSearchQuery: String = "",
    val pendingRestoreCount: Int? = null,
    val infoMessage: String? = null
)

/**
 * ReaderViewModel — 极致性能架构（AndroidViewModel + DataStore 响应式驱动 + 章节预热缓存 + 0 重组）
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val appCtx: Context get() = getApplication<Application>()

    /**
     * 阅读会话：一本书的正文、章节定位、章节缓存与相邻预热全部归它所有。
     *
     * 收口前这里是四个跨调用哨兵字段（编码 / 转换格式 / 转换读取源 / 当前偏移）加两张
     * 缓存表，任何一个组合错了都表现为串书或进度错位，且从外部无法观察。
     */
    private val reading = ReadingSession(appCtx, viewModelScope)

    private var cleanTypographyJob: Job? = null

    /**
     * 书内搜索编排：扫描、进度上报、取消语义都在它里面，搜索态不再占用 ReaderUiState
     * （搜索逐键/逐切片回写整份 uiState 会连带书架列表一起重组）
     */
    private val search = BookSearchController(viewModelScope)

    val searchState: StateFlow<SearchState> = search.state

    // 异步防抖持久化 Job
    private var savePositionJob: Job? = null
    private var loadJob: Job? = null

    /**
     * 传书服务生命周期与状态镜像：ip/端口/令牌/进度不再占用 ReaderUiState，
     * 传书页只订阅这条流，书架的整份状态不会因为上传进度而变化
     */
    private val transferController = TransferController(viewModelScope) {
        WifiTransferServerAdapter(
            WifiTransferServer(
                context = appCtx,
                preferredPort = 8888,
                onBookUploaded = { _ ->
                    viewModelScope.launch(Dispatchers.IO) {
                        val shelf = DataStoreManager.loadBookShelf(appCtx)
                        _uiState.update { it.copy(bookshelf = shelf) }
                    }
                },
                onBookDeleted = { fileName -> notifyBookDeletedFromWeb(fileName) }
            )
        )
    }

    val transferState: StateFlow<TransferUiState> = transferController.state

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
        setThemeMode(if (current.isDark) ThemeMode.LIGHT else ThemeMode.DARK)
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
        val clamped = AutoScrollSpeed.clamp(speed)
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
        _uiState.update { it.copy(screen = Screen.Reader(reading.currentOffset, it.currentChapterIndex)) }
    }

    /**
     * 异步加载书籍文件：格式分流、章节索引、首章正文与相邻预热全部交给 `ReadingSession`
     */
    fun loadFile(uri: Uri, initialOffset: Int = 0) {
        // 重入即取消上一次装载：两本书的流程交错会互相覆盖会话状态
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            search.reset()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    screen = Screen.Loading,
                    currentUri = uri,
                    errorMessage = null
                )
            }

            // 净化排版态是本类持有的用户设置，装载前先交给会话，首章直接按该态排版
            reading.setCleanTypography(_uiState.value.cleanTypography)

            when (val opened = reading.open(uri, initialOffset)) {
                is SessionOpenResult.Failed -> {
                    // 装载失败是常规路径（文件被删、权限失效、DRM）：回书架并说明原因
                    withContext(Dispatchers.IO) { DataStoreManager.clearReadingPosition(appCtx) }
                    val shelf = withContext(Dispatchers.IO) { DataStoreManager.loadBookShelf(appCtx) }
                    _uiState.update {
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
                    val chapterTitle = reading.state.value.content?.title.orEmpty()
                        .ifEmpty { opened.title }
                    val updatedShelf = withContext(Dispatchers.IO) {
                        DataStoreManager.updateBookInShelf(
                            appCtx, uri, reading.currentOffset, opened.totalChars, chapterTitle
                        )
                    }
                    val loadedBookmarks = withContext(Dispatchers.IO) {
                        DataStoreManager.loadBookmarks(appCtx, uri.toString())
                    }
                    _uiState.update {
                        it.copy(
                            fileName = opened.title,
                            bookmarks = loadedBookmarks,
                            isLoading = false,
                            bookshelf = updatedShelf,
                            screen = Screen.Reader(reading.currentOffset, reading.state.value.chapterIndex)
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
     * 但"这份数据从哪来"收敛在这里，阅读页不再需要知道章节正文是怎么装载的。
     */
    private fun syncReadingState() {
        val session = reading.state.value
        _uiState.update {
            it.copy(
                chapters = session.chapters,
                currentChapterIndex = session.chapterIndex,
                currentChapterContent = session.content,
                fullTextLength = session.totalChars,
                cleanTypography = session.cleanTypography,
                fileName = session.title.ifEmpty { it.fileName }
            )
        }
    }

    /**
     * 跳转到指定章节：优先命中预热缓存，未命中按需流式装载
     */
    fun goToChapter(chapterIndex: Int, targetCharOffset: Int = -1) {
        val state = _uiState.value
        val uri = state.currentUri ?: return
        if (chapterIndex !in state.chapters.indices) return

        viewModelScope.launch {
            if (!reading.goToChapter(chapterIndex, targetCharOffset)) {
                // 正文读取失败不跳章：留在当前页，把原因透出给 UI
                _uiState.update { it.copy(errorMessage = "读取章节正文失败") }
                return@launch
            }
            syncReadingState()
            val offset = reading.currentOffset
            _uiState.update { it.copy(screen = Screen.Reader(offset, chapterIndex)) }

            savePositionJob?.cancel()
            savePositionJob = viewModelScope.launch(Dispatchers.IO) {
                val title = reading.state.value.content?.title.orEmpty()
                DataStoreManager.saveReadingPosition(appCtx, uri, offset, reading.totalChars, title)
            }
        }
    }

    /**
     * 书内全文搜索：编排（有没有连续流 / 按章还是按切片 / 进度与取消语义）全部归
     * [BookSearchController]；本类只做两件事——把阅读会话适配成可搜对象、留住最后一次搜索词
     */
    fun searchInBook(query: String) {
        search.search(if (reading.isOpen()) reading.asSearchableBook() else null, query)
        // 搜索词与结果同生命周期：返回后再进搜索页仍可微调关键词重查
        if (search.state.value.isSearching) _uiState.update { it.copy(bookSearchQuery = query.trim()) }
    }

    /** 停止全书扫描（离开搜索页 / 跳转命中时调用）：已扫到的命中保留，转圈态立即复位 */
    fun cancelSearch() = search.cancel()

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
        reading.updateOffset(offset)
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
        val offset = reading.currentOffset
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
     * 开启局域网 Wi-Fi 传书服务
     */
    fun openWifiTransfer() {
        // 先切页，再后台绑定：端口 bind（最多尝试 6 个候选端口）与网卡枚举都是阻塞 IO，
        // open() 因此是阻塞调用，留在主线程会让 onClick 直接 ANR
        _uiState.update { it.copy(screen = Screen.WifiTransfer) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { transferController.open() }
        }
    }

    /**
     * 关闭局域网 Wi-Fi 传书服务
     */
    fun closeWifiTransfer() {
        viewModelScope.launch(Dispatchers.IO) {
            // stop() 要关监听 socket，同样不能留在主线程
            transferController.close()
            val shelf = DataStoreManager.loadBookShelf(appCtx)
            _uiState.update {
                it.copy(
                    screen = Screen.Home,
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

    /** 书内搜索关键词（与搜索结果同生命周期，返回搜索页可微调重查） */
    fun setBookSearchQuery(query: String) {
        _uiState.update { it.copy(bookSearchQuery = query) }
    }

    /**
     * 保存当前阅读位置为书签
     */
    fun addBookmark() {
        val state = _uiState.value
        val uri = state.currentUri ?: return
        val chap = state.currentChapterContent ?: return
        val currentOffset = reading.currentOffset
        val body = chap.formattedBody
        // 正文坐标必须经排版映射换算：formattedBody 补过段首缩进并重排换行，
        // 直接相减会落在错位的段落里，书签摘录与持久化位置一起漂移
        val relOffset = ChapterOffsetMapper.rawToBody(
            rawIndex = currentOffset - chap.startCharOffset,
            bodyParaStarts = chap.bodyParagraphStarts,
            rawParaStarts = chap.rawParagraphStarts,
            bodyLength = body.length,
            indentChars = chap.paragraphIndentChars
        )
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
                reading.close()
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
        search.reset()
        reading.close()
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

    fun getCurrentReadingOffset(): Int = reading.currentOffset

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
            val persistBuffer = ReadingSecondsBuffer()
            try {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                val state = _uiState.value
                if (state.screen is Screen.Reader) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastActiveTime <= 60_000L) {
                        val todayKey = ReadingStats.dateKeyOf(System.currentTimeMillis())
                        // 每 10s 结算一次状态，落盘按 60s 攒批：DataStore 每次 edit
                        // 都会重写整份 preferences（含书架 JSON），不能按秒写
                        val flushSeconds = persistBuffer.record(10L)
                        if (flushSeconds > 0L) {
                            DataStoreManager.addReadingSeconds(appCtx, flushSeconds, todayKey)
                        }

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
            } finally {
                // Job 被取消（退出阅读/onCleared）时冲刷余量，不足 60s 的碎片不丢
                val remainder = persistBuffer.drain()
                if (remainder > 0L) {
                    withContext(NonCancellable) {
                        DataStoreManager.addReadingSeconds(
                            appCtx,
                            remainder,
                            ReadingStats.dateKeyOf(System.currentTimeMillis())
                        )
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
    // 待确认的恢复备份原文：SAF 选完文件先解析预览，用户在确认浮层点头后才覆盖
    @Volatile
    private var pendingRestoreJson: String? = null

    /**
     * 解析备份供确认浮层预览：合法则记录原文并返回书目数，非法返回 null（文件不是有效备份）
     */
    fun previewBackupRestore(json: String): Int? {
        val payload = when (val decoded = BackupCodec.decodeResult(json)) {
            is BackupCodec.DecodeResult.Success -> decoded.payload
            is BackupCodec.DecodeResult.Failure -> {
                // 失败原因逐码透出：结构损坏与「类型/版本不符」是两种完全不同的误操作
                _uiState.update { it.copy(errorMessage = "恢复失败：${decoded.message}") }
                return null
            }
        }
        pendingRestoreJson = json
        _uiState.update { it.copy(pendingRestoreCount = payload.shelf.size) }
        return payload.shelf.size
    }

    /** 取消恢复：丢弃待确认备份 */
    fun cancelBackupRestore() {
        pendingRestoreJson = null
        _uiState.update { it.copy(pendingRestoreCount = null) }
    }

    /** 确认恢复：走原 restoreBackup 覆盖路径 */
    suspend fun confirmBackupRestore() {
        val json = pendingRestoreJson
        pendingRestoreJson = null
        _uiState.update { it.copy(pendingRestoreCount = null) }
        if (json != null) {
            restoreBackup(json)
        }
    }

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
                lineSpacing = config.lineSpacing,
                letterSpacing = config.letterSpacing,
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
     * 设置主题模式（0: 浅色, 1: 深色；历史值经 [ThemeMode.fromValue] 归一）
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

        if (_uiState.value.currentUri == null) return

        // 快速连点时取消上一次切换：只有最新排版态的正文允许落回 UI，避免旧协程晚归覆盖
        cleanTypographyJob?.cancel()
        cleanTypographyJob = viewModelScope.launch {
            if (!reading.setCleanTypography(enabled)) return@launch
            if (isActive && _uiState.value.cleanTypography == enabled) syncReadingState()
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

    /**
     * 生命周期收口：ViewModel 销毁时必须停掉派生 Job 与服务端，
     * 否则 Wi-Fi 传书 socket 与阅读计时协程会越过阅读页继续存活
     */
    override fun onCleared() {
        loadJob?.cancel()
        reading.close()
        cleanTypographyJob?.cancel()
        search.cancel()
        savePositionJob?.cancel()
        readingTimerJob?.cancel()
        transferController.close()
        RotaryHapticManager.shutdown()
        super.onCleared()
    }
}
