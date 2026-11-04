package com.watchreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 书架页的分段视图：每个 LazyColumn item 一枚，入参即该段需要的全部数据。
 *
 * 抽出动机：BookshelfScreen 原先把这些段全内联在一个 390 行的函数体里，
 * 屏幕级状态（表冠焦点、待确认删除、搜索词）与纯展示段耦合，改一处动效要横穿整页。
 * 现在屏幕只保留「状态 + LazyColumn 编排」，每段可单独读、单独改。
 * 入场错峰序号（[BookshelfHeader] 0 → [BookshelfActionPair] 2 → 搜索 3 → 书库 4 →
 * 空态 5 → 显示区 6+行数）沿用旧值，动效时序零变化。
 */

/**
 * 书架进度的纯参数口径（由 ShelfProgressTest 锁定）：百分比字段既驱动进度条比例，
 * 也直接渲染成文案，两个口径原本各写一遍散在继续阅读卡与书籍行里。
 */
internal object ShelfProgress {

    /** 0..100 的百分数 → 进度条 0..1 比例；越界钳制（旧实现 (p / 100f).coerceIn(0f, 1f)） */
    fun fraction(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)

    /** 进度文案：与旧内联模板 "${...}%" 一致，不做四舍五入或补位 */
    fun label(percent: Int): String = "$percent%"
}

/** 头部：栏目标签 + 主标题 + 书籍总数 */
@Composable
internal fun BookshelfHeader(bookCount: Int) {
    val colors = MaterialTheme.colorScheme
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
            text = "$bookCount 本",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
    }
}

/**
 * 继续阅读卡：有最近阅读的书时给主容器卡（标题 + 上次章节 + 进度），
 * 否则给「建立你的第一座书架」引导卡。
 */
@Composable
internal fun BookshelfContinueCard(latestBook: BookItem?, onOpen: (BookItem) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tick = rememberTickHaptic()
    if (latestBook != null) {
        Card(
            modifier = Modifier
                .staggeredEnter(1)
                .fillMaxWidth(),
            onClick = {
                tick()
                onOpen(latestBook)
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
                        progress = ShelfProgress.fraction(latestBook.progressPercent),
                        modifier = Modifier.weight(1f),
                        fillColor = colors.primary,
                        trackColor = colors.outlineVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ShelfProgress.label(latestBook.progressPercent),
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
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "建立你的第一座书架",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface
                )
                Text(
                    text = "导入 TXT 或 EPUB，阅读进度会自动保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** 两个入口卡：导入书籍 / 无线传书 */
@Composable
internal fun BookshelfActionPair(onOpenFile: () -> Unit, onOpenWifiTransfer: () -> Unit) {
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
}

/**
 * 一条结果信息（错误红 / 成功绿由 [color] 决定）：空或空白串时不占位，
 * 高度展开与淡入同源走 WatchMotion 档位。
 */
@Composable
internal fun BookshelfMessageBanner(message: String?, color: Color) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = fadeIn(tween(WatchMotion.DUR_FADE)) + expandVertically(),
        exit = fadeOut(tween(WatchMotion.DUR_FADE_OUT)) + shrinkVertically()
    ) {
        Text(
            text = message.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 搜索框：回车收起软键盘（手表上键盘占掉半屏，必须能主动收） */
@Composable
internal fun BookshelfSearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
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
}

/** 书库分段标题：无搜索词时说明默认排序，有搜索词时给命中数 */
@Composable
internal fun BookshelfLibraryHeader(searchQuery: String, resultCount: Int) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("书库", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Text(
            text = if (searchQuery.isBlank()) "最近阅读优先" else "$resultCount 个结果",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
    }
}

/** 空态卡：区分「书架本身为空」与「搜索无命中」两种文案 */
@Composable
internal fun BookshelfEmptyState(bookshelfSize: Int) {
    val colors = MaterialTheme.colorScheme
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
                text = if (bookshelfSize == 0) "书架还是空的" else "没有匹配的书籍",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface
            )
            Text(
                text = if (bookshelfSize == 0) "从上方导入或扫码传入第一本书" else "换个书名关键词试试",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示调节区：字号与日夜。
 *
 * 圆屏底部弦宽小于屏宽，定宽并排会整排伸出屏幕右侧被裁切（"日/夜"键首当其冲：
 * 旧版一行塞下 标签 + 字号值 + 三枚胶囊共约 188dp > 可用 185dp）。
 * 故拆为标签行 + 权重行：标签与数值单独占一行，控制键按权重均分剩余宽度，
 * 任何屏宽 / 字号下都由布局本身保证不溢出。
 */
@Composable
internal fun BookshelfDisplayControls(
    fontSize: Int,
    isDarkMode: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit,
    staggerIndex: Int
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(staggerIndex),
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

/** 入口卡：图标 + 标题 + 副标题，点按给一次 CLOCK_TICK 触感 */
@Composable
internal fun BookshelfAction(
    modifier: Modifier,
    icon: ImageVector,
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
