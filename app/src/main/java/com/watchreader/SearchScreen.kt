package com.watchreader

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 书内全文搜索页 — 关键词逐章扫描全书，命中列表点击即精准跳转
 *
 * 结果列表复用原生 ListView + 表冠滚动管线（与目录/书签同架构）；
 * 搜索进度由 ViewModel 增量上报，结果边扫边出。
 */
@Composable
fun SearchScreen(
    chapters: List<Chapter>,
    searchResults: List<SearchHit>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onHitClick: (SearchHit) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()

    var query by remember { mutableStateOf("") }
    // 是否已发起过搜索：用于区分「未搜索」与「无结果」两种空态文案
    var searched by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 52.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索正文关键词", fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                shape = WatchShapes.Row
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    label = "搜索",
                    modifier = Modifier.weight(1f),
                    emphasis = PillEmphasis.Primary,
                    enabled = query.isNotBlank() && !isSearching && chapters.isNotEmpty()
                ) {
                    searched = true
                    onSearch(query)
                }
                PillButton(
                    label = "‹ 返回",
                    modifier = Modifier.weight(1f),
                    onClick = onBack
                )
            }

            when {
                isSearching -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadingIndicator(size = 26.dp, strokeWidth = 2.4.dp)
                        Text(
                            text = "正在逐章扫描…",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                searchResults.isNotEmpty() -> {
                    Text(
                        text = "找到 ${searchResults.size} 处" +
                            if (searchResults.size >= BookSearchEngine.MAX_RESULTS) "（已达上限）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    SearchHitListView(
                        hits = searchResults,
                        bgColor = bgColor,
                        onHitClick = onHitClick
                    )
                }

                searched -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "未找到“${query.trim()}”",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "换个关键词试试",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

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
    }
}

/**
 * 命中列表（原生 ListView）：章节行 + 摘要行，表冠滚动统一管线
 */
@Composable
private fun SearchHitListView(
    hits: List<SearchHit>,
    bgColor: Int,
    onHitClick: (SearchHit) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val density = context.resources.displayMetrics.density
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
                setBackgroundColor(bgColor)
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            listView.bindCrownScroll(context)
            listView.adapter = SearchHitListAdapter(hits, colorScheme, density, onHitClick)
            listView.setOnItemClickListener { _, _, position, _ ->
                (listView.adapter as? SearchHitListAdapter)?.hits?.getOrNull(position)?.let(onHitClick)
            }
            listView
        },
        update = { listView ->
            applyListViewBg(listView, bgColor)
            val adapter = listView.adapter as? SearchHitListAdapter
            if (adapter != null && adapter.hits !== hits) {
                adapter.hits = hits
                adapter.notifyDataSetChanged()
            }
        }
    )
}

private class SearchHitListAdapter(
    var hits: List<SearchHit>,
    var colorScheme: androidx.compose.material3.ColorScheme,
    val density: Float,
    val onHitClick: (SearchHit) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = hits.size
    override fun getItem(position: Int): Any = hits[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val context = parent.context
        val hit = hits[position]

        val container = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(colorScheme.surfaceVariant.toArgb())
                cornerRadius = 12 * density
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val snippetTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            addView(titleTv)
            addView(snippetTv)
            tag = SearchHitViewHolder(titleTv, snippetTv)
        }

        val holder = container.tag as SearchHitViewHolder
        holder.titleTv.text = "第 ${hit.chapterIndex + 1} 章 · ${hit.chapterTitle.ifEmpty { "未命名" }}"
        holder.titleTv.setTextColor(colorScheme.primary.toArgb())
        holder.snippetTv.text = hit.snippet
        holder.snippetTv.setTextColor(colorScheme.onSurface.toArgb())
        container.setOnClickListener { onHitClick(hit) }
        return container
    }
}

private class SearchHitViewHolder(
    val titleTv: TextView,
    val snippetTv: TextView
)
