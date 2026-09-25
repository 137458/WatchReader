package com.watchreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 页面路由状态
 */
sealed class Screen {
    object Home : Screen()
    object Loading : Screen()
    data class Reader(val charOffset: Int = 0, val chapterIndex: Int = 0) : Screen()
    object ChapterList : Screen()
    object Menu : Screen()
    object Rsvp : Screen()
    object WifiTransfer : Screen()
}

/**
 * MainActivity — 遵循 Android / ColorOS Watch 官方规范的入口 Activity
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    // SAF 文件选择器
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.loadFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 ViewModel 状态（从 DataStore 异步读取书架、字号、深色模式、亮度、滚速）
        viewModel.init()

        // 异步预热 OPPO 官方 Linearmotor 线性马达引擎
        lifecycleScope.launch(Dispatchers.Default) {
            RotaryHapticManager.initOplusLinearmotor(applicationContext)
        }

        // 全屏沉浸式
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val colorScheme = when (ThemeMode.fromValue(uiState.themeMode)) {
                ThemeMode.RED_NIGHT -> WatchRedNightColorScheme
                ThemeMode.DARK -> WatchDarkColorScheme
                ThemeMode.PARCHMENT -> WatchColorScheme
            }

            // 动态同步 Window 底层 DecorView 背景色与硬件独立屏幕亮度
            SideEffect {
                BrightnessManager.applyToWindow(this@MainActivity, uiState.appBrightness)
                window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            }

            MaterialTheme(
                colorScheme = colorScheme,
                typography = WatchTypography
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent // 移除重复不透明底色，交由底色层 window.decorView 承载，降低 Overdraw
                    ) {
                        AppContent(uiState)
                    }

                    // 极暗纯黑 Alpha 硬件加速遮罩（仅当亮度低于 12% 时激活，不拦截手势）
                    val overlayAlpha = BrightnessManager.calculateDarkOverlayAlpha(uiState.appBrightness)
                    if (overlayAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = overlayAlpha))
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.flushReadingPosition()
    }

    /**
     * 顶层分发事件：优先处理 RSVP / 全局表冠转动，再分发给原生 View 树（ScrollView / ListView）
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            val delta = CrownScrollHelper.extractCrownDelta(event)
            if (abs(delta) > 0.001f) {
                if (viewModel.handleRotaryScroll(delta)) {
                    return true
                }
            }
        }
        if (super.dispatchGenericMotionEvent(event)) {
            return true
        }
        val focus = currentFocus
        if (focus != null && focus.dispatchGenericMotionEvent(event)) {
            return true
        }
        // 表冠焦点防死锁兜底路由：当 Compose 悬浮组件夺走焦点导致原生 View 失去焦点时，直接寻址并向内部活跃列表下发
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            val root = window.decorView as? ViewGroup
            if (root != null && dispatchRotaryToActiveScrollView(root, event)) {
                return true
            }
        }
        return false
    }

    private fun dispatchRotaryToActiveScrollView(viewGroup: ViewGroup, event: MotionEvent): Boolean {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.isShown && (child is android.widget.ScrollView || child is android.widget.ListView)) {
                if (child.dispatchGenericMotionEvent(event)) {
                    return true
                }
            }
            if (child is ViewGroup && child.isShown) {
                if (dispatchRotaryToActiveScrollView(child, event)) {
                    return true
                }
            }
        }
        return false
    }

    @Composable
    private fun AppContent(uiState: ReaderUiState) {
        // 页面级切换过渡：短促淡入 + 微缩放归位，键控于屏幕类型（阅读中参数变化不触发过渡）
        AnimatedContent(
            targetState = uiState.screen,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(WatchMotion.DUR_FADE, easing = WatchMotion.EnterEasing)) +
                    scaleIn(
                        initialScale = 0.985f,
                        animationSpec = tween(WatchMotion.DUR_FADE, easing = WatchMotion.EnterEasing)
                    )) togetherWith
                    fadeOut(tween(WatchMotion.DUR_FADE_OUT, easing = WatchMotion.ExitEasing))
            },
            label = "screen-transition"
        ) { current ->
            when (current) {
                is Screen.Home -> BookshelfScreen(
                    bookshelf = uiState.bookshelf,
                    searchQuery = uiState.searchQuery,
                    fontSize = uiState.fontSize,
                    isDarkMode = uiState.isDarkMode,
                    onOpenFile = {
                        openFileLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*"))
                    },
                    onOpenBook = { book -> viewModel.openFromShelf(book) },
                    onDeleteBook = { book -> viewModel.deleteFromShelf(book) },
                    onTogglePin = { book -> viewModel.toggleBookPin(book.uriString) },
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    onOpenWifiTransfer = { viewModel.openWifiTransfer() },
                    onFontSizeChange = { viewModel.updateFontSize(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    errorMessage = uiState.errorMessage
                )

                is Screen.Loading -> LoadingScreen()

                is Screen.Reader -> ReaderScreen(
                    chapterContent = uiState.currentChapterContent,
                    initialCharOffset = current.charOffset,
                    totalChapters = uiState.chapters.size,
                    fullTextLength = uiState.fullTextLength,
                    onCharOffsetChange = { offset ->
                        viewModel.updateCharOffset(offset)
                    },
                    onNextChapter = { viewModel.goToNextChapter() },
                    onPrevChapter = { viewModel.goToPrevChapter() },
                    onLongPress = { viewModel.navigateTo(Screen.Menu) },
                    onBack = { viewModel.handleBack() },
                    fontSize = uiState.fontSize,
                    autoScrollSpeed = uiState.autoScrollSpeed,
                    isAutoScrolling = uiState.isAutoScrolling,
                    onAutoScrollToggle = { viewModel.setAutoScrolling(!uiState.isAutoScrolling) },
                    onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                    appBrightness = uiState.appBrightness,
                    onBrightnessChange = { viewModel.updateAppBrightness(it) },
                    tapPageArea = uiState.tapPageArea,
                    fontType = uiState.fontType,
                    chapters = uiState.chapters,
                    currentChapterIndex = uiState.currentChapterIndex,
                    onSeekChapter = { index -> viewModel.goToChapter(index) },
                    onFlushReadingPosition = { viewModel.flushReadingPosition() }
                )

                is Screen.Menu -> MenuScreen(
                    chapterTitle = uiState.currentChapterContent?.title ?: "",
                    fontSize = uiState.fontSize,
                    autoScrollSpeed = uiState.autoScrollSpeed,
                    isAutoScrolling = uiState.isAutoScrolling,
                    appBrightness = uiState.appBrightness,
                    hasPrevChapter = uiState.currentChapterContent?.hasPrevChapter == true,
                    hasNextChapter = uiState.currentChapterContent?.hasNextChapter == true,
                    onPrevChapter = {
                        viewModel.goToPrevChapter()
                        viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                    },
                    onNextChapter = {
                        viewModel.goToNextChapter()
                        viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                    },
                    onFontSizeChange = { viewModel.updateFontSize(it) },
                    onToggleAutoScroll = {
                        val nextScrollState = !uiState.isAutoScrolling
                        viewModel.setAutoScrolling(nextScrollState)
                        if (nextScrollState) {
                            viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                        }
                    },
                    onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                    onBrightnessChange = { viewModel.updateAppBrightness(it) },
                    onAddBookmark = {
                        viewModel.addBookmark()
                        viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                    },
                    onOpenRsvp = { viewModel.openRsvp() },
                    onChapterListClick = { viewModel.navigateTo(Screen.ChapterList) },
                    onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) },
                    onHome = { viewModel.closeBook() },
                    themeMode = uiState.themeMode,
                    onThemeModeChange = { viewModel.setThemeMode(it) },
                    tapPageArea = uiState.tapPageArea,
                    onTapPageAreaChange = { viewModel.setTapPageArea(it) },
                    cleanTypography = uiState.cleanTypography,
                    onCleanTypographyChange = { viewModel.setCleanTypography(it) },
                    fontType = uiState.fontType,
                    onFontTypeChange = { viewModel.setFontType(it) },
                    readDurationSec = uiState.readDurationSec
                )

                is Screen.ChapterList -> ChapterListScreen(
                    chapters = uiState.chapters,
                    currentChapterIndex = uiState.currentChapterIndex,
                    bookmarks = uiState.bookmarks,
                    onChapterClick = { index ->
                        viewModel.goToChapter(index)
                    },
                    onBookmarkClick = { bookmark ->
                        viewModel.jumpToBookmark(bookmark)
                    },
                    onDeleteBookmark = { bookmark ->
                        viewModel.removeBookmark(bookmark.id)
                    },
                    onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) }
                )

                is Screen.Rsvp -> RsvpScreen(
                    chapterContent = uiState.currentChapterContent,
                    initialCharOffset = viewModel.getCurrentReadingOffset(),
                    wordsPerMinute = uiState.rsvpSpeed,
                    onCharOffsetChange = { offset ->
                        viewModel.updateCharOffset(offset)
                    },
                    onNextChapter = { viewModel.goToNextChapter() },
                    onSpeedChange = { viewModel.updateRsvpSpeed(it) },
                    onBack = { viewModel.handleBack() }
                )

                is Screen.WifiTransfer -> WifiTransferScreen(
                    ipAddress = uiState.wifiIpAddress,
                    port = uiState.wifiPort,
                    uploadedCount = uiState.wifiUploadedCount,
                    isServerRunning = uiState.isWifiServerRunning,
                    isTransferring = uiState.isTransferring,
                    transferProgress = uiState.transferProgress,
                    transferFileName = uiState.transferFileName,
                    onToggleServer = {
                        if (uiState.isWifiServerRunning) viewModel.closeWifiTransfer() else viewModel.openWifiTransfer()
                    },
                    onBack = { viewModel.closeWifiTransfer() }
                )
            }
        }
    }
}

// ═════════════════════════════════════
//  书架主页（Compose 腕上设计系统）
// ═════════════════════════════════════

/**
 * 书架主页 — 圆屏黄金安全区排版 + 发丝描边层次 + 错峰入场
 */
