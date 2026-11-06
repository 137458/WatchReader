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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.ButtonDefaults as WearButtonDefaults
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.LinearProgressIndicator as WearLinearProgressIndicator
import androidx.wear.compose.material3.ListHeader as WearListHeader
import androidx.wear.compose.material3.ListSubHeader as WearListSubHeader
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.OutlinedCard as WearOutlinedCard
import androidx.wear.compose.material3.ProgressIndicatorDefaults as WearProgressIndicatorDefaults
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TitleCard as WearTitleCard
import androidx.wear.compose.material3.Typography as WearTypography

/**
 * 书架页的分段视图：每个 LazyColumn item 一枚，入参即该段需要的全部数据。
 *
 * 组件口径（ADR-021）：本页 UI 全部走 Wear Compose Material 3 官方组件——
 * 卡片用 Card / TitleCard / OutlinedCard，列表头用 ListHeader / ListSubHeader，
 * 进度用 LinearProgressIndicator，按钮用 Button / TextButton，文本用 wear 的 Text。
 * 唯一保留的手写件是搜索输入框：wear M3 没有 TextField 组件，只能沿用 MD3 OutlinedTextField。
 * 底部弧带操作条（EdgeActionBar）与圆屏安全区壳（WatchScreenScaffold）不在本页维护。
 *
 * 抽出动机：BookshelfScreen 原先把这些段全内联在一个 390 行的函数体里，
 * 屏幕级状态（表冠焦点、待确认删除、搜索词）与纯展示段耦合，改一处动效要横穿整页。
 * 现在屏幕只保留「状态 + LazyColumn 编排」，每段可单独读、单独改。
 * 入场错峰序号（[BookshelfHeader] 0 → [BookshelfActionPair] 2 → 搜索 3 → 书库 4 →
 * 空态 5 → 显示区 6+行数）沿用旧值，动效时序零变化。
 */

/** 本页共用的 Wear M3 排版令牌（与 wear 主题一致，避免各处散写字号） */
internal val WearType: WearTypography = watchWearTypography()

/**
 * Wear M3 主题壳：把手表既有的 MD3 令牌桥接成 Wear 令牌，
 * 使官方 Wear 组件与既有页面共享同一套配色与排版，不出现两套视觉体系。
 */
@Composable
internal fun WearBookshelfTheme(content: @Composable () -> Unit) {
    val md3Colors = MaterialTheme.colorScheme
    val type = remember { watchWearTypography() }
    WearMaterialTheme(
        colorScheme = watchWearColorSchemeOf(md3Colors),
        typography = type,
        shapes = watchWearShapes(),
        motionScheme = watchWearMotionScheme(),
        content = content
    )
}

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

/** 头部：列表头（Wear ListHeader）给主标题 + 书籍总数 */
@Composable
internal fun BookshelfHeader(bookCount: Int) {
    val colors = MaterialTheme.colorScheme
    WearListHeader(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(0)
    ) {
        WearText(
            text = "我的书架",
            style = WearType.titleLarge,
            color = colors.onBackground
        )
        Spacer(modifier = Modifier.weight(1f))
        WearText(
            text = "$bookCount 本",
            style = WearType.labelMedium,
            color = colors.onSurfaceVariant
        )
    }
}

/**
 * 继续阅读卡：Wear TitleCard（标题 + 副标题 + 时间位 + 内容槽）承载最近阅读的书，
 * 无书时给描边引导卡（Wear OutlinedCard）。
 */
