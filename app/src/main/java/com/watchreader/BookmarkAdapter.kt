package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 两段式删除的待确认状态：从适配器里剥出来的唯一有状态单元。
 *
 * 不变量：同一时刻至多一个待删 id，且它只在三种转移之间切换 ——
 * 首次请求（[request]）进入待确认、超时/点行体（[cancel]）回滚、再按删除键（[confirm]）放行。
 * 定时器仍是适配器侧的 Handler（Android 依赖不进这里），本类型只管 id 归属，
 * 因此可以在纯 JVM 上断言"撤销后回到原状"。
 */
internal class BookmarkDeleteGate {

    companion object {
        /** 待确认窗口：与书架删书同档，3.2s 无操作自动回滚 */
        const val REVERT_DELAY_MS = 3200L
    }

    private var pending: String? = null

    val pendingId: String? get() = pending

    fun isPending(id: String): Boolean = pending == id

    /** 进入待确认：调用方随后负责挂起回滚定时器与重绘 */
    fun request(id: String) {
        pending = id
    }

    /** 回滚：返回 true 表示确实有一个挂起项被撤销（调用方据此决定是否重绘） */
    fun cancel(): Boolean {
        if (pending == null) return false
        pending = null
        return true
    }

    /** 放行：交出待删 id 并清空，返回 null 表示当前没有待确认项 */
    fun confirm(): String? {
        val id = pending ?: return null
        pending = null
        return id
    }
}

/**
 * 书签删除键的两段式视觉口径（纯参数，由 BookmarkRowStyleTest 锁定）：
 * 热区恒 ≥48dp（ADR-018 自定义可点组件最小触控尺寸），视觉只是文字不铺底色，
 * 因此扩热区不改观感；待确认态放宽宽度容纳「确认删除」四字。
 */
internal data class BookmarkDeleteVisual(
    val label: String,
    val minWidthDp: Int,
    val touchDp: Int,
    val bold: Boolean
)

internal object BookmarkRowStyle {
    /** ADR-018：自定义可点组件最小触控尺寸 */
    const val TOUCH_DP = 48

    /** 待确认态宽度：四字文案不折行 */
    private const val PENDING_WIDTH_DP = 76

    /** 摘要单行最多展示字数，超出才补省略号 */
    private const val SNIPPET_MAX_CHARS = 30

    fun deleteVisual(isPending: Boolean) = BookmarkDeleteVisual(
        label = if (isPending) "确认删除" else "✕",
        minWidthDp = if (isPending) PENDING_WIDTH_DP else TOUCH_DP,
        touchDp = TOUCH_DP,
        bold = isPending
    )

    /**
     * 书签摘要单行文案：空摘要返回 null（整行不占位）；只有真正被截断时才补省略号
     * （旧实现无条件追加「…」，完整短摘要被显示成疑似未完的片段）。
     */
    fun snippetLabel(snippet: String): String? {
        if (snippet.isEmpty()) return null
        val clipped = snippet.length > SNIPPET_MAX_CHARS
        val body = if (clipped) snippet.take(SNIPPET_MAX_CHARS) else snippet
        return if (clipped) "“$body…”" else "“$body”"
    }
}

/** 只在颜色真正变化时写 TextView 文字色（复用时避免每帧 invalidate） */
private fun TextView.setTextColorIfChanged(color: Int) {
    if (currentTextColor != color) setTextColor(color)
}

/**
 * 书签行视图缓存：ListView 复用 convertView 时按 tag 取回，绑定阶段不再新建任何 View/Drawable
 * （旧实现每次 getView 重建 4 个 View + 3 个 GradientDrawable，滚动期逐帧分配引发 GC 抖动）
 */
private class BookmarkRowViewHolder(
    val container: LinearLayout,
    val background: android.graphics.drawable.GradientDrawable,
    val titleTv: TextView,
    val snippetTv: TextView,
    val dateTv: TextView,
    val delTv: TextView
) {
    /** 上次写入的背景色：颜色没变就不 setColor，避免无谓失效重绘 */
    var backgroundArgb: Int = Int.MIN_VALUE
}

/**
 * 书签行适配器：入参只有"书签表 + 配色 + density + 两个回调"，之后的刷新一律走 [setData]；
 * 两段式删除的 id 归属收在 [BookmarkDeleteGate]，本类只管定时器与重绘。
 */
