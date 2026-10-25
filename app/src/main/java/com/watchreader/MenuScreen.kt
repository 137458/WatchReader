package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 阅读菜单 — 设计系统统一排版：分区卡片 + 发丝分隔 + 弹簧按压反馈
 */
@Composable
fun MenuScreen(
    chapterTitle: String = "",
    fontSize: Int,
    autoScrollSpeed: Float,
    isAutoScrolling: Boolean,
    appBrightness: Float,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onBack: () -> Unit,
    onHome: () -> Unit,
    themeMode: Int = 0,
    onThemeModeChange: (Int) -> Unit = {},
    tapPageArea: Int = 0,
    onTapPageAreaChange: (Int) -> Unit = {},
    cleanTypography: Boolean = true,
    onCleanTypographyChange: (Boolean) -> Unit = {},
    fontType: Int = 0,
    onFontTypeChange: (Int) -> Unit = {},
    readDurationSec: Long = 0L,
    readDays: Map<String, Long> = emptyMap(),
    readGoalMinutes: Int = 0,
    finishedCount: Int = 0,
    onReadGoalChange: (Int) -> Unit = {},
    lineSpacing: Int = LineSpacingMode.STANDARD.value,
    letterSpacing: Int = LetterSpacingMode.STANDARD.value,
    onLineSpacingChange: (Int) -> Unit = {},
    onLetterSpacingChange: (Int) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    // 主题选择浮层：配色一步直达，替代逐档循环点按
    var showThemePicker by remember { mutableStateOf(false) }

    // 表冠滚动目标注册：Activity 顶层管线直接寻址菜单滚动（连续跟手 + 距离门限微振）
    val context = LocalContext.current
    rememberCrownScrollTarget(scrollState) { delta ->
        CrownScrollHelper.dispatchScroll(delta, scrollState, context)
        true
    }
    val themeLabel = remember(themeMode) { ThemeMode.fromValue(themeMode).label }
    val tapLabel = remember(tapPageArea) {
        when (TapPageArea.fromValue(tapPageArea)) {
            TapPageArea.TOP_BOTTOM -> "上下点按"
            TapPageArea.LEFT_RIGHT -> "左右点按"
            TapPageArea.DISABLED -> "已关闭"
        }
    }
    val fontLabel = if (FontType.fromValue(fontType) == FontType.SERIF) "宋体" else "黑体"

    // 右滑退出：菜单页右滑回阅读页；配色浮层开启期由浮层自己接管右滑，此处停摆
    val backTick = rememberTickHaptic()
    // 底色由 Window decorView 统一承载，此处不再整屏填充（少一层全屏 overdraw）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .swipeDismissBack(enabled = !showThemePicker) {
                backTick()
                onBack()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 44.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("阅读控制", modifier = Modifier.staggeredEnter(0), color = colors.primary)

            // ── 当前章节主卡（MD3 primaryContainer 强调卡） ──
            Card(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(WatchShapes.Card),
                colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AnimatedValue(chapterTitle.ifBlank { "当前章节" }) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = ReadDurationFormatter.format(readDurationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimaryContainer
                    )
                }
            }

            // ── 章节导航 ──
            if (hasPrevChapter || hasNextChapter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEnter(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasPrevChapter) PillButton("上一章", Modifier.weight(1f), onClick = onPrevChapter)
                    if (hasNextChapter) PillButton("下一章", Modifier.weight(1f), onClick = onNextChapter)
                }
            }

            // ── 快捷操作（2×2 网格：每键约 88×48dp，满足 Google 48dp 触控基线） ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(3),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("书签", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onAddBookmark)
                    PillButton("速读", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenRsvp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("目录", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onChapterListClick)
                    PillButton("搜索", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenSearch)
                }
            }

            // ── 滚动分区 ──
            SurfaceCard(modifier = Modifier.fillMaxWidth().staggeredEnter(4)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("滚动")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            AnimatedValue(if (isAutoScrolling) "自动滚屏中" else "自动滚屏") { label ->
                                Text(label, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                            AnimatedValue("${autoScrollSpeed.toInt()} px/s") { speed ->
                                Text(speed, color = colors.primary, fontSize = 11.sp)
                            }
                        }
                        PillButton(
                            if (isAutoScrolling) "暂停" else "开启",
                            active = isAutoScrolling,
                            verticalPadding = 8.dp,
                            onClick = onToggleAutoScroll
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("减速", Modifier.weight(1f), verticalPadding = 8.dp) {
                            onAutoScrollSpeedChange((autoScrollSpeed - 10f).coerceAtLeast(15f))
                        }
                        PillButton("加速", Modifier.weight(1f), verticalPadding = 8.dp) {
                            onAutoScrollSpeedChange((autoScrollSpeed + 10f).coerceAtMost(200f))
                        }
                    }
                }
            }

            // ── 显示分区 ──
            SurfaceCard(modifier = Modifier.fillMaxWidth().staggeredEnter(5)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("显示")
                    SettingLine("字号", "$fontSize") {
                        Stepper(
                            onMinus = { onFontSizeChange(fontSize - 1) },
                            onPlus = { onFontSizeChange(fontSize + 1) }
                        )
                    }
                    HairlineDivider()
                    SettingLine("亮度", BrightnessManager.formatBrightnessText(appBrightness)) {
                        Stepper(
                            onMinus = {
                                val current = if (appBrightness < 0f) BrightnessManager.SYSTEM_STEP_BASE else appBrightness
                                onBrightnessChange((current - 0.05f).coerceIn(0.01f, 1f))
                            },
                            onPlus = {
                                val current = if (appBrightness < 0f) BrightnessManager.SYSTEM_STEP_BASE else appBrightness
                                onBrightnessChange((current + 0.05f).coerceIn(0.01f, 1f))
                            }
                        )
                    }
                    HairlineDivider()
                    // 亮度预设 2×2 网格：避免四键并排把单键压到 48dp 以下
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("系统", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT) }
                            PillButton("暗", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_ULTRA_DARK) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("中", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_2_MEDIUM) }
                            PillButton("亮", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_3_STRONG) }
                        }
                    }
                }
            }

            // ── 偏好分区 ──
            SurfaceCard(modifier = Modifier.fillMaxWidth().staggeredEnter(6)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("偏好")
                    CycleLine("主题", themeLabel) { showThemePicker = true }
                    HairlineDivider()
                    CycleLine("点按翻页", tapLabel) { onTapPageAreaChange((tapPageArea + 1) % TapPageArea.entries.size) }
                    HairlineDivider()
                    CycleLine("排版净化", if (cleanTypography) "开启" else "关闭") {
                        onCleanTypographyChange(!cleanTypography)
                    }
                    HairlineDivider()
                    CycleLine("字体", fontLabel) {
                        onFontTypeChange(if (FontType.fromValue(fontType) == FontType.SERIF) FontType.SANS_SERIF.value else FontType.SERIF.value)
                    }
                    HairlineDivider()
                    CycleLine("行距", LineSpacingMode.fromValue(lineSpacing).label) {
                        onLineSpacingChange((lineSpacing + 1) % LineSpacingMode.entries.size)
                    }
                    HairlineDivider()
                    CycleLine("字距", LetterSpacingMode.fromValue(letterSpacing).label) {
                        onLetterSpacingChange((letterSpacing + 1) % LetterSpacingMode.entries.size)
                    }
                }
            }

            // ── 统计分区 ──
            SurfaceCard(modifier = Modifier.fillMaxWidth().staggeredEnter(7)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("统计")
                    WeekBars(readDays)
                    AnimatedValue(weekSummaryLabel(readDays, finishedCount)) { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    HairlineDivider()
                    val goalLabel = if (readGoalMinutes <= 0) "关闭" else "$readGoalMinutes 分钟"
                    CycleLine("每日目标", goalLabel) {
                        val options = ReadingStats.GOAL_OPTIONS_MINUTES
                        val nextIdx = (options.indexOf(readGoalMinutes) + 1).mod(options.size)
                        onReadGoalChange(options[nextIdx])
                    }
                    if (readGoalMinutes > 0) {
                        val todayMinutes = todayMinutesOf(readDays)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ProgressTrack(
                                progress = (todayMinutes.toFloat() / readGoalMinutes).coerceIn(0f, 1f),
                                height = 3.dp
                            )
                            Text(
                                text = "今日 $todayMinutes / $readGoalMinutes 分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

        }

        // 底部羽化渐隐：压在滚动内容之上、贴边操作条之下，避免内容与胶囊硬叠
        EdgeFadeMask(
            edge = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            height = 56.dp
        )

        // ── 底部弧形贴边主导航：扇带处处贴合圆屏下缘 ──
        EdgeActionBar(
            EdgeButtonSpec("书架", onClick = onHome),
            EdgeButtonSpec(
                "继续阅读",
                emphasis = PillEmphasis.Primary,
                onClick = onBack
            )
        )

        // 顶部羽化渐隐（统一 EdgeFadeMask 基元 + 全应用统一 48dp 顶部高度）
        EdgeFadeMask(
            edge = Alignment.Top,
            modifier = Modifier.align(Alignment.TopCenter),
            height = 48.dp
        )

        // 配色选择浮层：全屏单选列表（底色 + 强调色色样直达观感），点击即换并关闭；
        // 进出场走 scale + fade（微内容时长档），右滑则内容跟手滑出后关闭；
        // 返回键先关浮层；浮层期表冠滚动注册抢占，直接寻址浮层列表
        AnimatedVisibility(
            visible = showThemePicker,
            enter = WatchOverlay.Enter,
            exit = WatchOverlay.Exit,
        ) {
            val pickerScroll = rememberScrollState()
            val pickerCrownContext = LocalContext.current
            rememberCrownScrollTarget(pickerScroll) { delta ->
                CrownScrollHelper.dispatchScroll(delta, pickerScroll, pickerCrownContext)
                true
            }
            val pickerTick = rememberTickHaptic()
            androidx.activity.compose.BackHandler { showThemePicker = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    // 右滑关浮层：内容跟手平移并淡出，越过门限滑出后关闭，竖向滚动仍归列表
                    .swipeDismissBack {
                        pickerTick()
                        showThemePicker = false
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showThemePicker = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(pickerScroll)
                        .padding(horizontal = 24.dp, vertical = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("选择配色", color = colors.primary)
                    ThemeMode.entries.forEach { mode ->
                        ThemeOptionRow(
                            mode = mode,
                            selected = mode.value == themeMode,
                            onSelect = {
                                onThemeModeChange(mode.value)
                                showThemePicker = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 配色选择浮层单行：主题底色胶囊内嵌强调色圆点作色样（所见即所得），
 * 选中行强调描边 + 高亮文字；行热区 ≥48dp
 */
@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val scheme = watchColorSchemeOf(mode)
    val tick = rememberTickHaptic()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = {
                    tick()
                    onSelect()
                },
                role = Role.RadioButton
            ),
        shape = RoundedCornerShape(WatchShapes.Row),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (selected) BorderStroke(1.5.dp, colors.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 色样：底色胶囊 + 强调色圆点
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 22.dp)
                    .clip(CircleShape)
                    .background(scheme.background)
                    .border(1.dp, colors.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) colors.primary else colors.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun AnimatedValue(value: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(tween(WatchMotion.DUR_SWAP_IN)) + slideInVertically { it / 3 }) togetherWith
                (fadeOut(tween(WatchMotion.DUR_SWAP_OUT)) + slideOutVertically { -it / 3 })
        },
        label = "menu-value"
    ) { current ->
        content(current)
    }
}

@Composable
private fun SettingLine(label: String, value: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            AnimatedValue(value) { current ->
                Text(
                    text = current,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        trailing()
    }
}

/**
 * 步进器：发丝描边胶囊双键（各自独立按压缩放）；长按 400ms 后 90ms 连发，
 * 免去调字号从默认到目标档的逐档连点
 */
@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperKey("−", colors, onMinus)
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(colors.outlineVariant)
            )
            StepperKey("+", colors, onPlus)
        }
    }
}

@Composable
private fun StepperKey(symbol: String, colors: ColorScheme, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            onClick()
            delay(400L)
            while (true) {
                onClick()
                delay(90L)
            }
        }
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CycleLine(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    // 行热区垂直内边距：13sp 文本裸行仅 ~18dp，密集相邻行腕上必误触邻行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(interaction, pressedScale = 0.985f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button
            ) {
                tick()
                onClick()
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedValue(value) { current ->
                Text(current, style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 周阅读条形图：周一至周日 7 根迷你柱，今日高亮，仅随 readDays 变化重算
 */
@Composable
private fun WeekBars(days: Map<String, Long>) {
    val colors = MaterialTheme.colorScheme
    val today = remember { java.time.LocalDate.now() }
    val bars = remember(days, today) { ReadingStats.weekBars(today, days) }
    val maxMinutes = bars.maxOf { it.minutes }.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { bar ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f)
            ) {
                val barHeight = if (bar.minutes <= 0) 3.dp
                else (4f + 24f * bar.minutes / maxMinutes).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        // 非今日柱用中性表面色，今日柱用强调色——零 alpha 的两档语义
                        .background(if (bar.isToday) colors.primary else colors.surfaceVariant)
                )
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.isToday) colors.primary else colors.onSurfaceVariant
                )
            }
        }
    }
}

/** 今日已读分钟数 */
private fun todayMinutesOf(days: Map<String, Long>): Int =
    ((days[ReadingStats.dateKeyOf(System.currentTimeMillis())] ?: 0L) / 60L).toInt()

/** 周摘要文案：本周分钟数 + 读完本数（无完读时省略后半句） */
private fun weekSummaryLabel(days: Map<String, Long>, finishedCount: Int): String {
    val weekMinutes = ReadingStats.weekBars(java.time.LocalDate.now(), days).sumOf { it.minutes }
    val sb = StringBuilder("本周 ").append(weekMinutes).append(" 分钟")
    if (finishedCount > 0) sb.append(" · 已读完 ").append(finishedCount).append(" 本")
    return sb.toString()
}
