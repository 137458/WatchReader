package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator as WearCircularProgressIndicator
import androidx.wear.compose.material3.LinearProgressIndicator as WearLinearProgressIndicator
import androidx.wear.compose.material3.ProgressIndicatorDefaults as WearProgressIndicatorDefaults
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TextButton as WearTextButton
import androidx.wear.compose.material3.TextButtonDefaults as WearTextButtonDefaults
import androidx.wear.compose.material3.TitleCard as WearTitleCard
import kotlinx.coroutines.delay

// ═════════════════════════════════════
//  书架主页（Wear Compose Material 3）
// ═════════════════════════════════════

/**
 * 书架主页 — 圆屏黄金安全区排版 + Wear M3 官方组件 + 表冠滚动
 *
 * 本函数只保留「屏幕级状态 + LazyColumn 编排」：搜索过滤排序、两段式删除的待确认定时器、
 * 表冠目标（非空态滚列表 / 空态焦点轮选）、底部弧带操作条。
 * 各分段视图见 BookshelfSections.kt，卡片 / 列表头 / 进度 / 按钮一律走 Wear M3 官方组件。
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
    errorMessage: String? = null,
    infoMessage: String? = null,
    onInfoMessageShown: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    // 过滤排序随书架/搜索词缓存：输入搜索、删除确认等重组不再反复全表扫描排序
    val latestBook = remember(bookshelf) { bookshelf.maxByOrNull { it.lastReadTime } }
    val filteredBooks = remember(bookshelf, searchQuery) {
        bookshelf
            .filter { it.title.contains(searchQuery, ignoreCase = true) }
            .sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })
    }

    val tick = rememberTickHaptic()

    // 两段式删除确认：首按进入待确认，3.2s 无操作自动回滚
    var pendingDeleteUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDeleteUri) {
        if (pendingDeleteUri != null) {
            delay(3200L)
            pendingDeleteUri = null
        }
    }

    // 备份/恢复结果信息条：3.5s 自动消退
    LaunchedEffect(infoMessage) {
        if (infoMessage != null) {
            delay(3500L)
            onInfoMessageShown()
        }
    }

    val listState = rememberLazyListState()

    // 表冠目标注册：非空态滚动列表（连续跟手 + 距离门限微振）；
    // 空态无可滚内容，改走焦点轮选——复刻系统设置「对齐模式」，单事件钳 ±79px、
    // 累积满 120px 焦点在备份/恢复间移动一格并给一次 302 齿轮微振
    val context = LocalContext.current
    val isBookshelfEmpty = bookshelf.isEmpty()
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focusStepper = remember { CrownFocusStepper(scrollFactorPxPerUnit = CrownScrollHelper.scrollFactorPxPerUnit) }
    rememberCrownScrollTarget(isBookshelfEmpty) { delta ->
        if (isBookshelfEmpty) {
            val step = focusStepper.feed(delta)
            if (step != 0) {
                focusedIndex = (focusedIndex + step).mod(2)
                tick()
            }
        } else {
            CrownScrollHelper.dispatchScroll(delta, listState, context)
        }
        true
    }

    val insets = rememberWatchSafeInsets()
    // 右滑关闭应用：Wear 对顶层视图的定义即「左→右滑动关闭当前视图」，
    // 书架是应用根页，右滑与系统返回同义
    WatchScreenScaffold(
        onDismiss = onExitApp,
        fades = WatchScreenFades(top = 48.dp, bottom = 56.dp),
        actionBar = {
            // ── 备份与恢复：弧形扇带贴合圆屏下缘；空态时表冠轮选焦点高亮 ──
            EdgeActionBar(
                EdgeButtonSpec(
                    "备份",
                    focused = isBookshelfEmpty && focusedIndex == 0,
                    onClick = onBackup
                ),
                EdgeButtonSpec(
                    "恢复",
                    focused = isBookshelfEmpty && focusedIndex == 1,
                    onClick = onRestore
                )
            )
        }
    ) {
        WearBookshelfTheme {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = insets.horizontal),
                // 上下留白随内容滚动（与旧的 padding 在 verticalScroll 内同语义）
                contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    // ── 头部：列表头 + 书籍总数 ──
                    BookshelfHeader(bookCount = bookshelf.size)
                }

                item {
                    BookshelfContinueCard(latestBook = latestBook, onOpen = onOpenBook)
                }

                item {
                    BookshelfActionPair(
                        onOpenFile = onOpenFile,
                        onOpenWifiTransfer = onOpenWifiTransfer
                    )
                }

                item {
                    BookshelfMessageBanner(message = errorMessage, color = MaterialTheme.colorScheme.error)
                }

                item {
                    // 成功类信息用格式绿，与错误红区分
                    BookshelfMessageBanner(message = infoMessage, color = MaterialTheme.colorScheme.secondary)
                }

                item {
                    BookshelfSearchField(query = searchQuery, onQueryChange = onSearchChange)
                }

                item {
                    BookshelfLibraryHeader(searchQuery = searchQuery, resultCount = filteredBooks.size)
                }

                if (filteredBooks.isEmpty()) {
                    item {
                        BookshelfEmptyState(bookshelfSize = bookshelf.size)
                    }
                } else {
                    // 书籍行不带入场动画：LazyColumn 回收后会重新组合，重放 alpha 淡入会变成滚动闪变
                    items(filteredBooks.size) { index ->
                        val book = filteredBooks[index]
                        BookshelfBookRow(
                            book = book,
                            isPendingDelete = pendingDeleteUri == book.uriString,
                            // 点按反馈（tick + 按压缩放）统一在行内处理，父层不再叠加
                            onOpen = { onOpenBook(book) },
                            onTogglePin = { onTogglePin(book) },
                            onRequestDelete = { pendingDeleteUri = book.uriString },
                            onConfirmDelete = {
                                pendingDeleteUri = null
                                onDeleteBook(book)
                            }
                        )
                    }
                }

                item {
                    // ── 底部显示调节区 ──
                    BookshelfDisplayControls(
                        fontSize = fontSize,
                        isDarkMode = isDarkMode,
                        onFontSizeChange = onFontSizeChange,
                        onToggleDarkMode = onToggleDarkMode,
                        staggerIndex = 6 + filteredBooks.size.coerceAtMost(6)
                    )
                }
            }
        }
    }
}

/**
 * 书籍行：Wear TitleCard 承载「标题 + 章节 + 进度 + 操作」，
 * 进度条用 Wear LinearProgressIndicator，操作用 Wear TextButton。
 */
