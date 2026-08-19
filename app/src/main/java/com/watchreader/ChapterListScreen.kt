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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * 章节目录列表 — 原生 ListView 架构 + 严格遵循项目 Theme 配色系统
 */
@Composable
fun ChapterListScreen(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterClick: (Int) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()
    val activeColor = colorScheme.primary.toArgb()
    val normalColor = colorScheme.onSurfaceVariant.toArgb()

    val noIndication = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        // 原生 ListView 核心
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val density = context.resources.displayMetrics.density
                val padH = (20 * density).toInt()
                val padTop = (46 * density).toInt()
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
                    dividerHeight = 0
                    setBackgroundColor(bgColor)
                    setPadding(padH, padTop, padH, padBottom)
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
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
                .height(44.dp)
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

        // 底部常驻悬浮返回胶囊按钮
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                .clickable(interactionSource = noIndication, indication = null, onClick = onBack)
                .padding(horizontal = 20.dp, vertical = 5.dp)
        ) {
            Text(
                text = "‹ 返回阅读",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = colorScheme.primary
            )
        }
    }
}
