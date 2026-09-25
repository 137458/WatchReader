package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    readDurationSec: Long = 0L
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    // 表冠滚动目标注册：Activity 顶层管线直接寻址菜单滚动（正向线性步进 + 齿轮微振），
    // 替代依赖原生焦点存活的 1dp 隐形锚点
    val context = LocalContext.current
    DisposableEffect(scrollState) {
        val target = CrownScrollTarget { delta ->
            CrownScrollHelper.dispatchScroll(delta, scrollState, context)
            true
        }
        CrownScrollTargetRegistry.activate(target)
        onDispose { CrownScrollTargetRegistry.deactivate(target) }
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
    val fontLabel = if (FontType.fromValue(fontType) == FontType.SERIF) "衬线" else "黑体"

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
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
                                val current = if (appBrightness < 0f) 0.50f else appBrightness
                                onBrightnessChange((current - 0.05f).coerceIn(0.01f, 1f))
                            },
                            onPlus = {
                                val current = if (appBrightness < 0f) 0.50f else appBrightness
                                onBrightnessChange((current + 0.05f).coerceIn(0.01f, 1f))
                            }
                        )
                    }
                    HairlineDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillButton("系统", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(-1f) }
                        PillButton("暗", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(0.10f) }
                        PillButton("中", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(0.65f) }
                        PillButton("亮", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(1f) }
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
                    CycleLine("主题", themeLabel) { onThemeModeChange((themeMode + 1) % 3) }
                    HairlineDivider()
                    CycleLine("点按翻页", tapLabel) { onTapPageAreaChange((tapPageArea + 1) % 3) }
                    HairlineDivider()
                    CycleLine("排版净化", if (cleanTypography) "开启" else "关闭") { onCleanTypographyChange(!cleanTypography) }
                    HairlineDivider()
                    CycleLine("字体", fontLabel) {
                        onFontTypeChange(if (FontType.fromValue(fontType) == FontType.SERIF) FontType.SANS_SERIF.value else FontType.SERIF.value)
                    }
                }
            }

            // ── 底部主导航 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEnter(7),
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

        // 顶部羽化渐隐（统一 EdgeFadeMask 基元）
        EdgeFadeMask(
            edge = Alignment.Top,
            modifier = Modifier.align(Alignment.TopCenter),
            height = 36.dp
        )
    }
}

@Composable
private fun AnimatedValue(value: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically { it / 3 }) togetherWith
                (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
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
            .background(colors.surfaceVariant.copy(alpha = 0.55f))
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
private fun CycleLine(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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
