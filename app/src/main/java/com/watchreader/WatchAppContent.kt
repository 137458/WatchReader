package com.watchreader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * App 内容壳：常驻阅读层 + 页面槽位的装配
 *
 * Activity 只负责 setContent、状态收集、主题根节点与 Window 级副作用；本文件承载
 * 「阅读页常驻 vs 页面切换」的全部排版决定。删除本文件会让 240 行页面装配退回 Activity，
 * 而常驻层规则（readerAlive / readerArgs 保留）与过渡口径（contentKey、nativeSurface 判定）
 * 都没有第二处可读。
 */
@Composable
internal fun WatchAppContent(
    uiState: ReaderUiState,
    transferState: TransferUiState,
    searchState: SearchState,
    viewModel: ReaderViewModel,
    requests: SafLaunchRequests,
    onExitApp: () -> Unit
) {
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

    // 常驻阅读页的输入：状态与回调各收成一个整体对象。
    // 状态相等时 Compose 整页跳过（阅读时长 tick、传书进度不再重排整章）；
    // 回调用 remember 固定身份，父级重组不会让它失效。
    val readerActions = remember(viewModel) {
        ReaderPaneActions(
            onCharOffsetChange = { viewModel.updateCharOffset(it) },
            onNextChapter = { viewModel.goToNextChapter() },
            onPrevChapter = { viewModel.goToPrevChapter() },
            onLongPress = { viewModel.navigateTo(Screen.Menu) },
            onBack = { viewModel.handleBack() },
            onAutoScrollToggle = {
                viewModel.setAutoScrolling(!viewModel.uiState.value.isAutoScrolling)
            },
            onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
            onSeekChapter = { index -> viewModel.goToChapter(index) },
            onFlushReadingPosition = { viewModel.flushReadingPosition() }
        )
    }

    // 行距 / 字距曾长期只写 _uiState 与 DataStore、从未下传阅读页，
    // 菜单里的两档设置对正文永久无效；这里必须显式带上
    val readerState = ReaderPaneState(
        chapterContent = uiState.currentChapterContent,
        initialCharOffset = readerArgs.value.charOffset,
        totalChapters = uiState.chapters.size,
        fullTextLength = uiState.fullTextLength,
        fontSize = uiState.fontSize,
        autoScrollSpeed = uiState.autoScrollSpeed,
        isAutoScrolling = uiState.isAutoScrolling,
        tapPageArea = uiState.tapPageArea,
        fontType = uiState.fontType,
        lineSpacing = uiState.lineSpacing,
        letterSpacing = uiState.letterSpacing,
        chapters = uiState.chapters,
        currentChapterIndex = uiState.currentChapterIndex,
        covered = screen !is Screen.Reader
    )

    // 全屏阻塞浮层（当前为备份恢复确认）在状态层是唯一事实来源：
    // 页面壳据此关闭右滑返回，表冠据此挂起输入。放在页面外层统一provide，
    // 避免书架/菜单/目录各自判断"我现在被遮住了吗"。
    val blockingOverlay = uiState.pendingRestoreCount != null
    DisposableEffect(blockingOverlay) {
        if (blockingOverlay) CrownScrollTargetRegistry.suspendInput()
        onDispose {
            if (blockingOverlay) CrownScrollTargetRegistry.resumeInput()
        }
    }

    CompositionLocalProvider(LocalBlockingOverlay provides blockingOverlay) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (readerAlive) {
                ReaderScreen(state = readerState, actions = readerActions)
            }

            ScreenSlot(
                uiState = uiState,
                screen = screen,
                transferState = transferState,
                searchState = searchState,
                viewModel = viewModel,
                requests = requests,
                onExitApp = onExitApp
            )
        }
    }
}

/**
 * 页面内容槽位。阅读页槽位只占一个透明空 Box（真实阅读页由常驻层承载），
 * 使页面切换不再销毁阅读页；其余页面按其自身入场节奏淡入。
 */
@Composable
private fun ScreenSlot(
    uiState: ReaderUiState,
    screen: Screen,
    transferState: TransferUiState,
    searchState: SearchState,
    viewModel: ReaderViewModel,
    requests: SafLaunchRequests,
    onExitApp: () -> Unit
) {
    // 页面级切换过渡。只有两类边界瞬时切换，其余页面保留纯 alpha 淡入淡出
    // （不用 scale：缩放会逐帧重建全屏离屏缓冲并重采样）：
    // 1. 目标页自带入场动效 —— 书架 / 菜单的逐卡 staggeredEnter，父层淡入与其重叠；
    // 2. 参与过渡的页面主体是原生视图 —— 阅读页正文常驻在原生层、目录页主体是原生 ListView，
    //    Compose 的 alpha 过渡对原生视图无效，淡入只会让周边 chrome 单独闪烁。
    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = {
            val targetSelfAnimated = targetState is Screen.Menu || targetState is Screen.Home
            val nativeSurface = initialState is Screen.Reader || targetState is Screen.Reader ||
                initialState is Screen.ChapterList || targetState is Screen.ChapterList
            if (targetSelfAnimated || nativeSurface) {
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
                onOpenFile = { requests.openDocument() },
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
                onBackup = { requests.exportBackup() },
                onRestore = { requests.importBackup() },
                onExitApp = onExitApp
            )

            is Screen.Loading -> LoadingScreen(onBack = { viewModel.handleBack() })

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
                onOpenBookmarks = { viewModel.navigateTo(Screen.ChapterList(bookmarkTab = true)) },
                onOpenRsvp = { viewModel.openRsvp() },
                onChapterListClick = { viewModel.navigateTo(Screen.ChapterList()) },
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
                openBookmarkTab = current.bookmarkTab,
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
                onAddBookmark = { viewModel.addBookmark() },
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
                state = searchState,
                query = uiState.bookSearchQuery,
                onQueryChange = { viewModel.setBookSearchQuery(it) },
                onSearch = { viewModel.searchInBook(it) },
                onHitClick = { viewModel.jumpToSearchHit(it) },
                onBack = { viewModel.handleBack() }
            )

            is Screen.WifiTransfer -> WifiTransferScreen(
                state = transferState,
                onToggleServer = {
                    if (transferState.isRunning) viewModel.closeWifiTransfer() else viewModel.openWifiTransfer()
                },
                onBack = { viewModel.closeWifiTransfer() }
            )
        }
    }
}
