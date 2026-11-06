package com.watchreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults as WearButtonDefaults
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import androidx.wear.compose.material3.CompactButton as WearCompactButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.ListHeader as WearListHeader
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TitleCard as WearTitleCard

/**
 * 菜单分区内容：阅读控制 + 滚动 / 显示 / 偏好三段设置。
 *
 * 排版全部交给 MenuBlocks.kt 的骨架（卡片、设置行、步进器、循环行），本文件只决定
 * 「哪个分区有哪些行、行里的值怎么来」。行里那些「值 → 文案」的换算不在组合函数中现算，
 * 而是收进 [MenuValueText] 与 [BrightnessStep] 两个纯对象：它们不碰 Compose 运行时，
 * 因此可以在 JVM 测试里逐档锁定文案（MenuSectionLogicTest）。
 *
 * 组件口径（ADR-021）：卡片、列表头、按钮、开关、步进器一律用 Wear Compose Material 3
 * 官方件（TitleCard / ListHeader / Button / CompactButton / SwitchButton / Stepper），
 * 不再手绘控件；按钮标签统一走旧式重载的 [content] 具名槽（与项目其余页面一致）。
 */

/**
 * 亮度步进：从「系统」档起跳时统一以 [BrightnessManager.SYSTEM_STEP_BASE] 为基准，
 * 之后按 ±0.05 递进并收敛到 1%..100%。
 *
 * 减法与加法此前在步进器的两个回调里各写一遍（基准判定 + 步长 + 钳制），
 * 收成一处后两键只差 [delta] 的符号，钳制边界不会只改一边。
 */
internal object BrightnessStep {

    /** 单次步进的下限与上限（应用域亮度值） */
    const val MIN = 0.01f
    const val MAX = 1f

    /** [current] 为负表示「跟随系统」，此时从 [BrightnessManager.SYSTEM_STEP_BASE] 起跳 */
    fun step(current: Float, delta: Float): Float {
        val base = if (current < 0f) BrightnessManager.SYSTEM_STEP_BASE else current
        return (base + delta).coerceIn(MIN, MAX)
    }
}

/** 设置行里的「值 → 中文文案」换算（纯函数，由 MenuSectionLogicTest 锁定） */
internal object MenuValueText {

    /** 点按翻页热区模式：模式枚举没有 label 属性，这里是唯一的一处口径 */
    fun tapPageArea(area: TapPageArea): String = when (area) {
        TapPageArea.TOP_BOTTOM -> "上下点按"
        TapPageArea.LEFT_RIGHT -> "左右点按"
        TapPageArea.DISABLED -> "已关闭"
    }

    /** 字体档位：阅读页只区分衬线/黑体两支 */
    fun fontType(type: FontType): String = if (type == FontType.SERIF) "宋体" else "黑体"

    /** 排版净化开关 */
    fun cleanTypography(enabled: Boolean): String = if (enabled) "开启" else "关闭"

    /** 自动滚屏主标题：强调「正在滚」而不只显示开关态 */
    fun autoScrollState(isAutoScrolling: Boolean): String =
        if (isAutoScrolling) "自动滚屏中" else "自动滚屏"

    /** 自动滚屏速度：取整到整数 px/s（与 AutoScrollSpeed 的取值域一致，不做二次钳制） */
    fun autoScrollSpeed(speed: Float): String = "${speed.toInt()} px/s"
}

/**
 * 阅读控制块：分区列表头（Wear ListHeader）+ 当前章节主卡（Wear TitleCard）+
 * 章节导航 + 2×2 快捷操作。
 * 入场错峰序号 0~3 由本块自己持有，页面只保证块的先后顺序
 */
@Composable
internal fun ReadingControlBlock(
    chapterTitle: String,
    readDurationSec: Long,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    // ── 分区头：Wear ListHeader（官方列表头，48dp 高） ──
    WearListHeader(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(0),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        contentColor = colors.primary,
        content = {
            WearText(
                text = "阅读控制",
                style = WearType.titleLarge,
                color = colors.primary
            )
        }
    )

    // ── 当前章节主卡：Wear TitleCard（标题槽 + 副标题槽） ──
    WearTitleCard(
        modifier = Modifier
            .staggeredEnter(1)
            .fillMaxWidth(),
        title = {
            AnimatedValue(chapterTitle.ifBlank { "当前章节" }) { title ->
                WearText(
                    text = title,
                    style = WearType.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        subtitle = {
            WearText(
                text = ReadDurationFormatter.format(readDurationSec),
                style = WearType.labelMedium
            )
        },
        colors = WearCardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            titleColor = colors.onPrimaryContainer,
            subtitleColor = colors.onPrimaryContainer
        )
    )

    // ── 章节导航：官方 tonal 按钮并排（各占一半权重） ──
    if (hasPrevChapter || hasNextChapter) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasPrevChapter) {
                WearFilledTonalButton(
                    onClick = onPrevChapter,
                    modifier = Modifier.weight(1f),
                    content = {
                        WearText(text = "上一章", style = WearType.labelLarge)
                    }
                )
            }
            if (hasNextChapter) {
                WearFilledTonalButton(
                    onClick = onNextChapter,
                    modifier = Modifier.weight(1f),
                    content = {
                        WearText(text = "下一章", style = WearType.labelLarge)
                    }
                )
            }
        }
    }

    // ── 快捷操作（2×2 网格：官方 CompactButton 容器高 48dp，恰为腕上最小交互尺寸） ──
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(3),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WearCompactButton(
                onClick = onOpenBookmarks,
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "书签", style = WearType.labelLarge)
                }
            )
            WearCompactButton(
                onClick = onOpenRsvp,
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "速读", style = WearType.labelLarge)
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WearCompactButton(
                onClick = onChapterListClick,
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "目录", style = WearType.labelLarge)
                }
            )
            WearCompactButton(
                onClick = onOpenSearch,
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "搜索", style = WearType.labelLarge)
                }
            )
        }
    }
}