@Composable
fun BookshelfScreen(
    bookshelf: List<BookItem>,
    searchQuery: String = "",
    fontSize: Int,
    isDarkMode: Boolean,
    onOpenFile: () -> Unit,
    onOpenBook: (BookItem) -> Unit,
    onDeleteBook: (BookItem) -> Unit,
    onTogglePin: (BookItem) -> Unit = {},
    onSearchChange: (String) -> Unit = {},
    onOpenWifiTransfer: () -> Unit = {},
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit,
    errorMessage: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val latestBook = bookshelf.maxByOrNull { it.lastReadTime }
    val filteredBooks = bookshelf
        .filter { it.title.contains(searchQuery, ignoreCase = true) }
        .sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })

    val tick = rememberTickHaptic()

    // 两段式删除确认：首按进入待确认，3.2s 无操作自动回滚
    var pendingDeleteUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDeleteUri) {
        if (pendingDeleteUri != null) {
            delay(3200L)
            pendingDeleteUri = null
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 42.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 头部：栏目标签 + 主标题 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(0),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                SectionLabel("正在阅读", color = colors.primary)
                Text(
                    text = "我的书架",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground
                )
            }
            Text(
                text = "${bookshelf.size} 本",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }

        // ── 继续阅读主卡：主色实底 + 弹簧进度轨 ──
        if (latestBook != null) {
            val heroInteraction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .staggeredEnter(1)
                    .pressScale(heroInteraction, pressedScale = 0.975f)
                    .fillMaxWidth()
                    .clip(WatchShapes.Card)
                    .background(colors.primary)
                    .clickable(interactionSource = heroInteraction, indication = null) {
                        tick()
                        onOpenBook(latestBook)
                    }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                SectionLabel("继续阅读", color = colors.onPrimary.copy(alpha = 0.72f))
                Text(
                    text = latestBook.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    color = colors.onPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = latestBook.lastChapterTitle.ifBlank { "从上次阅读位置继续" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = colors.onPrimary.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressTrack(
                        progress = (latestBook.progressPercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        trackColor = colors.onPrimary.copy(alpha = 0.25f),
                        fillColor = colors.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${latestBook.progressPercent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onPrimary
                    )
                }
            }
        } else {
            SurfaceCard(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth()
                    .padding(0.dp),
                containerColor = colors.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("建立你的第一座书架", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text(
                        "导入 TXT 或 EPUB，阅读进度会自动保存。",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        // ── 双入口操作卡 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BookshelfAction(
                modifier = Modifier.weight(1f),
                title = "导入书籍",
                value = "TXT · EPUB",
                onClick = onOpenFile
            )
            BookshelfAction(
                modifier = Modifier.weight(1f),
                title = "无线传书",
                value = "扫码直连",
                onClick = onOpenWifiTransfer
            )
        }

        AnimatedVisibility(
            visible = !errorMessage.isNullOrBlank(),
            enter = fadeIn(tween(WatchMotion.DUR_FADE)) + androidx.compose.animation.expandVertically(),
            exit = fadeOut(tween(WatchMotion.DUR_FADE_OUT)) + androidx.compose.animation.shrinkVertically()
        ) {
            Text(
                text = errorMessage.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.error
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(3),
            singleLine = true,
            placeholder = { Text("搜索书名", fontSize = 12.sp) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            shape = WatchShapes.Row
        )

        // ── 书库列表 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("书库", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            Text(
                text = if (searchQuery.isBlank()) "最近阅读优先" else "${filteredBooks.size} 个结果",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }

        if (filteredBooks.isEmpty()) {
            SurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(5),
                containerColor = colors.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (bookshelf.isEmpty()) "书架还是空的" else "没有匹配的书籍",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface
                    )
                    Text(
                        text = if (bookshelf.isEmpty()) "从上方导入或扫码传入第一本书" else "换个书名关键词试试",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        } else {
            filteredBooks.forEachIndexed { index, book ->
                BookshelfBookRow(
                    book = book,
                    isPendingDelete = pendingDeleteUri == book.uriString,
                    onOpen = {
                        tick()
                        onOpenBook(book)
                    },
                    onTogglePin = { onTogglePin(book) },
                    onRequestDelete = { pendingDeleteUri = book.uriString },
                    onConfirmDelete = {
                        pendingDeleteUri = null
                        onDeleteBook(book)
                    },
                    enterOrder = 5 + index
                )
            }
        }

        // ── 底部显示调节行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(6 + filteredBooks.size.coerceAtMost(6)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("显示")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "字号 $fontSize",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface
                )
                PillButton("−", verticalPadding = 5.dp) { onFontSizeChange(fontSize - 1) }
                PillButton("+", verticalPadding = 5.dp) { onFontSizeChange(fontSize + 1) }
                PillButton(
                    if (isDarkMode) "夜" else "日",
                    verticalPadding = 5.dp
                ) { onToggleDarkMode() }
            }
        }

        // 表冠滚动焦点代理（原生 View 微型焦点锚点，保持书架表冠滚动手感与齿轮微振）
        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { context ->
                ScrollView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnGenericMotionListener { view, event ->
                        if (CrownScrollHelper.isCrownScrollEvent(event)) {
                            CrownScrollHelper.dispatchScroll(
                                CrownScrollHelper.extractCrownDelta(event),
                                scrollState,
                                context,
                                view
                            )
                            true
                        } else {
                            false
                        }
                    }
                    post { requestFocus() }
                }
            }
        )
    }
}

