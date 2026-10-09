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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
 * 原生 ListView 背景守卫：View.setBackgroundColor 每次调用都会新建 ColorDrawable 并失效重绘，
 * 而 AndroidView 的 update 在每次父级重组都会执行 —— 仅在颜色真正变化时才设置
 */
internal fun applyListViewBg(view: View, color: Int) {
    val current = (view.background as? android.graphics.drawable.ColorDrawable)?.color
    if (current != color) {
        view.setBackgroundColor(color)
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

    val colorScheme = MiuixTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()
    val activeColor = colorScheme.primary.toArgb()
    val normalColor = colorScheme.onSurfaceVariantSummary.toArgb()

    val noIndication = remember { MutableInteractionSource() }
    var currentListView by remember { mutableStateOf<ListView?>(null) }
    // 选卷浮层的表冠归属：浮层打开时注册 Compose 侧目标抢占顶层管线，
    // 兜底焦点路由不再有机会命中被浮层盖住的章节列表（"滚的是看不见的列表"缺陷）
    val rangeListViewRef = remember { arrayOfNulls<ListView>(1) }
    val crownContext = androidx.compose.ui.platform.LocalContext.current
    rememberCrownScrollTarget(showRangePicker) { delta ->
        val lv = rangeListViewRef[0]
        if (!showRangePicker || lv == null) {
            false
        } else {
            CrownScrollHelper.dispatchScroll(delta, lv, crownContext)
            true
        }
    }
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
                    // padTop 与顶部 Tab 胶囊（44dp 起，圆屏弧线安全区）对齐，列表内容不被 Tab 压住
                    val padTop = (78 * density).toInt()
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

                    // 表冠物理旋转无缝滚动（统一管线扩展，见 bindCrownScroll）
                    listView.bindCrownScroll(context)

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
                    applyListViewBg(listView, bgColor)
                    // notifyDataSetChanged 会强制全部可见行重布局：仅在数据/主题真正变化时调用，
                    // 杜绝父级每次重组（如阅读时长 tick）都触发目录整页 invalidate
                    val adapter = listView.adapter as? ChapterListAdapter
                    if (adapter != null &&
                        (adapter.chapters !== chapters ||
                            adapter.currentChapterIndex != currentChapterIndex ||
                            adapter.colorScheme !== colorScheme)
                    ) {
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
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val density = context.resources.displayMetrics.density
                        val padH = (18 * density).toInt()
                        // 与目录模式一致：避让顶部 Tab 胶囊（44dp 起的安全区）
                        val padTop = (78 * density).toInt()
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

                        // 表冠物理旋转无缝滚动（统一管线扩展）
                        bookmarkListView.bindCrownScroll(context)

                        val adapter = BookmarkListAdapter(bookmarks, colorScheme, density, onBookmarkClick, onDeleteBookmark)
                        bookmarkListView.adapter = adapter

                        bookmarkListView
                    },
                    update = { bookmarkListView ->
                        applyListViewBg(bookmarkListView, bgColor)
                        val adapter = bookmarkListView.adapter as? BookmarkListAdapter
                        if (adapter != null &&
                            (adapter.bookmarks !== bookmarks || adapter.colorScheme !== colorScheme)
                        ) {
                            adapter.bookmarks = bookmarks
                            adapter.colorScheme = colorScheme
                            adapter.notifyDataSetChanged()
                        }
                    }
                )
            }
        }

        // 顶部/底部羽化遮罩（统一 EdgeFadeMask 基元）
        EdgeFadeMask(
            edge = Alignment.Top,
            modifier = Modifier.align(Alignment.TopCenter),
            height = 48.dp
        )
        EdgeFadeMask(
            edge = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
            height = 44.dp
        )

        // 顶部 Tab 切换胶囊（滑动式指示：填充与文字颜色双通道动画，仅绘制层失效）。
        // 顶部 44dp 起：18dp 处弦宽仅 ~124dp 装不下双胶囊，外端贴弧甚至点不到
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .clip(WatchShapes.Pill)
                .background(colorScheme.surfaceVariant)
                .border(1.dp, colorScheme.outline.copy(alpha = WatchAlpha.HAIRLINE), WatchShapes.Pill)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabCapsule("目录 (${chapters.size})", selected = selectedTab == 0) { selectedTab = 0 }
            TabCapsule("书签 (${bookmarks.size})", selected = selectedTab == 1) { selectedTab = 1 }
        }

        // 底部常驻操作栏（提升至 18dp 宽阔弦长区，两端按钮不再被下弧削平）
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(label = "‹ 返回", onClick = onBack)

            if (selectedTab == 0 && chapters.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                PillButton(
                    label = "当前",
                    emphasis = PillEmphasis.Outline,
                    onClick = {
                        currentListView?.let { lv ->
                            if (currentChapterIndex in chapters.indices) {
                                val viewHeight = lv.height
                                val itemHeight = (44 * lv.resources.displayMetrics.density).toInt()
                                val targetTop = maxOf(0, (viewHeight - itemHeight) / 2)
                                lv.smoothScrollToPositionFromTop(currentChapterIndex, targetTop, 300)
                            }
                        }
                    }
                )
            }

            if (selectedTab == 0 && ranges.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                PillButton(label = "选卷", onClick = { showRangePicker = true })
            }
        }

        // 2. 范围分卷极速直达浮层
        if (showRangePicker && ranges.isNotEmpty()) {
            val activeRangeIndex = ranges.indexOfFirst { currentChapterIndex in it.startIndex..it.endIndex }.coerceAtLeast(0)
            // 选卷列表条件化刷新状态：[0]=章节总数快照, [1]=当前章节快照
            val rangeSyncState = remember { intArrayOf(-1, -1) }

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

                        // 表冠物理旋转无缝滚动（统一管线扩展）；实例经捕获数组交给 Compose 侧，
                        // 浮层打开时注册的表冠目标抢占顶层管线（局部名与外层状态同名，直接赋值会被遮蔽）
                        rangeListView.bindCrownScroll(context)
                        rangeListViewRef[0] = rangeListView

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
                                        // 当前卷走预混容器角色：primaryContainer 底 + primary 强调描边（零 alpha）
                                        setColor(colorScheme.primaryContainer.toArgb())
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
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                }

                                val (rangeNormalBg, rangeCurrentBg) = drawables()
                                tv.background = if (isCurrentRange) rangeCurrentBg else rangeNormalBg
                                tv.text = if (isCurrentRange) "${range.label} • 正在读" else range.label
                                // 当前卷文字用容器配对内容色（onPrimaryContainer），保证 container 底上达 AA
                                tv.setTextColor(if (isCurrentRange) colorScheme.onPrimaryContainer.toArgb() else normalColor)
                                tv.typeface = if (isCurrentRange) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                applyPressFeedback(tv)
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
                        applyListViewBg(rangeListView, bgColor)
                        val adapter = rangeListView.adapter
                        if (adapter != null &&
                            (rangeSyncState[0] != chapters.size || rangeSyncState[1] != currentChapterIndex)
                        ) {
                            rangeSyncState[0] = chapters.size
                            rangeSyncState[1] = currentChapterIndex
                            (adapter as? BaseAdapter)?.notifyDataSetChanged()
                        }
                    }
                )

                // 顶部/底部羽化（统一 EdgeFadeMask 基元）
                EdgeFadeMask(
                    edge = Alignment.Top,
                    modifier = Modifier.align(Alignment.TopCenter),
                    height = 48.dp
                )
                EdgeFadeMask(
                    edge = Alignment.Bottom,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    height = 44.dp
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
                    PillButton(label = "✕ 关闭", onClick = { showRangePicker = false })
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
    val colors = MiuixTheme.colorScheme
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN),
        label = "tab-pill"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariantSummary,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN),
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}