internal class BookmarkListAdapter(
    private var bookmarks: List<Bookmark>,
    private var colorScheme: ColorScheme,
    val density: Float,
    val onBookmarkClick: (Bookmark) -> Unit,
    val onDeleteBookmark: (Bookmark) -> Unit
) : BaseAdapter() {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val gate = BookmarkDeleteGate()
    private val revertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val revertRunnable = Runnable {
        if (gate.cancel()) {
            notifyDataSetChanged()
        }
    }

    /**
     * 唯一数据入口：书签表与配色都没变则不下发重绘（父级每次重组都会调用）
     */
    fun setData(bookmarks: List<Bookmark>, colorScheme: ColorScheme) {
        if (this.bookmarks === bookmarks && this.colorScheme === colorScheme) return
        this.bookmarks = bookmarks
        this.colorScheme = colorScheme
        notifyDataSetChanged()
    }

    override fun getCount(): Int = bookmarks.size
    override fun getItem(position: Int): Any = bookmarks[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val bm = bookmarks[position]
        val isPending = gate.isPending(bm.id)

        // convertView 复用的唯一入口：tag 命中即零分配绑定，未命中才装配一次
        val holder = (convertView?.tag as? BookmarkRowViewHolder)
            ?: createRow(parent.context).also { it.container.tag = it }
        val container = holder.container
        val context = container.context

        // 书签标题用 tertiary 书签琥珀：与目录条目（primary）在色彩语义上分离
        val surfaceVariantColor = colorScheme.surfaceContainerHigh.toArgb()
        if (holder.backgroundArgb != surfaceVariantColor) {
            holder.background.setColor(surfaceVariantColor)
            holder.backgroundArgb = surfaceVariantColor
        }

        holder.titleTv.text = bm.chapterTitle.ifEmpty { "第 ${bm.chapterIndex + 1} 章" }
        holder.titleTv.setTextColorIfChanged(colorScheme.tertiary.toArgb())

        val snippetLabel = BookmarkRowStyle.snippetLabel(bm.snippet)
        if (snippetLabel == null) {
            holder.snippetTv.visibility = View.GONE
        } else {
            holder.snippetTv.visibility = View.VISIBLE
            holder.snippetTv.text = snippetLabel
            holder.snippetTv.setTextColorIfChanged(colorScheme.onSurface.toArgb())
        }

        holder.dateTv.text = timeFormat.format(Date(bm.time))
        holder.dateTv.setTextColorIfChanged(colorScheme.onSurfaceVariant.toArgb())

        val visual = BookmarkRowStyle.deleteVisual(isPending)
        holder.delTv.text = visual.label
        holder.delTv.typeface = if (visual.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.delTv.setTextColorIfChanged(colorScheme.error.toArgb())
        // 尺寸只在两段式状态翻转时写一次：minWidth/minHeight 的 setter 每次都 requestLayout
        val delMinWidth = (visual.minWidthDp * density).toInt()
        if (holder.delTv.minWidth != delMinWidth) holder.delTv.minWidth = delMinWidth
        val delMinHeight = (visual.touchDp * density).toInt()
        if (holder.delTv.minHeight != delMinHeight) holder.delTv.minHeight = delMinHeight
        holder.delTv.setOnClickListener {
            if (gate.isPending(bm.id)) {
                revertHandler.removeCallbacks(revertRunnable)
                gate.confirm()
                onDeleteBookmark(bm)
            } else {
                gate.request(bm.id)
                RotaryHapticManager.performScrollTick(context, null)
                notifyDataSetChanged()
                revertHandler.removeCallbacks(revertRunnable)
                revertHandler.postDelayed(revertRunnable, BookmarkDeleteGate.REVERT_DELAY_MS)
            }
        }

        container.setOnClickListener {
            if (gate.pendingId != null) {
                // 待确认期点击行体 = 取消删除，避免误跳转
                revertHandler.removeCallbacks(revertRunnable)
                gate.cancel()
                notifyDataSetChanged()
            } else {
                onBookmarkClick(bm)
            }
        }
        applyPressFeedback(container)

        return container
    }

    /** 首次装配行视图：之后仅按 tag 复用，不再新建任何 View/Drawable */
    private fun createRow(context: Context): BookmarkRowViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (BookmarkRowStyle.TOUCH_DP * density).toInt()
            setPadding((10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * density
        }
        container.background = background

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textLayout.addView(titleTv)

        val snippetTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textLayout.addView(snippetTv)

        val dateTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textLayout.addView(dateTv)
        container.addView(textLayout)

        // 删除键：热区 48dp（ADR-018），视觉只有文字不铺底色，扩热区不改观感；
        // 与文本区之间留 6dp 死区，降低误触 ✕ 概率
        val delTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * density).toInt() }
        }
        container.addView(delTv)

        return BookmarkRowViewHolder(container, background, titleTv, snippetTv, dateTv, delTv)
    }
}
