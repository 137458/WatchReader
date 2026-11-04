package com.watchreader

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/**
 * 主题模式（Material 3 亮 / 暗双档，持久化值为 Int）
 *
 * 历史值兼容：0 羊皮纸 / 4 晴空蓝 → LIGHT；1 极光黑 / 2 红光夜视 / 3 深空蓝 → DARK。
 * 非法值回落 LIGHT（与旧版回落羊皮纸的明色取向一致）。
 */
enum class ThemeMode(val value: Int) {
    LIGHT(0),
    DARK(1);

    /** 是否属于深色系——由主题本身派生，杜绝第二个"深色"状态量 */
    val isDark: Boolean get() = this == DARK

    /** 主题显示名 */
    val label: String
        get() = when (this) {
            LIGHT -> "浅色"
            DARK -> "深色"
        }

    companion object {
        fun fromValue(value: Int): ThemeMode = when (value) {
            0, 4 -> LIGHT
            1, 2, 3 -> DARK
            else -> LIGHT
        }
    }
}

/**
 * 点按翻页热区划分模式
 */
enum class TapPageArea(val value: Int) {
    TOP_BOTTOM(0),
    LEFT_RIGHT(1),
    DISABLED(2);

    companion object {
        fun fromValue(value: Int): TapPageArea = entries.firstOrNull { it.value == value } ?: TOP_BOTTOM
    }
}

/**
 * 字体样式
 */
enum class FontType(val value: Int) {
    SANS_SERIF(0),
    SERIF(1);

    companion object {
        fun fromValue(value: Int): FontType = entries.firstOrNull { it.value == value } ?: SANS_SERIF
    }
}

/**
 * 正文行距档位（TextView lineSpacingMultiplier；STANDARD = 历史默认 1.45）
 */
enum class LineSpacingMode(val value: Int, val label: String, val multiplier: Float) {
    COMPACT(0, "紧凑", 1.25f),
    STANDARD(1, "标准", 1.45f),
    RELAXED(2, "宽松", 1.7f),
    LOOSE(3, "疏朗", 1.95f);

