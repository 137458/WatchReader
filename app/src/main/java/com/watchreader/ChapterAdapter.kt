package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * 原生 ListView 背景守卫：View.setBackgroundColor 每次调用都会新建 ColorDrawable 并失效重绘，
 * 而 AndroidView 的 update 在每次父级重组都会执行 —— 仅在颜色真正变化时才设置
 */
internal fun applyListViewBg(view: View, color: Int) {
    val current = (view.background as? android.graphics.drawable.ColorDrawable)?.color
    if (current != color) {
        view.setBackgroundColor(color)
    }
    // 行间距由「背景色 divider」绘制，主题切换不同步会露出旧主题色
    val list = view as? ListView
    val dividerColor = (list?.divider as? android.graphics.drawable.ColorDrawable)?.color
    if (list != null && dividerColor != color) {
        list.divider = android.graphics.drawable.ColorDrawable(color)
    }
}

/**
 * 原生 View 行按压反馈：按下轻陷（alpha 0.65）、抬起/取消恢复。
 * 不消费事件，项点击回调不受影响 —— 与 Compose 侧 pressScale 对齐的"点了有动静"底线
 */
internal fun applyPressFeedback(view: View) {
    view.setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.animate().alpha(0.65f).setDuration(60L).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().alpha(1f).setDuration(120L).start()
        }
        false
    }
}

private class ChapterCardViewHolder(
    val indicatorTv: TextView,
    val titleTv: TextView,
    val tagTv: TextView
)

/**
 * 目录行适配器：入参只有"章节表 + 当前章 + 配色 + density"，之后的刷新一律走 [setData]。
 * 外部不再逐个字段赋值再自己判重，重绘时机由本模块自己守。
 */
internal class ChapterListAdapter(
    private var chapters: List<Chapter>,
    private var currentChapterIndex: Int,
    private var colorScheme: ColorScheme,
    private val density: Float
) : BaseAdapter() {

    // 缓存两级卡片背景，杜绝快速滚动期逐帧 GradientDrawable 分配引发的 GC 抖动
    private var cachedNormalBg: android.graphics.drawable.GradientDrawable? = null
    private var cachedCurrentBg: android.graphics.drawable.GradientDrawable? = null
    private var drawableCacheKey: ColorScheme? = null

    /**
     * 唯一数据入口：三项都没变则不下发重绘（父级每次重组都会调用，直接 notifyDataSetChanged
     * 会让目录整页 invalidate）。返回是否有实际变化调用方不需要，故不返回值。
     */
    fun setData(chapters: List<Chapter>, currentChapterIndex: Int, colorScheme: ColorScheme) {
        if (this.chapters === chapters &&
            this.currentChapterIndex == currentChapterIndex &&
            this.colorScheme === colorScheme
        ) {
            return
        }
        this.chapters = chapters
        this.currentChapterIndex = currentChapterIndex
        this.colorScheme = colorScheme
        notifyDataSetChanged()
    }

    private fun cachedDrawables(): Pair<android.graphics.drawable.GradientDrawable, android.graphics.drawable.GradientDrawable> {
        if (drawableCacheKey !== colorScheme || cachedNormalBg == null) {
            cachedNormalBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(colorScheme.surfaceContainerHigh.toArgb())
            }
            cachedCurrentBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * density
                // 当前章走预混容器角色：primaryContainer 实底 + primary 强调描边（零 alpha 糊底）
                setColor(colorScheme.primaryContainer.toArgb())
                setStroke((1.5f * density).toInt(), colorScheme.primary.toArgb())
            }
            drawableCacheKey = colorScheme
        }
        return cachedNormalBg!! to cachedCurrentBg!!
    }

    override fun getCount(): Int = chapters.size
    override fun getItem(position: Int): Any = chapters[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val context = parent.context
        val isCurrent = position == currentChapterIndex
        val chapter = chapters[position]
        val activeColor = colorScheme.primary.toArgb()
        val onSurfaceColor = colorScheme.onSurface.toArgb()
        // 当前章标题落在 primaryContainer 实底上，用配对内容色保证 AA（primary 在该底上不足 4.5:1）
        val onPrimaryContainerColor = colorScheme.onPrimaryContainer.toArgb()

        val container: FrameLayout
        val holder: ChapterCardViewHolder

        if (convertView == null) {
            container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = (52 * density).toInt()
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            val indicatorTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, (5 * density).toInt(), 0)
            }
            textLayout.addView(indicatorTv)

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textLayout.addView(titleTv)

            val tagTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding((6 * density).toInt(), (1.5f * density).toInt(), (6 * density).toInt(), (1.5f * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    // 标签实底 primary + 配对 onPrimary 文字（预混角色，替代 25% alpha 糊底）
                    setColor(colorScheme.primary.toArgb())
                    // 胶囊标签：圆角取行内文字高度的半径，替代原 6dp 小圆角
                    cornerRadius = 9 * density
                }
            }
            textLayout.addView(tagTv)

            container.addView(textLayout)
            holder = ChapterCardViewHolder(indicatorTv, titleTv, tagTv)
            container.tag = holder
        } else {
            container = convertView as FrameLayout
            holder = container.tag as ChapterCardViewHolder
        }

        val (normalBg, currentBg) = cachedDrawables()
        container.background = if (isCurrent) currentBg else normalBg
        applyPressFeedback(container)

        holder.titleTv.text = chapter.title

        if (isCurrent) {
            holder.indicatorTv.visibility = View.VISIBLE
            holder.indicatorTv.text = "●"
            holder.indicatorTv.setTextColor(activeColor)
            holder.titleTv.setTextColor(onPrimaryContainerColor)
            holder.titleTv.typeface = Typeface.DEFAULT_BOLD

            holder.tagTv.visibility = View.VISIBLE
            holder.tagTv.text = "正在读"
            holder.tagTv.setTextColor(colorScheme.onPrimary.toArgb())
        } else {
            holder.indicatorTv.visibility = View.GONE
            holder.titleTv.setTextColor(onSurfaceColor)
            holder.titleTv.typeface = Typeface.DEFAULT

            holder.tagTv.visibility = View.GONE
        }

        return container
    }
}
