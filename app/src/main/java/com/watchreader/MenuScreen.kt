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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onReadGoalChange: (Int) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    // 表冠滚动目标注册：Activity 顶层管线直接寻址菜单滚动（正向线性步进 + 齿轮微振）
    val context = LocalContext.current
    rememberCrownScrollTarget(scrollState) { delta ->
        CrownScrollHelper.dispatchScroll(delta, scrollState, context)
        true
    }
    val themeLabel = remember(themeMode) {
        when (ThemeMode.fromValue(themeMode)) {
            ThemeMode.PARCHMENT -> "羊皮纸"
            ThemeMode.DARK -> "极光黑"
            ThemeMode.RED_NIGHT -> "红光夜视"
        }
    }
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
                .padding(horizontal = 24.dp, vertical = 42.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("阅读控制", modifier = Modifier.staggeredEnter(0), color = colors.primary)

            // ── 当前章节主卡 ──
            Column(
                modifier = Modifier
                    .staggeredEnter(1)
                    .fillMaxWidth()
                    .clip(WatchShapes.Card)
                    .background(colors.primary)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AnimatedValue(chapterTitle.ifBlank { "当前章节" }) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        color = colors.onPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = ReadDurationFormatter.format(readDurationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimary.copy(alpha = 0.78f)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillButton("书签", Modifier.weight(1f), onClick = onAddBookmark)
                PillButton("速读", Modifier.weight(1f), onClick = onOpenRsvp)
                PillButton("目录", Modifier.weight(1f), onClick = onChapterListClick)
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
                    CycleLine("主题", themeLabel) { onThemeModeChange((themeMode + 1) % ThemeMode.entries.size) }
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
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            AnimatedValue(value) { current ->
                Text(
                    text = current,
                    color = MaterialTheme.colorScheme.primary,
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
 * 步进器：发丝描边胶囊双键（各自独立按压缩放）
 */
@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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
                .background(colors.outline.copy(alpha = 0.25f))
        )
        StepperKey("+", colors, onPlus)
    }
}

@Composable
private fun StepperKey(symbol: String, colors: androidx.compose.material3.ColorScheme, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
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
private fun CycleLine(label: String, value: String, onClick: () -> Unit) {    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.onSurface, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedValue(value) { current ->
                Text(current, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(4.dp))
            Text("›", color = colors.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 13.sp)
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
                        .background(if (bar.isToday) colors.primary else colors.primary.copy(alpha = 0.32f))
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
