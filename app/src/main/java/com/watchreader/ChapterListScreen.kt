package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

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
 * 章节目录列表 — 原生 ListView 极速架构 + 千章范围快速分卷直达
 */
@Composable
fun ChapterListScreen(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterClick: (Int) -> Unit,
    onBack: () -> Unit
) {
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
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. 原生 ListView 核心
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val density = context.resources.displayMetrics.density
                val padH = (20 * density).toInt()
                val padTop = (46 * density).toInt()
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

        // 顶部沿表盘外边缘弧形排布的目录标题
        CurvedChapterHeader(
            title = "目录 (${chapters.size}章)",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 顶部透明点击感应区（点击顶部弧形直接返回阅读）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(interactionSource = noIndication, indication = null, onClick = onBack)
                .align(Alignment.TopCenter)
        )

        // 底部常驻操作栏（长篇小说支持范围分卷与返回）
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

            if (ranges.isNotEmpty()) {
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

        // 2. 范围分卷极速直达浮层 (Native ListView + 表冠旋转支持 + 圆屏专属弧形美化)
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
                // 原生极速 ListView（支持物理表冠与 120fps 零 GC 流畅滑动）
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

                        // 表冠物理旋转与触觉微振
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

                // 顶部渐变羽化
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

                // 底部渐变羽化
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

                // 顶部透明点击感应区（点击顶部弧形直接关闭）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(interactionSource = noIndication, indication = null) {
                            showRangePicker = false
                        }
                        .align(Alignment.TopCenter)
                )

                // 底部悬浮关闭胶囊
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
