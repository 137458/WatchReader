package com.watchreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

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

    // 备份导出（SAF 建文件）与恢复（SAF 选文件）
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = viewModel.exportBackup()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: throw IllegalStateException("无法写入所选文件")
                viewModel.notifyBackupExported(true)
            } catch (_: Exception) {
                viewModel.notifyBackupExported(false)
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (!viewModel.restoreBackup(json)) {
                    viewModel.notifyBackupRestoreFailed()
                }
            } catch (_: Exception) {
                viewModel.notifyBackupRestoreFailed()
            }
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

        // 读取系统表冠滚动系数（与 HeyLauncher 同源），保证灵敏度与系统应用一致
        CrownScrollHelper.ensureSystemScrollFactor(applicationContext)

        // 全屏沉浸式
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val colors = watchColorsOf(ThemeMode.fromValue(uiState.themeMode))

            // 动态同步 Window 底层 DecorView 背景色与硬件独立屏幕亮度。
            // 底色必须带变更守卫：无守卫时每次重组（搜索逐键 / 亮度步进 / 阅读时长 tick）
            // 都会令 DecorView 全窗口失效重绘，抵消全部逐帧绘制优化
            val lastDecorBackground = remember { intArrayOf(Int.MIN_VALUE) }
            SideEffect {
                BrightnessManager.applyToWindow(this@MainActivity, uiState.appBrightness)
                val bgArgb = colors.background.toArgb()
                if (bgArgb != lastDecorBackground[0]) {
                    lastDecorBackground[0] = bgArgb
                    window.decorView.setBackgroundColor(bgArgb)
                }
            }

            // 主题根节点：应用自持 ThemeMode（DataStore 持久化），显式注入色板与排版，
            // 不走 ThemeController 的系统明暗 / 动态取色管线
            MiuixTheme(
                colors = colors,
                textStyles = WatchTextStyles
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppContent(uiState)

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
     * 顶层分发事件：优先处理 RSVP 调速 / 页面注册的表冠滚动目标，再分发给原生 View 树
     * （阅读页 / 目录页的原生 ScrollView / ListView 保持自身监听路径不变）
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            val delta = CrownScrollHelper.extractCrownDelta(event)
            if (abs(delta) > 0.001f) {
                if (viewModel.handleRotaryScroll(delta)) {
                    return true
                }
                if (CrownScrollTargetRegistry.active?.onCrownDelta(delta) == true) {
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
        val screen = uiState.screen

        // 阅读页常驻层：呼出菜单 / 目录 / 速读时不再拆解阅读页 ——
        // 阅读页是"单 TextLayout 承载整章文本 + 6 个复用原生 View"，销毁重建一次就要
        // 整章重新断行排版并重建视图树，这正是"返回阅读"顿挫的根因。覆盖页改为叠加其上，
        // 阅读页原生视图转 INVISIBLE 退出绘制（View 树跳过不可见子节点，隐藏期零绘制），
        // 返回时排版、滚动位置、表冠管线与自动滚屏引擎原样保留。
        val readerAlive = uiState.currentUri != null && (
            screen is Screen.Reader || screen is Screen.Menu ||
                screen is Screen.ChapterList || screen is Screen.Rsvp || screen is Screen.Search
            )

        // 离开阅读页后屏幕状态不再携带阅读定位参数，故保留最后一次的阅读参数：
        // 避免常驻阅读页的 initialCharOffset 抖动引发其内部偏移状态重置
        val readerArgs = remember { mutableStateOf(Screen.Reader()) }
        if (screen is Screen.Reader) readerArgs.value = screen

        Box(modifier = Modifier.fillMaxSize()) {
            if (readerAlive) {
                ReaderScreen(
                    chapterContent = uiState.currentChapterContent,
                    initialCharOffset = readerArgs.value.charOffset,
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
                    tapPageArea = uiState.tapPageArea,
                    fontType = uiState.fontType,
                    chapters = uiState.chapters,
                    currentChapterIndex = uiState.currentChapterIndex,
                    onSeekChapter = { index -> viewModel.goToChapter(index) },
                    onFlushReadingPosition = { viewModel.flushReadingPosition() },
                    covered = screen !is Screen.Reader
                )
            }

            ScreenSlot(uiState = uiState, screen = screen)
        }
    }

    /**
     * 页面内容槽位。阅读页槽位只占一个透明空 Box（真实阅读页由常驻层承载），
     * 使页面切换不再销毁阅读页；其余页面按其自身入场节奏淡入。
     */
    @Composable
    private fun ScreenSlot(uiState: ReaderUiState, screen: Screen) {
        // 页面级切换过渡。阅读页槽位为空 Box，切换零成本；书架 / 菜单自带错峰入场
        // （staggeredEnter 逐卡淡入上浮），父层淡入只是与其重叠的重复劳动，一并瞬时切换。
        // 其余页面保留纯 alpha 淡入淡出（不用 scale：缩放会逐帧重建全屏离屏缓冲并重采样）
        AnimatedContent(
            targetState = screen,
            contentKey = { it::class },
            transitionSpec = {
                val instantSwitch = initialState is Screen.Reader ||
                    targetState is Screen.Reader ||
                    targetState is Screen.Menu ||
                    targetState is Screen.Home
                if (instantSwitch) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(WatchMotion.DUR_FADE, easing = WatchMotion.EnterEasing)) togetherWith
                        fadeOut(tween(WatchMotion.DUR_FADE_OUT, easing = WatchMotion.ExitEasing))
                }
            },
            label = "screen-transition"
        ) { current ->
            when (current) {
                is Screen.Home -> BookshelfScreen(
                    bookshelf = uiState.bookshelf,
                    searchQuery = uiState.searchQuery,
                    fontSize = uiState.fontSize,
                    isDarkMode = ThemeMode.fromValue(uiState.themeMode).isDark,
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
                    errorMessage = uiState.errorMessage,
                    infoMessage = uiState.infoMessage,
                    onInfoMessageShown = { viewModel.clearInfoMessage() },
                    onBackup = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                            .format(java.util.Date())
                        exportBackupLauncher.launch("watchreader_backup_$stamp.json")
                    },
                    onRestore = {
                        importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                )

                is Screen.Loading -> LoadingScreen()

                // 阅读页由常驻层承载，此槽位保持透明空占位（零绘制）
                is Screen.Reader -> Box(modifier = Modifier.fillMaxSize())

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
                        viewModel.returnToReader()
                    },
                    onNextChapter = {
                        viewModel.goToNextChapter()
                        viewModel.returnToReader()
                    },
                    onFontSizeChange = { viewModel.updateFontSize(it) },
                    onToggleAutoScroll = {
                        val nextScrollState = !uiState.isAutoScrolling
                        viewModel.setAutoScrolling(nextScrollState)
                        if (nextScrollState) {
                            viewModel.returnToReader()
                        }
                    },
                    onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                    onBrightnessChange = { viewModel.updateAppBrightness(it) },
                    onAddBookmark = {
                        viewModel.addBookmark()
                        viewModel.returnToReader()
                    },
                    onOpenRsvp = { viewModel.openRsvp() },
                    onChapterListClick = { viewModel.navigateTo(Screen.ChapterList) },
                    onOpenSearch = { viewModel.navigateTo(Screen.Search) },
                    onBack = { viewModel.returnToReader() },
                    onHome = { viewModel.closeBook() },
                    themeMode = uiState.themeMode,
                    onThemeModeChange = { viewModel.setThemeMode(it) },
                    tapPageArea = uiState.tapPageArea,
                    onTapPageAreaChange = { viewModel.setTapPageArea(it) },
                    cleanTypography = uiState.cleanTypography,
                    onCleanTypographyChange = { viewModel.setCleanTypography(it) },
                    fontType = uiState.fontType,
                    onFontTypeChange = { viewModel.setFontType(it) },
                    readDurationSec = uiState.readDurationSec,
                    readDays = uiState.readDays,
                    readGoalMinutes = uiState.readGoalMinutes,
                    finishedCount = uiState.bookshelf.count { it.finished },
                    onReadGoalChange = { viewModel.setReadGoalMinutes(it) },
                    lineSpacing = uiState.lineSpacing,
                    letterSpacing = uiState.letterSpacing,
                    onLineSpacingChange = { viewModel.setLineSpacing(it) },
                    onLetterSpacingChange = { viewModel.setLetterSpacing(it) }
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
                    onBack = { viewModel.returnToReader() }
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

                is Screen.Search -> SearchScreen(
                    chapters = uiState.chapters,
                    searchResults = uiState.searchResults,
                    isSearching = uiState.isSearching,
                    scannedChapters = uiState.searchScannedChapters,
                    totalChapters = uiState.searchTotalChapters,
                    onSearch = { viewModel.searchInBook(it) },
                    onHitClick = { viewModel.jumpToSearchHit(it) },
                    onBack = { viewModel.handleBack() }
                )

                is Screen.WifiTransfer -> WifiTransferScreen(
                    ipAddress = uiState.wifiIpAddress,
                    port = uiState.wifiPort,
                    accessToken = uiState.wifiToken,
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
