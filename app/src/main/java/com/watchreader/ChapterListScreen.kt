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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.*

private class ChapterCardViewHolder(
    val indicatorTv: TextView,
    val titleTv: TextView,
    val tagTv: TextView
)

/**
 * 章节范围分卷模型
 */
data class ChapterRange(
    val startIndex: Int,
    val endIndex: Int,
    val label: String
)

/**
 * 智能根据章节总数生成适宜手表的范围分卷
 */
fun generateChapterRanges(totalCount: Int): List<ChapterRange> {
    if (totalCount <= 30) return emptyList()
    val step = when {
        totalCount <= 150 -> 25
        totalCount <= 600 -> 50
        else -> 100
    }
    val list = ArrayList<ChapterRange>(totalCount / step + 1)
    var start = 0
    while (start < totalCount) {
        val end = minOf(start + step, totalCount)
        list.add(ChapterRange(start, end - 1, "第 ${start + 1} ~ ${end} 章"))
        start = end
    }
    return list
}

/**
 * 章节目录与书签列表 — 原生 ListView 极速架构 + 双 Tab 切换 + 千章范围快速分卷直达
 */
@Composable
fun ChapterListScreen(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    bookmarks: List<Bookmark> = emptyList(),
    onChapterClick: (Int) -> Unit,
    onBookmarkClick: (Bookmark) -> Unit = {},
    onDeleteBookmark: (Bookmark) -> Unit = {},
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: 目录, 1: 书签
    var showRangePicker by remember { mutableStateOf(false) }

    BackHandler(onBack = {
        if (showRangePicker) {
            showRangePicker = false
        } else {
            onBack()
        }
    })

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()
    val activeColor = colorScheme.primary.toArgb()
    val normalColor = colorScheme.onSurfaceVariant.toArgb()

    val noIndication = remember { MutableInteractionSource() }
    var currentListView by remember { mutableStateOf<ListView?>(null) }
    val ranges = remember(chapters.size) { generateChapterRanges(chapters.size) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (selectedTab == 0) {
            // ── 目录模式：原生 ListView 核心 ──
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val density = context.resources.displayMetrics.density
                    val padH = (20 * density).toInt()
                    val padTop = (50 * density).toInt()
                    val padBottom = (56 * density).toInt()

                    val listView = ListView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isVerticalScrollBarEnabled = false
                        divider = null
                        dividerHeight = (5 * density).toInt()
                        setBackgroundColor(bgColor)
                        setPadding(padH, padTop, padH, padBottom)
                        clipToPadding = false
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    }
                    currentListView = listView

                    // 表冠物理旋转无缝滚动
                    listView.setOnGenericMotionListener { v, event ->
                        if (CrownScrollHelper.isCrownScrollEvent(event)) {
                            val delta = CrownScrollHelper.extractCrownDelta(event)
                            CrownScrollHelper.dispatchScroll(delta, listView, context, v)
                            true
                        } else {
                            false
                        }
                    }

                    val adapter = ChapterListAdapter(chapters, currentChapterIndex, colorScheme, density)
                    listView.adapter = adapter

                    listView.setOnItemClickListener { _, _, position, _ ->
                        if (position in chapters.indices) {
                            onChapterClick(position)
                        }
                    }

                    listView.post {
                        listView.requestFocus()
                        if (currentChapterIndex in chapters.indices) {
                            listView.setSelection((currentChapterIndex - 1).coerceAtLeast(0))
                        }
                    }

                    listView
                },
                update = { listView ->
                    currentListView = listView
                    listView.setBackgroundColor(bgColor)
                    val adapter = listView.adapter as? ChapterListAdapter
                    if (adapter != null) {
                        adapter.chapters = chapters
                        adapter.currentChapterIndex = currentChapterIndex
                        adapter.colorScheme = colorScheme
                        adapter.notifyDataSetChanged()
                    }
                }
            )
        } else {
            // ── 书签模式：原生卡片 ListView ──
            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无书签\n可在阅读菜单中点击“存为书签”",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val density = context.resources.displayMetrics.density
                        val padH = (18 * density).toInt()
                        val padTop = (50 * density).toInt()
                        val padBottom = (56 * density).toInt()

                        val bookmarkListView = ListView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isVerticalScrollBarEnabled = false
                            divider = null
                            dividerHeight = (6 * density).toInt()
                            setBackgroundColor(bgColor)
                            setPadding(padH, padTop, padH, padBottom)
                            clipToPadding = false
                            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        }

                        bookmarkListView.setOnGenericMotionListener { v, event ->
                            if (CrownScrollHelper.isCrownScrollEvent(event)) {
                                val delta = CrownScrollHelper.extractCrownDelta(event)
                                CrownScrollHelper.dispatchScroll(delta, bookmarkListView, context, v)
                                true
                            } else {
                                false
                            }
                        }

                        val adapter = BookmarkListAdapter(bookmarks, colorScheme, density, onBookmarkClick, onDeleteBookmark)
                        bookmarkListView.adapter = adapter

                        bookmarkListView
                    },
                    update = { bookmarkListView ->
                        bookmarkListView.setBackgroundColor(bgColor)
                        val adapter = bookmarkListView.adapter as? BookmarkListAdapter
                        if (adapter != null) {
                            adapter.bookmarks = bookmarks
                            adapter.colorScheme = colorScheme
                            adapter.notifyDataSetChanged()
                        }
                    }
                )
            }
        }

        // 顶部平滑渐变羽化遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        0f to colorScheme.background,
                        0.75f to colorScheme.background.copy(alpha = 0.9f),
                        1f to Color.Transparent
                    )
                )
                .align(Alignment.TopCenter)
        )

        // 底部渐变羽化遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.7f to colorScheme.background.copy(alpha = 0.9f),
                        1f to colorScheme.background
                    )
                )
                .align(Alignment.BottomCenter)
        )

        // 顶部 Tab 切换胶囊（滑动式指示：填充与文字颜色双通道动画，仅绘制层失效）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .clip(WatchShapes.Pill)
                .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                .border(1.dp, colorScheme.outline.copy(alpha = 0.18f), WatchShapes.Pill)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabCapsule("目录 (${chapters.size})", selected = selectedTab == 0) { selectedTab = 0 }
            TabCapsule("书签 (${bookmarks.size})", selected = selectedTab == 1) { selectedTab = 1 }
        }

        // 底部常驻操作栏（提升至 18dp 宽阔弦长区，两端按钮不再被下弧削平）
        val screenContext = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverlayPill(label = "‹ 返回", onClick = onBack)

            if (selectedTab == 0 && chapters.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                OverlayPill(
                    label = "当前",
                    emphasized = true,
                    onClick = {
                        currentListView?.let { lv ->
                            if (currentChapterIndex in chapters.indices) {
                                RotaryHapticManager.performScrollTick(screenContext, null)
                                val viewHeight = lv.height
                                val itemHeight = (42 * lv.resources.displayMetrics.density).toInt()
                                val targetTop = maxOf(0, (viewHeight - itemHeight) / 2)
                                lv.smoothScrollToPositionFromTop(currentChapterIndex, targetTop, 300)
                            }
                        }
                    }
                )
            }

            if (selectedTab == 0 && ranges.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                OverlayPill(label = "选卷") { showRangePicker = true }
            }
        }

        // 2. 范围分卷极速直达浮层
        if (showRangePicker && ranges.isNotEmpty()) {
            val activeRangeIndex = ranges.indexOfFirst { currentChapterIndex in it.startIndex..it.endIndex }.coerceAtLeast(0)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .clickable(interactionSource = noIndication, indication = null) {
                        showRangePicker = false
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val density = context.resources.displayMetrics.density
                        val padH = (26 * density).toInt()
                        val padTop = (48 * density).toInt()
                        val padBottom = (54 * density).toInt()

                        val rangeListView = ListView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isVerticalScrollBarEnabled = false
                            divider = null
                            dividerHeight = (6 * density).toInt()
                            setBackgroundColor(bgColor)
                            setPadding(padH, padTop, padH, padBottom)
                            clipToPadding = false
                            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        }

                        rangeListView.setOnGenericMotionListener { v, event ->
                            if (CrownScrollHelper.isCrownScrollEvent(event)) {
                                val delta = CrownScrollHelper.extractCrownDelta(event)
                                CrownScrollHelper.dispatchScroll(delta, rangeListView, context, v)
                                true
                            } else {
                                false
                            }
                        }

                        rangeListView.adapter = object : BaseAdapter() {

                            // 范围行卡片背景缓存（普通态 / 当前态）
                            var normalBg: android.graphics.drawable.GradientDrawable? = null
                            var currentBg: android.graphics.drawable.GradientDrawable? = null

                            fun drawables(): Pair<android.graphics.drawable.GradientDrawable, android.graphics.drawable.GradientDrawable> {
                                if (normalBg == null) {
                                    normalBg = android.graphics.drawable.GradientDrawable().apply {
                                        cornerRadius = 14 * density
                                        setColor(colorScheme.surfaceVariant.toArgb())
                                    }
                                    currentBg = android.graphics.drawable.GradientDrawable().apply {
                                        cornerRadius = 14 * density
                                        setColor(colorScheme.primary.copy(alpha = 0.22f).toArgb())
                                        setStroke((1.5f * density).toInt(), activeColor)
                                    }
                                }
                                return normalBg!! to currentBg!!
                            }

                            override fun getCount(): Int = ranges.size
                            override fun getItem(position: Int): Any = ranges[position]
                            override fun getItemId(position: Int): Long = position.toLong()

                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val range = ranges[position]
                                val isCurrentRange = currentChapterIndex in range.startIndex..range.endIndex

                                val tv = (convertView as? TextView) ?: TextView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (38 * density).toInt()
                                    )
                                    gravity = Gravity.CENTER
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                }

                                val (rangeNormalBg, rangeCurrentBg) = drawables()
                                tv.background = if (isCurrentRange) rangeCurrentBg else rangeNormalBg
                                tv.text = if (isCurrentRange) "${range.label} • 正在读" else range.label
                                tv.setTextColor(if (isCurrentRange) activeColor else normalColor)
                                tv.typeface = if (isCurrentRange) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                return tv
                            }
                        }

                        rangeListView.setOnItemClickListener { _, _, position, _ ->
                            if (position in ranges.indices) {
                                RotaryHapticManager.performScrollTick(context, null)
                                currentListView?.setSelection(ranges[position].startIndex)
                                showRangePicker = false
                            }
                        }

                        rangeListView.post {
                            rangeListView.requestFocus()
                            if (activeRangeIndex in ranges.indices) {
                                rangeListView.setSelection((activeRangeIndex - 1).coerceAtLeast(0))
                            }
                        }

                        rangeListView
                    },
                    update = { rangeListView ->
                        rangeListView.setBackgroundColor(bgColor)
                        (rangeListView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    }
                )

                // 顶部羽化
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to colorScheme.background,
                                0.75f to colorScheme.background.copy(alpha = 0.9f),
                                1f to Color.Transparent
                            )
                        )
                        .align(Alignment.TopCenter)
                )

                // 底部羽化
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.7f to colorScheme.background.copy(alpha = 0.9f),
                                1f to colorScheme.background
                            )
                        )
                        .align(Alignment.BottomCenter)
                )

                // 顶部弧形标题
                CurvedChapterHeader(
                    title = "快速选卷 (${ranges.size}卷)",
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // 底部关闭胶囊（提高至 18dp 安全区并扩充触控盒）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                ) {
                    OverlayPill(label = "✕ 关闭") { showRangePicker = false }
                }
            }
        }
    }
}

