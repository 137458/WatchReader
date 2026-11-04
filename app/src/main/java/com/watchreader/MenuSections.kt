package com.watchreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 菜单分区内容：阅读控制 + 滚动 / 显示 / 偏好三段设置。
 *
 * 排版全部交给 MenuBlocks.kt 的骨架（卡片、设置行、步进器、循环行），本文件只决定
 * 「哪个分区有哪些行、行里的值怎么来」。行里那些「值 → 文案」的换算不在组合函数中现算，
 * 而是收进 [MenuValueText] 与 [BrightnessStep] 两个纯对象：它们不碰 Compose 运行时，
 * 因此可以在 JVM 测试里逐档锁定文案（MenuSectionLogicTest）。
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
 * 阅读控制块：当前章节主卡 + 章节导航 + 2×2 快捷操作。
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
            PillButton("书签", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenBookmarks)
            PillButton("速读", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenRsvp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("目录", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onChapterListClick)
            PillButton("搜索", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenSearch)
        }
    }
}

/** 滚动分区：自动滚屏开关 + 速度档 */
@Composable
internal fun ScrollSettingsBlock(
    isAutoScrolling: Boolean,
    autoScrollSpeed: Float,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SettingSection("滚动", modifier = Modifier.staggeredEnter(4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                AnimatedValue(MenuValueText.autoScrollState(isAutoScrolling)) { label ->
                    Text(label, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                AnimatedValue(MenuValueText.autoScrollSpeed(autoScrollSpeed)) { speed ->
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
                onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed - 10f))
            }
            PillButton("加速", Modifier.weight(1f), verticalPadding = 8.dp) {
                onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed + 10f))
            }
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
    SettingSection("显示", modifier = Modifier.staggeredEnter(5)) {
        SettingLine("字号", "$fontSize") {
            Stepper(
                onMinus = { onFontSizeChange(fontSize - 1) },
                onPlus = { onFontSizeChange(fontSize + 1) }
            )
        }
        HairlineDivider()
        SettingLine("亮度", BrightnessManager.formatBrightnessText(appBrightness)) {
            Stepper(
                onMinus = { onBrightnessChange(BrightnessStep.step(appBrightness, -0.05f)) },
                onPlus = { onBrightnessChange(BrightnessStep.step(appBrightness, 0.05f)) }
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

/** 偏好分区：主题（开浮层）/ 点按翻页 / 排版净化 / 字体 / 行距 / 字距的循环档位 */
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
    val themeLabel = ThemeMode.fromValue(themeMode).label
    val tapLabel = MenuValueText.tapPageArea(TapPageArea.fromValue(tapPageArea))
    val fontLabel = MenuValueText.fontType(FontType.fromValue(fontType))
    SettingSection("偏好", modifier = Modifier.staggeredEnter(6)) {
        CycleLine("主题", themeLabel) { onOpenThemePicker() }
        HairlineDivider()
        CycleLine("点按翻页", tapLabel) { onCycleTapPageArea() }
        HairlineDivider()
        CycleLine("排版净化", MenuValueText.cleanTypography(cleanTypography)) {
            onToggleCleanTypography()
        }
        HairlineDivider()
        CycleLine("字体", fontLabel) { onCycleFontType() }
        HairlineDivider()
        CycleLine("行距", LineSpacingMode.fromValue(lineSpacing).label) { onCycleLineSpacing() }
        HairlineDivider()
        CycleLine("字距", LetterSpacingMode.fromValue(letterSpacing).label) { onCycleLetterSpacing() }
    }
}
