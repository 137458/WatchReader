package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
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
    val colors = MiuixTheme.colorScheme
    val scrollState = rememberScrollState()
    // 主题选择浮层：配色一步直达，替代逐档循环点按
    var showThemePicker by remember { mutableStateOf(false) }

    // 表冠滚动目标注册：Activity 顶层管线直接寻址菜单滚动（正向线性步进 + 齿轮微振）
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

    // 底色由 Window decorView 统一承载，此处不再整屏填充（少一层全屏 overdraw）
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 44.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("阅读控制", modifier = Modifier.staggeredEnter(0), color = colors.primary)

            // ── 当前章节主卡 ──
            Column(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth()
                    .squircleSurface(colors.primary, WatchShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AnimatedValue(chapterTitle.ifBlank { "当前章节" }) { title ->
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title3.copy(fontSize = 16.sp),
                        color = colors.onPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = ReadDurationFormatter.format(readDurationSec),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onPrimary
                )
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

            // ── 快捷操作 ──
            // 四键收紧内边距：圆屏最窄弦宽下并排四枚胶囊与「显示」分区同规则，防整排溢出
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(3),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                PillButton("书签", Modifier.weight(1f), verticalPadding = 10.dp, horizontalPadding = 4.dp, onClick = onAddBookmark)
                PillButton("速读", Modifier.weight(1f), verticalPadding = 10.dp, horizontalPadding = 4.dp, onClick = onOpenRsvp)
                PillButton("目录", Modifier.weight(1f), verticalPadding = 10.dp, horizontalPadding = 4.dp, onClick = onChapterListClick)
                PillButton("搜索", Modifier.weight(1f), verticalPadding = 10.dp, horizontalPadding = 4.dp, onClick = onOpenSearch)
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
                    // 四档并排最窄处每键仅 ~37dp，必须紧凑内边距才能完整容纳"系统"两字
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PillButton("系统", Modifier.weight(1f), verticalPadding = 8.dp, horizontalPadding = 5.dp) { onBrightnessChange(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT) }
                        PillButton("暗", Modifier.weight(1f), verticalPadding = 8.dp, horizontalPadding = 5.dp) { onBrightnessChange(BrightnessManager.LEVEL_ULTRA_DARK) }
                        PillButton("中", Modifier.weight(1f), verticalPadding = 8.dp, horizontalPadding = 5.dp) { onBrightnessChange(BrightnessManager.LEVEL_2_MEDIUM) }
                        PillButton("亮", Modifier.weight(1f), verticalPadding = 8.dp, horizontalPadding = 5.dp) { onBrightnessChange(BrightnessManager.LEVEL_3_STRONG) }
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
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary
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
                                style = MiuixTheme.textStyles.footnote2,
                                color = colors.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            // ── 底部主导航 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(8),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillButton("书架", Modifier.weight(1f), verticalPadding = 12.dp, onClick = onHome)
                PillButton(
                    "继续阅读",
                    Modifier.weight(1.4f),
                    emphasis = PillEmphasis.Primary,
                    verticalPadding = 12.dp,
                    onClick = onBack
                )
            }
        }

        // 顶部羽化渐隐（统一 EdgeFadeMask 基元 + 全应用统一 48dp 顶部高度）
        EdgeFadeMask(
            edge = Alignment.Top,
            modifier = Modifier.align(Alignment.TopCenter),
            height = 48.dp
        )

        // 配色选择浮层：全屏单选列表（底色 + 强调色色样直达观感），点击即换并关闭；
        // 返回键先关浮层；浮层期表冠滚动注册抢占，直接寻址浮层列表
        if (showThemePicker) {
            val pickerScroll = rememberScrollState()
            val pickerCrownContext = LocalContext.current
            rememberCrownScrollTarget(pickerScroll) { delta ->
                CrownScrollHelper.dispatchScroll(delta, pickerScroll, pickerCrownContext)
                true
            }
            androidx.activity.compose.BackHandler { showThemePicker = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
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
 * 选中行强调描边 + 高亮文字；行热区 ≥44dp
 */
@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val scheme = watchColorsOf(mode)
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
            .squircleSurface(colors.surfaceVariant, WatchShapes.Row)
            .then(
                if (selected) {
                    Modifier.squircleBorder(1.dp, colors.primary.copy(alpha = WatchAlpha.ACCENT_BORDER), WatchShapes.Row)
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onSelect()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 色样：底色胶囊 + 强调色圆点
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 22.dp)
                .clip(WatchShapes.Pill)
                .background(scheme.background)
                .border(1.dp, colors.outline.copy(alpha = WatchAlpha.HAIRLINE), WatchShapes.Pill),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(scheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = mode.label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        if (selected) {
            Text("●", color = colors.primary, fontSize = 11.sp)
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
            Text(label, color = MiuixTheme.colorScheme.onSurface, fontSize = 13.sp)
            AnimatedValue(value) { current ->
                Text(
                    text = current,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 11.sp,
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
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(WatchShapes.Pill)
            .background(colors.surfaceVariant.copy(alpha = WatchAlpha.SUBTLE_SCRIM))
    ) {
        StepperKey("−", colors, onMinus)
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(22.dp)
                .background(colors.dividerLine)
        )
        StepperKey("+", colors, onPlus)
    }
}

@Composable
private fun StepperKey(symbol: String, colors: top.yukonga.miuix.kmp.theme.Colors, onClick: () -> Unit) {
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
            .pressScale(interaction, pressedScale = 0.9f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CycleLine(label: String, value: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    // 行热区垂直内边距：13sp 文本裸行仅 ~18dp，密集相邻行腕上必误触邻行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onClick()
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.onSurface, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedValue(value) { current ->
                Text(current, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(4.dp))
            Text("›", color = colors.onSurfaceVariantSummary, fontSize = 13.sp)
        }
    }
}

/**
 * 周阅读条形图：周一至周日 7 根迷你柱，今日高亮，仅随 readDays 变化重算
 */
@Composable
private fun WeekBars(days: Map<String, Long>) {
    val colors = MiuixTheme.colorScheme
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
                        // 非今日柱用次级墨（灰），今日柱用强调色——全主题零 alpha 的两档语义
                        .background(if (bar.isToday) colors.primary else colors.onSurfaceVariantSummary)
                )
                Text(
                    text = bar.label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (bar.isToday) colors.primary else colors.onSurfaceVariantSummary
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