    companion object {
        fun fromValue(value: Int): LineSpacingMode = entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

/**
 * 正文字距档位（TextView letterSpacing，em；STANDARD = 历史默认 0）
 */
enum class LetterSpacingMode(val value: Int, val label: String, val em: Float) {
    STANDARD(0, "标准", 0f),
    RELAXED(1, "宽松", 0.03f),
    LOOSE(2, "疏朗", 0.06f);

    companion object {
        fun fromValue(value: Int): LetterSpacingMode = entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

/**
 * 单击点按动作
 */
enum class TapAction {
    PAGE_UP,
    PAGE_DOWN,
    SHOW_MENU
}

/**
 * 点按翻页与视觉锚点几何计算纯函数引擎
 */
object TapPageHelper {

    /**
     * 解析点按动作
     */
    fun resolveTapAction(
        x: Float,
        y: Float,
        screenWidth: Float,
        screenHeight: Float,
        area: TapPageArea
    ): TapAction {
        return when (area) {
            TapPageArea.TOP_BOTTOM -> {
                when {
                    y < screenHeight * 0.35f -> TapAction.PAGE_UP
                    y > screenHeight * 0.65f -> TapAction.PAGE_DOWN
                    else -> TapAction.SHOW_MENU
                }
            }
            TapPageArea.LEFT_RIGHT -> {
                when {
                    x < screenWidth * 0.35f -> TapAction.PAGE_UP
                    x > screenWidth * 0.65f -> TapAction.PAGE_DOWN
                    else -> TapAction.SHOW_MENU
                }
            }
            TapPageArea.DISABLED -> TapAction.SHOW_MENU
        }
    }

    fun resolveTapAction(
        x: Float,
        y: Float,
        screenWidth: Float,
        screenHeight: Float,
        areaValue: Int
    ): TapAction {
        return resolveTapAction(x, y, screenWidth, screenHeight, TapPageArea.fromValue(areaValue))
    }

    /**
     * 计算点按整屏翻页位移并自适应扣除 32dp 视觉锚点重叠区
     */
    fun calculateScrollDistance(screenHeight: Float, density: Float): Int {
        val overlap = (32 * density).toInt()
        return maxOf((100 * density).toInt(), (screenHeight - overlap).toInt())
    }
}

/**
 * 阅读时长格式化工具
 */
object ReadDurationFormatter {

    fun format(durationSec: Long): String {
        val safeSec = maxOf(0L, durationSec)
        val hours = safeSec / 3600
        val mins = (safeSec % 3600) / 60
        return "⏱️ 累计阅读: ${hours}小时 ${mins}分钟"
    }
}

/**
 * RSVP 表冠调速阻尼器（纯算法，时钟由调用方注入以便测试）
 *
 * 强阻尼滤波三要素（与硬件表冠页 CrownScrollHelper 同思路，[ADR-007]）：
 * 1. 空闲复位：[resetIdleMs] 内无旋转则清空累加器，一次拨动视作独立手势；
 * 2. 门限分档：小幅刻度（|delta| < [coarseDeltaBoundary]）累计 [fineStepThreshold] 计一步，
 *    快速甩动单次 [coarseStepThreshold] 即计一步；
 * 3. 时间窗节流：两步之间至少间隔 [minStepIntervalMs]，连转不爆冲。
 */
class RsvpRotaryDamper(
    private val resetIdleMs: Long = 400L,
    private val fineStepThreshold: Float = 2.5f,
    private val coarseStepThreshold: Float = 75f,
    private val coarseDeltaBoundary: Float = 5f,
    private val minStepIntervalMs: Long = 75L
) {
    private var accumulator = 0f
    private var lastRotaryTimeMs = 0L
    private var lastStepTimeMs = 0L

    /**
     * 喂入一次表冠增量
     * @return 本步速度变化量（字/分，含方向），未达步进门限时返回 0
     */
    fun onDelta(delta: Float, nowMs: Long, stepPerTick: Float = 10f): Float {
        if (nowMs - lastRotaryTimeMs > resetIdleMs) {
            accumulator = 0f
        }
        lastRotaryTimeMs = nowMs
        accumulator += delta

        val threshold = if (abs(delta) < coarseDeltaBoundary) fineStepThreshold else coarseStepThreshold
        if (abs(accumulator) >= threshold && nowMs - lastStepTimeMs >= minStepIntervalMs) {
            val direction = if (accumulator > 0) 1 else -1
            accumulator = 0f
            lastStepTimeMs = nowMs
            return direction * stepPerTick
        }
        return 0f
    }
}

/**
 * 阅读页的组合输入：一页需要知道的全部事实。
 *
 * 此前阅读页有 23 个平铺参数，父级 [ReaderUiState] 任意字段变化（阅读时长 tick、
 * 传书进度、搜索进度）都会重新进入阅读页的组合，逐项 setTextSize / setTextColor
 * 各自触发整章 StaticLayout 重建。收成一个整体后，父级只需比较一次：相等即整页跳过。
 *
 * 标记 [Immutable] 是承诺而非猜测——本类每次都由当前状态重新构造，构造后不再被改写。
 */
@Immutable
data class ReaderPaneState(
    val chapterContent: ChapterContent?,
    val initialCharOffset: Int,
    val totalChapters: Int,
    val fullTextLength: Int,
    val fontSize: Int,
    val autoScrollSpeed: Float,
    val isAutoScrolling: Boolean,
    val tapPageArea: Int,
    val fontType: Int,
    val lineSpacing: Int,
    val letterSpacing: Int,
    val chapters: List<Chapter>,
    val currentChapterIndex: Int,
    val covered: Boolean
)

/**
 * 阅读页的回调出口：与 [ReaderPaneState] 分离，使常驻阅读页在状态不变时
 * 不会因为父级重新构造 lambda 而失效跳过比较。
 */
@Immutable
class ReaderPaneActions(
    val onCharOffsetChange: (Int) -> Unit,
    val onNextChapter: () -> Unit,
    val onPrevChapter: () -> Unit,
    val onLongPress: () -> Unit,
    val onBack: () -> Unit,
    val onAutoScrollToggle: () -> Unit,
    val onAutoScrollSpeedChange: (Float) -> Unit,
    val onSeekChapter: (Int) -> Unit,
    val onFlushReadingPosition: () -> Unit
)