@Composable
internal fun BookshelfContinueCard(latestBook: BookItem?, onOpen: (BookItem) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tick = rememberTickHaptic()
    if (latestBook != null) {
        WearTitleCard(
            modifier = Modifier
                .staggeredEnter(1)
                .fillMaxWidth(),
            onClick = {
                tick()
                onOpen(latestBook)
            },
            title = {
                WearText(
                    text = latestBook.title,
                    style = WearType.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            subtitle = {
                WearText(
                    text = latestBook.lastChapterTitle.ifBlank { "从上次阅读位置继续" },
                    style = WearType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            time = {
                WearText(
                    text = ShelfProgress.label(latestBook.progressPercent),
                    style = WearType.labelMedium
                )
            },
            colors = WearCardDefaults.cardColors(
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                titleColor = colors.onPrimaryContainer,
                subtitleColor = colors.onPrimaryContainer,
                timeColor = colors.onPrimaryContainer
            ),
            content = {
                WearLinearProgressIndicator(
                    progress = { ShelfProgress.fraction(latestBook.progressPercent) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = WearProgressIndicatorDefaults.colors(
                        indicatorColor = colors.primary,
                        trackColor = colors.outlineVariant
                    ),
                    strokeWidth = 8.dp
                )
            }
        )
    } else {
        WearOutlinedCard(
            modifier = Modifier
                .staggeredEnter(1)
                .fillMaxWidth(),
            colors = WearCardDefaults.outlinedCardColors(contentColor = colors.onSurface),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WearText(
                        text = "建立你的第一座书架",
                        style = WearType.titleMedium,
                        color = colors.onSurface
                    )
                    WearText(
                        text = "导入 TXT 或 EPUB，阅读进度会自动保存。",
                        style = WearType.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        )
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
        WearText(
            text = message.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            style = WearType.labelSmall,
            color = color
        )
    }
}

/**
 * 搜索框：回车收起软键盘（手表上键盘占掉半屏，必须能主动收）。
 * Wear M3 没有文本输入组件，只能保留 MD3 OutlinedTextField——本页唯一的手写例外。
 */
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
        placeholder = { WearText("搜索书名", style = WearType.bodyMedium) },
        leadingIcon = {
            androidx.compose.material3.Icon(
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
    WearListSubHeader(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(4),
        label = {
            WearText(
                text = "书库",
                style = WearType.titleMedium,
                color = colors.onBackground
            )
            Spacer(modifier = Modifier.weight(1f))
            WearText(
                text = if (searchQuery.isBlank()) "最近阅读优先" else "$resultCount 个结果",
                style = WearType.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    )
}

/** 空态卡：区分「书架本身为空」与「搜索无命中」两种文案 */
@Composable
internal fun BookshelfEmptyState(bookshelfSize: Int) {
    val colors = MaterialTheme.colorScheme
    WearOutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(5),
        colors = WearCardDefaults.outlinedCardColors(contentColor = colors.onSurface),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WearText(
                    text = if (bookshelfSize == 0) "书架还是空的" else "没有匹配的书籍",
                    style = WearType.labelLarge,
                    color = colors.onSurface
                )
                WearText(
                    text = if (bookshelfSize == 0) "从上方导入或扫码传入第一本书" else "换个书名关键词试试",
                    style = WearType.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    )
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        WearListHeader(modifier = Modifier.fillMaxWidth()) {
            WearText(
                text = "显示",
                style = WearType.titleMedium,
                color = colors.onBackground
            )
            Spacer(modifier = Modifier.weight(1f))
            WearText(
                text = "字号 $fontSize",
                style = WearType.labelMedium,
                color = colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WearButton(
                onClick = { onFontSizeChange(fontSize - 1) },
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "−", style = WearType.labelLarge)
                }
            )
            WearButton(
                onClick = { onFontSizeChange(fontSize + 1) },
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "+", style = WearType.labelLarge)
                }
            )
            WearButton(
                onClick = { onToggleDarkMode() },
                modifier = Modifier.weight(1.7f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = if (isDarkMode) colors.primaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (isDarkMode) colors.onPrimaryContainer else colors.onSurface
                ),
                content = {
                    WearText(
                        text = if (isDarkMode) "夜间" else "日间",
                        style = WearType.labelLarge
                    )
                }
            )
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
    WearCard(
        modifier = modifier,
        onClick = {
            tick()
            onClick()
        },
        shape = RoundedCornerShape(WatchShapes.Row),
        colors = WearCardDefaults.cardColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface
        ),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                WearIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
                WearText(
                    text = title,
                    style = WearType.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1
                )
                WearText(
                    text = value,
                    style = WearType.labelSmall,
                    color = colors.secondary,
                    maxLines = 1
                )
            }
        }
    )
}