private class ChapterListAdapter(
    var chapters: List<Chapter>,
    var currentChapterIndex: Int,
    var colorScheme: top.yukonga.miuix.kmp.theme.Colors,
    val density: Float
) : BaseAdapter() {

    // 缓存两级卡片背景，杜绝快速滚动期逐帧 GradientDrawable 分配引发的 GC 抖动
    private var cachedNormalBg: android.graphics.drawable.GradientDrawable? = null
    private var cachedCurrentBg: android.graphics.drawable.GradientDrawable? = null
    private var drawableCacheKey: top.yukonga.miuix.kmp.theme.Colors? = null

    private fun cachedDrawables(): Pair<android.graphics.drawable.GradientDrawable, android.graphics.drawable.GradientDrawable> {
        if (drawableCacheKey !== colorScheme || cachedNormalBg == null) {
            cachedNormalBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(colorScheme.surfaceVariant.toArgb())
            }
            cachedCurrentBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * density
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, (5 * density).toInt(), 0)
            }
            textLayout.addView(indicatorTv)

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textLayout.addView(titleTv)

            val tagTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding((6 * density).toInt(), (1.5f * density).toInt(), (6 * density).toInt(), (1.5f * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    // 标签实底 primary + 配对 onPrimary 文字（预混角色，替代 25% alpha 糊底）
                    setColor(colorScheme.primary.toArgb())
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

private class BookmarkListAdapter(
    var bookmarks: List<Bookmark>,
    var colorScheme: top.yukonga.miuix.kmp.theme.Colors,
    val density: Float,
    val onBookmarkClick: (Bookmark) -> Unit,
    val onDeleteBookmark: (Bookmark) -> Unit
) : BaseAdapter() {
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // 两段式删除（与书架删书同款）：首按进入待确认，3.2s 无操作自动回滚。
    // 书签删除不可恢复，防护等级必须与删书对齐，不能 ✕ 一下就没
    private var pendingDeleteId: String? = null
    private val revertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val revertRunnable = Runnable {
        if (pendingDeleteId != null) {
            pendingDeleteId = null
            notifyDataSetChanged()
        }
    }

    override fun getCount(): Int = bookmarks.size
    override fun getItem(position: Int): Any = bookmarks[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val context = parent.context
        val bm = bookmarks[position]
        val isPending = pendingDeleteId == bm.id
        // 书签标题用 tertiary 书签琥珀：与目录条目（primary）在色彩语义上分离
        val activeColor = colorScheme.tertiaryContainerVariant.toArgb()
        val surfaceVariantColor = colorScheme.surfaceVariant.toArgb()
        val onSurfaceColor = colorScheme.onSurface.toArgb()
        val normalColor = colorScheme.onSurfaceVariantSummary.toArgb()
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
                cornerRadius = 14 * density
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = bm.chapterTitle.ifEmpty { "第 ${bm.chapterIndex + 1} 章" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activeColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textLayout.addView(titleTv)

        if (bm.snippet.isNotEmpty()) {
            val snippetTv = TextView(context).apply {
                text = "“${bm.snippet.take(30)}…”"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(onSurfaceColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            textLayout.addView(snippetTv)
        }

        val dateTv = TextView(context).apply {
            text = timeFormat.format(Date(bm.time))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(normalColor)
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textLayout.addView(dateTv)
        container.addView(textLayout)

        // 删除按钮：热区 42dp+；与文本区之间留 6dp 死区，降低误触 ✕ 概率
        val delBtn = TextView(context).apply {
            text = if (isPending) "确认删除" else "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(errorColor)
            typeface = if (isPending) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            minWidth = ((if (isPending) 76 else 42) * density).toInt()
            minHeight = (42 * density).toInt()
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * density).toInt() }
            setOnClickListener {
                if (isPending) {
                    revertHandler.removeCallbacks(revertRunnable)
                    pendingDeleteId = null
                    onDeleteBookmark(bm)
                } else {
                    pendingDeleteId = bm.id
                    RotaryHapticManager.performScrollTick(context, null)
                    notifyDataSetChanged()
                    revertHandler.removeCallbacks(revertRunnable)
                    revertHandler.postDelayed(revertRunnable, 3200L)
                }
            }
        }
        container.addView(delBtn)

        container.setOnClickListener {
            if (pendingDeleteId != null) {
                // 待确认期点击行体 = 取消删除，避免误跳转
                revertHandler.removeCallbacks(revertRunnable)
                pendingDeleteId = null
                notifyDataSetChanged()
            } else {
                onBookmarkClick(bm)
            }
        }
        applyPressFeedback(container)

        return container
    }
}
