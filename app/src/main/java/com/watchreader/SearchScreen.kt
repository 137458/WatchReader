package com.watchreader

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.CircularProgressIndicator as WearCircularProgressIndicator
import androidx.wear.compose.material3.ListSubHeader as WearListSubHeader
import androidx.wear.compose.material3.OutlinedButton as WearOutlinedButton
import androidx.wear.compose.material3.Text as WearText

/**
 * 书内全文搜索页 — 关键词逐章扫描全书，命中列表点击即精准跳转
 *
 * 结果列表复用原生 ListView + 表冠滚动管线（与目录/书签同架构），不改为 Compose 列表；
 * 搜索进度由 ViewModel 增量上报，结果边扫边出。
 *
 * 组件口径：页面外壳沿用 WatchScreenScaffold 圆屏安全区；输入框保留 MD3 OutlinedTextField
 * （Wear M3 未提供文本输入件）；操作键与结果计数改用 Wear M3 官方件
 * （Button / OutlinedButton / ListSubHeader / CircularProgressIndicator / Text）。
 */
@Composable
fun SearchScreen(
    chapters: List<Chapter>,
    state: SearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onHitClick: (SearchHit) -> Unit,
    onBack: () -> Unit
) {
    // 搜索态是独立于书架/阅读态的型（SearchState）：扫描逐切片上报只重组本页，
    // 不会连带书架整份列表与阅读页一起重组
    val searchResults = state.hits
    val isSearching = state.isSearching
    val scannedChapters = state.scannedChapters
    val totalChapters = state.totalChapters

    BackHandler(onBack = onBack)

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()

    // 搜索词由 VM 持有（与结果同生命周期）：返回后再进本页仍可微调关键词重查
    var searched by remember { mutableStateOf(false) }

    WatchReaderWearTheme {
        WatchScreenScaffold(
            // 右滑退出搜索页（与返回键同一处理）
            onDismiss = onBack,
            fades = WatchScreenFades(top = 48.dp, bottom = 44.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 52.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text("搜索正文关键词", style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (query.isNotBlank()) onSearch(query)
                    })
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WearButton(
                        onClick = {
                            searched = true
                            onSearch(query)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = query.isNotBlank() && !isSearching && chapters.isNotEmpty(),
                        label = { WearText("搜索") }
                    )
                    WearOutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        label = { WearText("‹ 返回") }
                    )
                }

                when {
                    // 有命中即出列表（边扫边出），扫描中附进度行
                    searchResults.isNotEmpty() -> {
                        WearListSubHeader(
                            label = {
                                WearText(
                                    text = buildString {
                                        append("找到 ${searchResults.size} 处")
                                        if (searchResults.size >= BookSearchEngine.MAX_RESULTS) append("（已达上限）")
                                        if (isSearching) append(" · 扫描 $scannedChapters/$totalChapters 章")
                                    },
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        SearchHitListView(
                            hits = searchResults,
                            bgColor = bgColor,
                            onHitClick = onHitClick,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    isSearching -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WearCircularProgressIndicator(modifier = Modifier.size(26.dp))
                            WearText(
                                text = "正在扫描 $scannedChapters/$totalChapters 章…",
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    searched -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            WearText(
                                text = "未找到“${query.trim()}”",
                                color = colorScheme.onSurfaceVariant
                            )
                            WearText(
                                text = "换个关键词试试",
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 命中列表（原生 ListView）：章节行 + 摘要行，表冠滚动统一管线
 */
@Composable
private fun SearchHitListView(
    hits: List<SearchHit>,
    bgColor: Int,
    onHitClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    AndroidView(
        modifier = modifier,
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
    var colorScheme: ColorScheme,
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
                setColor(colorScheme.surfaceContainerHigh.toArgb())
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
        applyPressFeedback(container)
        return container
    }
}

private class SearchHitViewHolder(
    val titleTv: TextView,
    val snippetTv: TextView
)
