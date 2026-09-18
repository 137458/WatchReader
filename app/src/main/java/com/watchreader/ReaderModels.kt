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
