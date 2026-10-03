package com.watchreader

import kotlin.math.abs

/**
 * 主题模式
 */
enum class ThemeMode(val value: Int) {
    PARCHMENT(0),
    DARK(1),
    RED_NIGHT(2);

    /** 是否属于深色系（极光黑 / 红光夜视）—— 由主题本身派生，杜绝第二个"深色"状态量 */
    val isDark: Boolean get() = this != PARCHMENT

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.firstOrNull { it.value == value } ?: PARCHMENT
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
 * 章节偏移精确映射引擎：正文排版坐标 ↔ 原文切片坐标
 *
 * 背景约束：formattedBody 为每段追加了全角缩进并重排换行，正文字符索引
 * 不再等于原文偏移。持久化的阅读位置必须落在原文坐标域，否则全局进度、
 * 跨章节定位与 DataStore 保存值全部失真。
 *
 * 映射模型：段落 i 的正文起点 bodyParaStarts[i] 对应原文 rawParaStarts[i]；
 * 段内前 indentChars 个字符为格式化时补入的缩进（映射回段首原文位置），其后与原文逐字符 1:1。
 * 空映射数组时退化为恒等映射（正文坐标 = 原文坐标）。
 */
object ChapterOffsetMapper {

    /** 正文坐标 → 原文切片坐标（结果钳制在 [0, rawLength]） */
    fun bodyToRaw(
        bodyIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int,
        rawLength: Int,
        indentChars: Int = 2
    ): Int {
        val safeBody = bodyIndex.coerceIn(0, bodyLength)
        // 空映射数组退化为恒等映射（EPUB 等未提供映射的路径，正文坐标即原文坐标）
        if (bodyParaStarts.isEmpty() || rawParaStarts.size != bodyParaStarts.size) {
            return safeBody
        }
        if (safeBody < bodyParaStarts[0]) {
            return rawParaStarts[0].coerceIn(0, rawLength)
        }

        // 二分定位：最后一个 bodyParagraphStarts[i] <= safeBody
        var lo = 0
        var hi = bodyParaStarts.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bodyParaStarts[mid] <= safeBody) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val inPara = safeBody - bodyParaStarts[found]
        // 段内前 indentChars 字符为补入缩进，映射回段首原文位置；其后逐字符 1:1
        val raw = rawParaStarts[found] + maxOf(0, inPara - indentChars)
        val upper = if (found < rawParaStarts.lastIndex) rawParaStarts[found + 1] - 1 else rawLength
        return raw.coerceIn(0, minOf(rawLength, upper))
    }

    /** 原文切片坐标 → 正文坐标（结果钳制在 [0, bodyLength]） */
    fun rawToBody(
        rawIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int,
        indentChars: Int = 2
    ): Int {
        // 入参为原文坐标域，不做正文长度预钳制（结果统一收敛到 [0, bodyLength]）
        val safeRaw = rawIndex
        if (bodyParaStarts.isEmpty() || rawParaStarts.size != bodyParaStarts.size) {
            return rawIndex.coerceIn(0, bodyLength)
        }
        if (safeRaw < rawParaStarts[0]) {
            return 0
        }

        var lo = 0
        var hi = rawParaStarts.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rawParaStarts[mid] <= safeRaw) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val inRaw = safeRaw - rawParaStarts[found]
        // 段落正文内容区终点（不含段落间换行与补入缩进）；原文行尾空白/空行吸附到段末字符
        val contentEnd = if (found < bodyParaStarts.lastIndex) bodyParaStarts[found + 1] - indentChars else bodyLength
        val body = bodyParaStarts[found] + indentChars + inRaw
        return body.coerceIn(bodyParaStarts[found], (contentEnd - 1).coerceAtLeast(bodyParaStarts[found]))
            .coerceIn(0, bodyLength)
    }
}