@Composable
private fun BookshelfAction(
    modifier: Modifier,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .pressScale(interaction)
            .clip(WatchShapes.Row)
            .background(colors.surfaceVariant)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            Text(value, style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun BookshelfBookRow(
    book: BookItem,
    isPendingDelete: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    enterOrder: Int
) {
    val colors = MaterialTheme.colorScheme
    val isEpub = book.uriString.endsWith(".epub", ignoreCase = true) || book.title.endsWith(".epub", ignoreCase = true)
    val displayTitle = EpubParser.cleanBookTitle(book.title)

    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(enterOrder),
        shape = WatchShapes.Row
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextBadge(
                    text = if (isEpub) "EPUB" else "TXT",
                    color = if (isEpub) colors.primary else colors.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${book.progressPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
            }
            Text(
                text = book.lastChapterTitle.ifBlank { "尚未开始" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressTrack(
                    progress = (book.progressPercent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    height = 3.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (book.isPinned) "已置顶" else "置顶",
                    modifier = Modifier.clickable(onClick = onTogglePin),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isPendingDelete) "确认删除？" else "删除",
                    modifier = Modifier.clickable { if (isPendingDelete) onConfirmDelete() else onRequestDelete() },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isPendingDelete) FontWeight.Bold else FontWeight.Normal),
                    color = if (isPendingDelete) colors.error else colors.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

/**
 * 加载中界面：旋转弧环 + 呼吸文字
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingIndicator(size = 30.dp, strokeWidth = 2.6.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "正在打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
