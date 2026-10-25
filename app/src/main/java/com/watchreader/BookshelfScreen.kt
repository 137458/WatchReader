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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.semantics.Role
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
    errorMessage: String? = null,
    infoMessage: String? = null,
    onInfoMessageShown: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val keyboardController = LocalSoftwareKeyboardController.current
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

    val scrollState = rememberScrollState()

    // 表冠目标注册：非空态滚动列表（连续跟手 + 距离门限微振）；
    // 空态无可滚内容，改走焦点轮选——复刻系统设置「对齐模式」，单事件钳 ±79px、
    // 累积满 120px 焦点在备份/恢复间移动一格并给一次 302 齿轮微振
    val context = LocalContext.current
    val isBookshelfEmpty = bookshelf.isEmpty()
    var focusedIndex by remember { mutableStateOf(0) }
    val focusStepper = remember { CrownFocusStepper(scrollFactorPxPerUnit = CrownScrollHelper.scrollFactorPxPerUnit) }
    rememberCrownScrollTarget(isBookshelfEmpty) { delta ->
        if (isBookshelfEmpty) {
            val step = focusStepper.feed(delta)
            if (step != 0) {
                focusedIndex = (focusedIndex + step).mod(2)
                tick()
            }
        } else {
            CrownScrollHelper.dispatchScroll(delta, scrollState, context)
        }
        true
    }

    // 右滑关闭应用：Wear 对顶层视图的定义即「左→右滑动关闭当前视图」，
    // 书架是应用根页，右滑与系统返回同义
    Box(
        modifier = Modifier
            .fillMaxSize()
            .swipeDismissBack(onDismiss = onExitApp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 44.dp, bottom = 84.dp),
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
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onBackground
                )
            }
            Text(
                text = "${bookshelf.size} 本",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }

        // ── 继续阅读主卡：MD3 primaryContainer 强调卡 + 圆头进度轨 ──
        if (latestBook != null) {
            Card(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth(),
                onClick = {
                    tick()
                    onOpenBook(latestBook)
                },
                shape = RoundedCornerShape(WatchShapes.Card),
                colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    SectionLabel("继续阅读", color = colors.onPrimaryContainer)
                    Text(
                        text = latestBook.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = latestBook.lastChapterTitle.ifBlank { "从上次阅读位置继续" },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressTrack(
                            progress = (latestBook.progressPercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f),
                            fillColor = colors.primary,
                            trackColor = colors.outlineVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${latestBook.progressPercent}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer
                        )
                    }
                }
            }
        } else {
            SurfaceCard(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth()
                    .padding(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("建立你的第一座书架", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text(
                        "导入 TXT 或 EPUB，阅读进度会自动保存。",
                        style = MaterialTheme.typography.bodySmall,
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
                icon = Icons.Default.Add,
                title = "导入书籍",
                value = "TXT · EPUB",
                onClick = onOpenFile
            )
            BookshelfAction(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Share,
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

        AnimatedVisibility(
            visible = !infoMessage.isNullOrBlank(),
            enter = fadeIn(tween(WatchMotion.DUR_FADE)) + expandVertically(),
            exit = fadeOut(tween(WatchMotion.DUR_FADE_OUT)) + shrinkVertically()
        ) {
            Text(
                text = infoMessage.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                // 成功类信息用格式绿，与错误红区分
                color = colors.secondary
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(3),
            singleLine = true,
            placeholder = { Text("搜索书名", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
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
                    .staggeredEnter(5)
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
                    // 点按反馈（tick + 按压缩放）统一在行内处理，父层不再叠加
                    onOpen = { onOpenBook(book) },
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

        // 顶部/底部羽化渐隐：压在滚动内容之上、贴边操作条之下，
        // 避免主卡文字与胶囊硬叠（此前遮罩先于内容绘制，实际被内容盖住）
        EdgeFadeMask(
            edge = Alignment.Top,
            modifier = Modifier.align(Alignment.TopCenter),
            height = 48.dp
        )
        EdgeFadeMask(
            edge = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            height = 56.dp
        )

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
}

@Composable
private fun BookshelfAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tick = rememberTickHaptic()
    Card(
        modifier = modifier,
        onClick = {
            tick()
            onClick()
        },
        shape = RoundedCornerShape(WatchShapes.Row),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
                maxLines = 1
            )
        }
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
    val rowInteraction = remember { MutableInteractionSource() }
    val rowTick = rememberTickHaptic()

    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(enterOrder),
        cornerRadius = WatchShapes.Row
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(rowInteraction, pressedScale = 0.985f)
                .clickable(
                    interactionSource = rowInteraction,
                    indication = null,
                    role = Role.Button
                ) {
                    rowTick()
                    onOpen()
                }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextBadge(
                    text = formatBadge,
                    container = if (formatBadge == "TXT") colors.secondaryContainer else colors.primaryContainer,
                    content = if (formatBadge == "TXT") colors.onSecondaryContainer else colors.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
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
                TextButton(
                    onClick = {
                        rowTick()
                        onTogglePin()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) {
                    Text(
                        text = if (book.isPinned) "已置顶" else "置顶",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(
                    onClick = {
                        rowTick()
                        if (isPendingDelete) onConfirmDelete() else onRequestDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isPendingDelete) colors.error else colors.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = if (isPendingDelete) "确认删除？" else "删除",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isPendingDelete) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

/**
 * 加载中界面：旋转弧环 + 呼吸文字；返回键取消加载回书架
 * （此前 handleBack 的 Loading 分支无 BackHandler 可达，按返回会直接退出应用）
 */
@Composable
fun LoadingScreen(onBack: () -> Unit = {}) {
    androidx.activity.compose.BackHandler(onBack = onBack)
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