/** 滚动分区：自动滚屏开关（Wear SwitchButton）+ 速度档 */
@Composable
internal fun ScrollSettingsBlock(
    isAutoScrolling: Boolean,
    autoScrollSpeed: Float,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SettingSection("滚动", modifier = Modifier.staggeredEnter(4)) {
        WearSwitchButton(
            checked = isAutoScrolling,
            onCheckedChange = { onToggleAutoScroll() },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Column {
                    AnimatedValue(MenuValueText.autoScrollState(isAutoScrolling)) { label ->
                        WearText(text = label, style = WearType.bodyMedium)
                    }
                    WearText(
                        text = MenuValueText.autoScrollSpeed(autoScrollSpeed),
                        style = WearType.labelMedium,
                        color = colors.primary
                    )
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WearCompactButton(
                onClick = { onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed - 10f)) },
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "减速", style = WearType.labelLarge)
                }
            )
            WearCompactButton(
                onClick = { onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed + 10f)) },
                modifier = Modifier.weight(1f),
                colors = WearButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface
                ),
                content = {
                    WearText(text = "加速", style = WearType.labelLarge)
                }
            )
        }
    }
}

/** 显示分区：字号步进、亮度步进与四档亮度预设 */
@Composable
internal fun DisplaySettingsBlock(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    appBrightness: Float,
    onBrightnessChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SettingSection("显示", modifier = Modifier.staggeredEnter(5)) {
        SettingLine("字号", "$fontSize") {
            Stepper(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueProgression = 10..24
            )
        }
        HairlineDivider()
        // 亮度是「系统档 + 百分比档」混合量：系统档（负值）无对应刻度，
        // 步进器落在 [BrightnessManager.SYSTEM_STEP_BASE] 的百分比刻度上，
        // 回调再按 ±0.05 的语义换算回应用域亮度（系统档从 50% 起跳）。
        val effectiveBrightness = if (appBrightness < 0f) {
            BrightnessManager.SYSTEM_STEP_BASE
        } else {
            appBrightness
        }
        SettingLine("亮度", BrightnessManager.formatBrightnessText(appBrightness)) {
            Stepper(
                value = effectiveBrightness,
                onValueChange = { target ->
                    onBrightnessChange(BrightnessStep.step(appBrightness, target - effectiveBrightness))
                },
                valueRange = 0f..1f,
                steps = 19,
                valueLabel = BrightnessManager.formatBrightnessText(appBrightness)
            )
        }
        HairlineDivider()
        // 亮度预设 2×2 网格：避免四键并排把单键压到 48dp 以下
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrightnessPresetButton("系统", colors) { onBrightnessChange(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT) }
                BrightnessPresetButton("暗", colors) { onBrightnessChange(BrightnessManager.LEVEL_ULTRA_DARK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrightnessPresetButton("中", colors) { onBrightnessChange(BrightnessManager.LEVEL_2_MEDIUM) }
                BrightnessPresetButton("亮", colors) { onBrightnessChange(BrightnessManager.LEVEL_3_STRONG) }
            }
        }
    }
}

/** 亮度预设按钮：官方 CompactButton + tonal 配色，四键两行排布 */
@Composable
private fun RowScope.BrightnessPresetButton(
    label: String,
    colors: ColorScheme,
    onClick: () -> Unit
) {
    WearCompactButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = WearButtonDefaults.buttonColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface
        ),
        content = {
            WearText(text = label, style = WearType.labelLarge)
        }
    )
}

/** 偏好分区：主题（开浮层）/ 点按翻页 / 排版净化（开关）/ 字体 / 行距 / 字距的循环档位 */
@Composable
internal fun PreferenceSettingsBlock(
    themeMode: Int,
    onOpenThemePicker: () -> Unit,
    tapPageArea: Int,
    onCycleTapPageArea: () -> Unit,
    cleanTypography: Boolean,
    onToggleCleanTypography: () -> Unit,
    fontType: Int,
    onCycleFontType: () -> Unit,
    lineSpacing: Int,
    onCycleLineSpacing: () -> Unit,
    letterSpacing: Int,
    onCycleLetterSpacing: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val themeLabel = ThemeMode.fromValue(themeMode).label
    val tapLabel = MenuValueText.tapPageArea(TapPageArea.fromValue(tapPageArea))
    val fontLabel = MenuValueText.fontType(FontType.fromValue(fontType))
    SettingSection("偏好", modifier = Modifier.staggeredEnter(6)) {
        CycleLine("主题", themeLabel) { onOpenThemePicker() }
        HairlineDivider()
        CycleLine("点按翻页", tapLabel) { onCycleTapPageArea() }
        HairlineDivider()
        WearSwitchButton(
            checked = cleanTypography,
            onCheckedChange = { onToggleCleanTypography() },
            modifier = Modifier.fillMaxWidth(),
            label = {
                WearText(
                    text = "排版净化",
                    style = WearType.bodyMedium,
                    color = colors.onSurface
                )
            },
            secondaryLabel = {
                WearText(
                    text = MenuValueText.cleanTypography(cleanTypography),
                    style = WearType.labelMedium,
                    color = colors.primary
                )
            }
        )
        HairlineDivider()
        CycleLine("字体", fontLabel) { onCycleFontType() }
        HairlineDivider()
        CycleLine("行距", LineSpacingMode.fromValue(lineSpacing).label) { onCycleLineSpacing() }
        HairlineDivider()
        CycleLine("字距", LetterSpacingMode.fromValue(letterSpacing).label) { onCycleLetterSpacing() }
    }
}
