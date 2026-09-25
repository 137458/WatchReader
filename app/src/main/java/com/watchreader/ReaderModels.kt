package com.watchreader

/**
 * 主题模式
 */
enum class ThemeMode(val value: Int, val title: String) {
    PARCHMENT(0, "📜 主题: 羊皮纸浅色"),
    DARK(1, "🌌 主题: 极光深色"),
    RED_NIGHT(2, "🌙 主题: 纯黑深红夜视");

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.firstOrNull { it.value == value } ?: PARCHMENT
    }
}

/**
 * 点按翻页热区划分模式
 */
enum class TapPageArea(val value: Int, val title: String) {
    TOP_BOTTOM(0, "👆 点按: 上下分屏翻页"),
    LEFT_RIGHT(1, "👉 点按: 左右分屏翻页"),
    DISABLED(2, "🚫 点按: 关闭点按翻页");

    companion object {
        fun fromValue(value: Int): TapPageArea = entries.firstOrNull { it.value == value } ?: TOP_BOTTOM
    }
}

/**
 * 字体样式
 */
enum class FontType(val value: Int, val title: String) {
    SANS_SERIF(0, "🔤 字体: 系统黑体 (无衬线)"),
    SERIF(1, "🔤 字体: 系统衬线体 (宋体)");

    companion object {
        fun fromValue(value: Int): FontType = entries.firstOrNull { it.value == value } ?: SANS_SERIF
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

    fun formatText(durationSec: Long): String {
        val safeSec = maxOf(0L, durationSec)
        val hours = safeSec / 3600
        val mins = (safeSec % 3600) / 60
        return "${hours}小时 ${mins}分钟"
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
 * 段内前 2 个字符为全角缩进（映射回段首原文位置），其后与原文逐字符 1:1。
 * 空映射数组时退化为恒等映射（正文坐标 = 原文坐标）。
 */
object ChapterOffsetMapper {

    /** 正文坐标 → 原文切片坐标（结果钳制在 [0, rawLength]） */
    fun bodyToRaw(
        bodyIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int,
        rawLength: Int
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
        // 段内前 2 字符为全角缩进，映射回段首原文位置；其后逐字符 1:1
        val raw = rawParaStarts[found] + maxOf(0, inPara - 2)
        val upper = if (found < rawParaStarts.lastIndex) rawParaStarts[found + 1] - 1 else rawLength
        return raw.coerceIn(0, minOf(rawLength, upper))
    }

    /** 原文切片坐标 → 正文坐标（结果钳制在 [0, bodyLength]） */
    fun rawToBody(
        rawIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int
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
        // 段落正文内容区终点（不含段落间换行）；原文行尾空白/空行吸附到段末字符
        val contentEnd = if (found < bodyParaStarts.lastIndex) bodyParaStarts[found + 1] - 2 else bodyLength
        val body = bodyParaStarts[found] + 2 + inRaw
        return body.coerceIn(bodyParaStarts[found], (contentEnd - 1).coerceAtLeast(bodyParaStarts[found]))
            .coerceIn(0, bodyLength)
    }
}