/**
 * 顶部 Tab 胶囊：选中态填充与文字颜色双通道动画，填充绘制于 drawBehind（仅绘制层失效）
 */
@Composable
private fun TabCapsule(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(190),
        label = "tab-pill"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = tween(190),
        label = "tab-text"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.97f)
            .clip(WatchShapes.Pill)
            .drawBehind {
                if (pillAlpha > 0.01f) {
                    drawRoundRect(
                        color = colors.primary.copy(alpha = pillAlpha),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}

/**
 * 浮层胶囊按钮：发丝描边 + 按压缩放 + 轻触感
 */
@Composable
private fun OverlayPill(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .clip(WatchShapes.Pill)
            .background(
                if (emphasized) colors.primary.copy(alpha = 0.16f)
                else colors.surfaceVariant.copy(alpha = 0.94f)
            )
            .then(
                if (emphasized) {
                    Modifier.border(1.dp, colors.primary.copy(alpha = 0.45f), WatchShapes.Pill)
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (emphasized) colors.primary else colors.onSurfaceVariant
        )
    }
}

private class ChapterListAdapter(
    var chapters: List<Chapter>,
    var currentChapterIndex: Int,
    var colorScheme: androidx.compose.material3.ColorScheme,
    val density: Float
) : BaseAdapter() {

    // 缓存两级卡片背景，杜绝快速滚动期逐帧 GradientDrawable 分配引发的 GC 抖动
    private var cachedNormalBg: android.graphics.drawable.GradientDrawable? = null
    private var cachedCurrentBg: android.graphics.drawable.GradientDrawable? = null
    private var drawableCacheKey: androidx.compose.material3.ColorScheme? = null

    private fun cachedDrawables(): Pair<android.graphics.drawable.GradientDrawable, android.graphics.drawable.GradientDrawable> {
        if (drawableCacheKey !== colorScheme || cachedNormalBg == null) {
            cachedNormalBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(colorScheme.surfaceVariant.copy(alpha = 0.70f).toArgb())
            }
            cachedCurrentBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(colorScheme.primary.copy(alpha = 0.18f).toArgb())
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

        val container: FrameLayout
        val holder: ChapterCardViewHolder

        if (convertView == null) {
            container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((11 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textLayout.addView(titleTv)

            val tagTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding((6 * density).toInt(), (1.5f * density).toInt(), (6 * density).toInt(), (1.5f * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(colorScheme.primary.copy(alpha = 0.25f).toArgb())
                    cornerRadius = 6 * density
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

        holder.titleTv.text = chapter.title

        if (isCurrent) {
            holder.indicatorTv.visibility = View.VISIBLE
            holder.indicatorTv.text = "●"
            holder.indicatorTv.setTextColor(activeColor)
            holder.titleTv.setTextColor(activeColor)
            holder.titleTv.typeface = Typeface.DEFAULT_BOLD

            holder.tagTv.visibility = View.VISIBLE
            holder.tagTv.text = "正在读"
            holder.tagTv.setTextColor(activeColor)
        } else {
            holder.indicatorTv.visibility = View.GONE
            holder.titleTv.setTextColor(onSurfaceColor)
            holder.titleTv.typeface = Typeface.DEFAULT

            holder.tagTv.visibility = View.GONE
        }

        return container
    }
}

private class BookmarkListAdapter(
    var bookmarks: List<Bookmark>,
    var colorScheme: androidx.compose.material3.ColorScheme,
    val density: Float,
    val onBookmarkClick: (Bookmark) -> Unit,
    val onDeleteBookmark: (Bookmark) -> Unit
) : BaseAdapter() {
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun getCount(): Int = bookmarks.size
    override fun getItem(position: Int): Any = bookmarks[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val context = parent.context
        val bm = bookmarks[position]
        val activeColor = colorScheme.primary.toArgb()
        val surfaceVariantColor = colorScheme.surfaceVariant.toArgb()
        val onSurfaceColor = colorScheme.onSurface.toArgb()
        val normalColor = colorScheme.onSurfaceVariant.toArgb()
        val errorColor = colorScheme.error.toArgb()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceVariantColor)
                cornerRadius = 12 * density
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = bm.chapterTitle.ifEmpty { "第 ${bm.chapterIndex + 1} 章" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activeColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textLayout.addView(titleTv)

        if (bm.snippet.isNotEmpty()) {
            val snippetTv = TextView(context).apply {
                text = "“${bm.snippet.take(30)}…”"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(onSurfaceColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            textLayout.addView(snippetTv)
        }

        val dateTv = TextView(context).apply {
            text = timeFormat.format(Date(bm.time))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(normalColor)
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textLayout.addView(dateTv)
        container.addView(textLayout)

        // 删除按钮：热区扩大至 42dp 以上，带点击触达保护
        val delBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(errorColor)
            gravity = Gravity.CENTER
            minWidth = (42 * density).toInt()
            minHeight = (42 * density).toInt()
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                onDeleteBookmark(bm)
            }
        }
        container.addView(delBtn)

        container.setOnClickListener {
            onBookmarkClick(bm)
        }

        return container
    }
}
