package com.watchreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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

    val scrollState = rememberScrollState()

    // 表冠滚动目标注册：Activity 顶层管线直接寻址书架滚动（正向线性步进 + 齿轮微振）
    val context = LocalContext.current
    rememberCrownScrollTarget(scrollState) { delta ->
        CrownScrollHelper.dispatchScroll(delta, scrollState, context)
        true
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            enter = fadeIn(tween(WatchMotion.DUR_FADE)) + expandVertically(),
            exit = fadeOut(tween(WatchMotion.DUR_FADE_OUT)) + shrinkVertically()
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

        // ── 底部显示调节区 ──
        // 圆屏底部弦宽小于屏宽，定宽并排会整排伸出屏幕右侧被裁切（"日/夜"键首当其冲：
        // 旧版一行塞下 标签 + 字号值 + 三枚胶囊共约 188dp > 可用 185dp）。
        // 故拆为标签行 + 权重行：标签与数值单独占一行，控制键按权重均分剩余宽度，
        // 任何屏宽 / 字号下都由布局本身保证不溢出。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(6 + filteredBooks.size.coerceAtMost(6)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("显示")
                Text(
                    text = "字号 $fontSize",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillButton(
                    "−",
                    Modifier.weight(1f),
                    verticalPadding = 8.dp,
                    horizontalPadding = 0.dp
                ) { onFontSizeChange(fontSize - 1) }
                PillButton(
                    "+",
                    Modifier.weight(1f),
                    verticalPadding = 8.dp,
                    horizontalPadding = 0.dp
                ) { onFontSizeChange(fontSize + 1) }
                PillButton(
                    if (isDarkMode) "夜间" else "日间",
                    Modifier.weight(1.7f),
                    active = isDarkMode,
                    verticalPadding = 8.dp,
                    horizontalPadding = 0.dp
                ) { onToggleDarkMode() }
            }
        }
        }
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
            // 值行用格式绿：按钮浅暖底上对比 4.7:1 达 AA，且与 TXT 格式角标同一语义色
            Text(value, style = MaterialTheme.typography.labelSmall, color = colors.secondary)
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
    val formatBadge = remember(book.uriString, book.title) {
        BookTextConverter.formatBadgeOf(book.uriString.ifEmpty { book.title })
    }
    // 书名清洗含正则替换：随书名 remember，避免父级每次重组（搜索逐键 / 时长 tick）逐行重算
    val displayTitle = remember(book.title) { EpubParser.cleanBookTitle(book.title) }

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
                    text = formatBadge,
                    color = if (formatBadge == "TXT") colors.secondary else colors.primary
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
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (book.isPinned) "已置顶" else "置顶",
                    modifier = Modifier
                        .clickable(onClick = onTogglePin)
                        // 可点击区内边距：触控热区 ≥ 文字视觉尺寸，圆屏边缘误触率显著降低
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = if (isPendingDelete) "确认删除？" else "删除",
                    modifier = Modifier
                        .clickable { if (isPendingDelete) onConfirmDelete() else onRequestDelete() }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
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
