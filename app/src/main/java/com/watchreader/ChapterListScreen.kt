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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.*

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
    val onSurfaceColor = colorScheme.onSurface.toArgb()
    val surfaceVariantColor = colorScheme.surfaceVariant.toArgb()
    val errorColor = colorScheme.error.toArgb()

    val noIndication = remember { MutableInteractionSource() }
    var currentListView by remember { mutableStateOf<ListView?>(null) }
    val ranges = remember(chapters.size) { generateChapterRanges(chapters.size) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
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
                        dividerHeight = 0
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

                    listView.adapter = object : BaseAdapter() {
                        override fun getCount(): Int = chapters.size
                        override fun getItem(position: Int): Any = chapters[position]
                        override fun getItemId(position: Int): Long = position.toLong()

                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val textView = (convertView as? TextView) ?: TextView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    (36 * density).toInt()
                                )
                                gravity = Gravity.CENTER_VERTICAL
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            }

                            val isCurrent = position == currentChapterIndex
                            textView.text = chapters[position].title
                            if (isCurrent) {
                                textView.setTextColor(activeColor)
                                textView.typeface = Typeface.DEFAULT_BOLD
                            } else {
                                textView.setTextColor(normalColor)
                                textView.typeface = Typeface.DEFAULT
                            }
                            return textView
                        }
                    }

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
                    (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
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

                        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

                        bookmarkListView.adapter = object : BaseAdapter() {
                            override fun getCount(): Int = bookmarks.size
                            override fun getItem(position: Int): Any = bookmarks[position]
                            override fun getItemId(position: Int): Long = position.toLong()

                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val bm = bookmarks[position]
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

                                val delBtn = TextView(context).apply {
                                    text = "✕"
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    setTextColor(errorColor)
                                    setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
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

                        bookmarkListView
                    },
                    update = { bookmarkListView ->
                        bookmarkListView.setBackgroundColor(bgColor)
                        (bookmarkListView.adapter as? BaseAdapter)?.notifyDataSetChanged()
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

        // 顶部 Tab 切换胶囊
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selectedTab == 0) colorScheme.primary else Color.Transparent)
                    .clickable { selectedTab = 0 }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "目录 (${chapters.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                    color = if (selectedTab == 0) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selectedTab == 1) colorScheme.primary else Color.Transparent)
                    .clickable { selectedTab = 1 }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "书签 (${bookmarks.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                    color = if (selectedTab == 1) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                )
            }
        }

        // 底部常驻操作栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                    .clickable(interactionSource = noIndication, indication = null, onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "‹ 返回",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colorScheme.primary
                )
            }

            if (selectedTab == 0 && ranges.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.primary.copy(alpha = 0.18f))
                        .border(1.dp, colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .clickable(interactionSource = noIndication, indication = null) {
                            showRangePicker = true
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "⚡ 快速选卷",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = colorScheme.primary
                    )
                }
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

                                val cardBg = android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = 14 * density
                                    if (isCurrentRange) {
                                        setColor(colorScheme.primary.copy(alpha = 0.22f).toArgb())
                                        setStroke((1.5f * density).toInt(), activeColor)
                                    } else {
                                        setColor(colorScheme.surfaceVariant.toArgb())
                                    }
                                }
                                tv.background = cardBg
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

                // 底部关闭胶囊
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                        .clickable(interactionSource = noIndication, indication = null) {
                            showRangePicker = false
                        }
                        .padding(horizontal = 20.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "✕ 关闭",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
