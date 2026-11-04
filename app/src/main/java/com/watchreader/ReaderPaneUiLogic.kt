package com.watchreader

import kotlin.math.abs

/**
 * 阅读页纯判定层：只吃数字与布尔，不碰 Android 类型。
 *
 * 把原先埋在 672 行 ReaderPane 里的判定（滚动偏移换算、引擎同步、位置恢复、
 * 常亮超时、进度文案、表冠抖动门限）收成可被纯 JVM 直接驱动的 module。
 * 调用方仍是原来的 Composable / View 监听器，逐语句等价替换，行为未变。
 */
internal object ReaderPaneLogic {

    /** 静态阅读无交互后自动释放常亮的时间窗 */
    const val KEEP_SCREEN_ON_TIMEOUT_MS = 5 * 60 * 1000L

    /** 表冠单次调速步长（px/s） */
    const val AUTO_SCROLL_SPEED_STEP = 5f

    /** 表冠旋转量的噪声门限：低于此值视为抖动，不滚动 */
    const val CROWN_SCROLL_EPSILON = 0.001f

    /** 自动滚屏调速的噪声门限：低于此值视为抖动，不调速 */
    const val CROWN_ADJUST_EPSILON = 0.05f

    /** 章节标题顶部避让间距：有上一章卡片时贴卡片，否则让出顶部安全区 */
    const val TITLE_PADDING_DP_WITH_PREV = 8
    const val TITLE_PADDING_DP_WITHOUT_PREV = 20

    /** 自动滚屏引擎的目标动作 */
    enum class EngineAction { START, STOP, NONE }

    /** 常亮超时只在静态阅读时上膛；自动滚屏期间永久常亮 */
    fun shouldArmKeepScreenOnTimeout(isAutoScrolling: Boolean): Boolean = !isAutoScrolling

    /**
     * 引擎同步：覆盖页压顶一律停转，否则按开关与运行态收敛。
     * 与收口前 update 块里的 if/else-if 链逐分支等价。
     */
    fun engineSyncAction(covered: Boolean, isAutoScrolling: Boolean, engineRunning: Boolean): EngineAction {
        if (covered) return if (engineRunning) EngineAction.STOP else EngineAction.NONE
        if (isAutoScrolling) return if (engineRunning) EngineAction.NONE else EngineAction.START
        return if (engineRunning) EngineAction.STOP else EngineAction.NONE
    }

    /** 行域是否可用：排版行已生成且正文有高度，两条件缺一不可 */
    fun isLineDomainReady(layoutLineCount: Int, bodyHeightPx: Int): Boolean =
        layoutLineCount > 0 && bodyHeightPx > 0

    /** 滚动视口换算到正文内部的纵向位置，钳在正文高度区间内（末行取 height-1） */
    fun clampBodyScrollY(scrollY: Int, bodyTop: Int, bodyHeightPx: Int): Int =
        (scrollY - bodyTop).coerceIn(0, maxOf(0, bodyHeightPx - 1))

    /** 行首字符偏移钳到正文长度内 */
    fun clampCharOffset(charOffset: Int, bodyLength: Int): Int =
        charOffset.coerceIn(0, bodyLength)

    /** 排版未就绪时的线性比例回退：按滚动比例折算原文偏移 */
    fun rawOffsetByRatio(
        scrollY: Int,
        bodyTop: Int,
        bodyHeightPx: Int,
        startCharOffset: Int,
        endCharOffset: Int
    ): Int {
        val bodyHeight = maxOf(1, bodyHeightPx)
        val relativeY = (scrollY - bodyTop).coerceIn(0, bodyHeight)
        val scrollRatio = relativeY.toFloat() / bodyHeight
        val chapterLen = endCharOffset - startCharOffset
        return startCharOffset + (chapterLen * scrollRatio).toInt()
    }

    /**
     * 换章分支的恢复偏移：同章内仅换排版时以实时偏移为准（否则换字体会把位置弹回章首），
     * 跨章或偏移落在本章区间外时退回章节入口偏移。
     */
    fun chapterChangeRestoreOffset(
        liveOffset: Int,
        sameChapter: Boolean,
        hasContent: Boolean,
        startCharOffset: Int,
        endCharOffset: Int,
        initialCharOffset: Int
    ): Int =
        if (sameChapter && hasContent && liveOffset in startCharOffset..endCharOffset) {
            liveOffset
        } else {
            initialCharOffset
        }

    /** 章节标题的顶部避让 dp：有上一章卡片时贴卡片，否则让出顶部安全区 */
    fun chapterTitleTopPaddingDp(hasPrevChapter: Boolean): Int =
        if (hasPrevChapter) TITLE_PADDING_DP_WITH_PREV else TITLE_PADDING_DP_WITHOUT_PREV

    /** 全书进度百分比：整数截断后钳在 0..100；总长为 0 时视为 0 */
    fun progressPercent(currentOffset: Int, fullTextLength: Int): Int =
        if (fullTextLength > 0) {
            ((currentOffset.toFloat() / fullTextLength) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

    /** 底部进度文案：多章书带章节号，单章书只报百分比 */
    fun progressText(chapterNum: Int, totalChapters: Int, percent: Int): String =
        if (totalChapters > 1) {
            "第 $chapterNum/$totalChapters 章 · $percent%"
        } else {
            "$percent%"
        }

    /** 常规阅读的表冠滚动量是否有效 */
    fun isCrownScrollDelta(delta: Float): Boolean = abs(delta) > CROWN_SCROLL_EPSILON

    /** 自动滚屏期间的表冠调速量是否有效 */
    fun isCrownAdjustDelta(delta: Float): Boolean = abs(delta) > CROWN_ADJUST_EPSILON

    /** 调速只看方向：正向加速一档，反向减速一档 */
    fun autoScrollSpeedDelta(delta: Float): Float =
        if (delta > 0) AUTO_SCROLL_SPEED_STEP else -AUTO_SCROLL_SPEED_STEP
}
