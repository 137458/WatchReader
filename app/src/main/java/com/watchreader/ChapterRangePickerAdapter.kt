package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * 快速选委屈浮层的行文案与索引映射（纯参数口径，由 BookshelfAndRangePickerTest 锁定）。
 *
 * 从适配器里剥出的原因：这三条判定是浮层唯一的业务逻辑（哪一段包住当前章、行尾强调、
 * 首次定位偏移），却原先混在 getView/factory 里只能靠真机看。剥出来后纯 JVM 可断言，
 * 视图层只剩「把结果写进 TextView」。
 */
internal object RangePickerStyle {

    /** 当前卷的行尾强调（与旧内联模板 "${range.label} • 正在读" 一致） */
    const val CURRENT_SUFFIX = " • 正在读"

    /** 章节是否落在本段内（闭区间，段由 generateChapterRanges 保证连续不重叠） */
    fun isCurrent(range: ChapterRange, currentChapterIndex: Int): Boolean =
        currentChapterIndex in range.startIndex..range.endIndex

    /** 行文案：当前卷追加强调后缀 */
    fun rowLabel(range: ChapterRange, currentChapterIndex: Int): String =
        if (isCurrent(range, currentChapterIndex)) range.label + CURRENT_SUFFIX else range.label

    /** 当前章落在第几段；空分卷、越界索引、未打开书（-1）都返回 -1 */
    fun activeRangeIndex(ranges: List<ChapterRange>, currentChapterIndex: Int): Int =
        ranges.indexOfFirst { isCurrent(it, currentChapterIndex) }

    /**
     * 浮层首次定位：让当前卷上方留一行（旧实现 (active - 1).coerceAtLeast(0) 的口径）。
     * 找不到当前段时按第一段定位（旧实现先 coerceAtLeast(0) 再判 in indices）。
     */
    fun initialSelection(ranges: List<ChapterRange>, currentChapterIndex: Int): Int {
        val active = activeRangeIndex(ranges, currentChapterIndex).coerceAtLeast(0)
        if (active !in ranges.indices) return 0
        return (active - 1).coerceAtLeast(0)
    }
}

/**
 * 选卷行适配器：入参只有「分卷表 + 当前章 + 配色 + density」，刷新一律走 [setData]。
 * 背景 Drawable 每次实例只建一次（与旧内联适配器一致：浮层关闭即销毁，重新打开时重建）。
 */
internal class ChapterRangePickerAdapter(
    private var ranges: List<ChapterRange>,
    private var currentChapterIndex: Int,
    private val colorScheme: ColorScheme,
    private val density: Float
) : BaseAdapter() {

    // 范围行卡片背景缓存（普通态 / 当前态）
    private var normalBg: GradientDrawable? = null
    private var currentBg: GradientDrawable? = null

    /**
     * 唯一数据入口：分卷表与当前章都没变则不下发重绘。
     * 旧实现用 intArrayOf(章节总数, 当前章) 在屏幕里手写判重，现在判重归属适配器。
     */
    fun setData(ranges: List<ChapterRange>, currentChapterIndex: Int) {
        if (this.ranges === ranges && this.currentChapterIndex == currentChapterIndex) return
        this.ranges = ranges
        this.currentChapterIndex = currentChapterIndex
        notifyDataSetChanged()
    }

    private fun drawables(): Pair<GradientDrawable, GradientDrawable> {
        val cachedNormal = normalBg
        val cachedCurrent = currentBg
        if (cachedNormal != null && cachedCurrent != null) return cachedNormal to cachedCurrent

        val normal = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(colorScheme.surfaceContainerHigh.toArgb())
        }
        val current = GradientDrawable().apply {
            cornerRadius = 12 * density
            // 当前卷走预混容器角色：primaryContainer 底 + primary 强调描边（零 alpha）
            setColor(colorScheme.primaryContainer.toArgb())
            setStroke((1.5f * density).toInt(), colorScheme.primary.toArgb())
        }
        normalBg = normal
        currentBg = current
        return normal to current
    }

    override fun getCount(): Int = ranges.size
    override fun getItem(position: Int): Any = ranges[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val range = ranges[position]
        val isCurrentRange = RangePickerStyle.isCurrent(range, currentChapterIndex)

        val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }

        val (rangeNormalBg, rangeCurrentBg) = drawables()
        tv.background = if (isCurrentRange) rangeCurrentBg else rangeNormalBg
        tv.text = RangePickerStyle.rowLabel(range, currentChapterIndex)
        // 当前卷文字用容器配对内容色（onPrimaryContainer），保证 container 底上达 AA
        tv.setTextColor(
            if (isCurrentRange) colorScheme.onPrimaryContainer.toArgb()
            else colorScheme.onSurfaceVariant.toArgb()
        )
        tv.typeface = if (isCurrentRange) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        applyPressFeedback(tv)
        return tv
    }
}

/**
 * 选卷浮层的列表装配：表冠连续滚动、选中即回调、首次定位让当前卷上方留一行。
 * [onRangePicked] 只在命中有效索引时回调，触感（CLOCK_TICK）在回调前给。
 */
internal fun createRangePickerListView(
    context: Context,
    ranges: List<ChapterRange>,
    currentChapterIndex: Int,
    colorScheme: ColorScheme,
    onRangePicked: (ChapterRange) -> Unit
): ListView {
    val density = context.resources.displayMetrics.density
    val padH = (26 * density).toInt()
    val padTop = (48 * density).toInt()
    val padBottom = (54 * density).toInt()

    val listView = ListView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = false
        divider = null
        dividerHeight = (6 * density).toInt()
        setBackgroundColor(colorScheme.background.toArgb())
        setPadding(padH, padTop, padH, padBottom)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    // 表冠物理旋转无缝滚动（统一管线扩展）；实例经捕获数组交给 Compose 侧，
    // 浮层打开时注册的表冠目标抢占顶层管线，兜底焦点路由不再有机会命中被浮层盖住的章节列表
    listView.bindCrownScroll(context)
    listView.adapter = ChapterRangePickerAdapter(ranges, currentChapterIndex, colorScheme, density)

    listView.setOnItemClickListener { _, _, position, _ ->
        if (position in ranges.indices) {
            RotaryHapticManager.performScrollTick(context, null)
            onRangePicked(ranges[position])
        }
    }

    listView.post {
        listView.requestFocus()
        listView.setSelection(RangePickerStyle.initialSelection(ranges, currentChapterIndex))
    }

    return listView
}