@Composable
private fun BookshelfBookRow(
    book: BookItem,
    isPendingDelete: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val formatBadge = remember(book.uriString, book.title) {
        BookTextConverter.formatBadgeOf(book.uriString.ifEmpty { book.title })
    }
    // 书名清洗含正则替换：随书名 remember，避免父级每次重组（搜索逐键 / 时长 tick）逐行重算
    val displayTitle = remember(book.title) { EpubParser.cleanBookTitle(book.title) }
    val rowTick = rememberTickHaptic()

    WearTitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            rowTick()
            onOpen()
        },
        title = {
            WearText(
                text = formatBadge,
                style = WearType.labelMedium,
                color = if (formatBadge == "TXT") colors.secondary else colors.primary
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
            WearText(
                text = displayTitle,
                style = WearType.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        },
        subtitle = {
            WearText(
                text = book.lastChapterTitle.ifBlank { "尚未开始" },
                style = WearType.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        time = {
            WearText(
                text = ShelfProgress.label(book.progressPercent),
                style = WearType.labelMedium,
                color = colors.primary
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WearLinearProgressIndicator(
                    progress = { ShelfProgress.fraction(book.progressPercent) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = WearProgressIndicatorDefaults.colors(
                        indicatorColor = colors.primary,
                        trackColor = colors.outlineVariant
                    ),
                    strokeWidth = 8.dp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WearTextButton(
                        onClick = {
                            rowTick()
                            onTogglePin()
                        },
                        colors = WearTextButtonDefaults.textButtonColors(contentColor = colors.primary)
                    ) {
                        WearText(
                            text = if (book.isPinned) "已置顶" else "置顶",
                            style = WearType.labelSmall
                        )
                    }
                    WearTextButton(
                        onClick = {
                            rowTick()
                            if (isPendingDelete) onConfirmDelete() else onRequestDelete()
                        },
                        colors = WearTextButtonDefaults.textButtonColors(
                            contentColor = if (isPendingDelete) colors.error else colors.onSurfaceVariant
                        )
                    ) {
                        WearText(
                            text = if (isPendingDelete) "确认删除？" else "删除",
                            style = WearType.labelSmall
                        )
                    }
                }
            }
        }
    )
}

/**
 * 加载中界面：Wear CircularProgressIndicator 旋转弧环 + 说明文字；
 * 返回键取消加载回书架（此前 handleBack 的 Loading 分支无 BackHandler 可达，按返回会直接退出应用）
 */
@Composable
fun LoadingScreen(onBack: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WearCircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                colors = WearProgressIndicatorDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                ),
                strokeWidth = 3.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WearText(
                    text = "正在打开",
                    style = WearType.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                WearText(
                    text = "加载中…",
                    style = WearType.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
