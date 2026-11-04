package com.watchreader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 章节目录页的原生列表装配 + Tab 胶囊。
 *
 * 抽出动机：屏幕里三段 AndroidView 的 factory 各自几十行（ListView 装配、避让内边距、
 * 表冠绑定、首次定位），与浮层/书签分支混在同一个 when 里，看不出屏幕主干。
 * 装配口径（内边距、分隔高度、首次定位偏移）原样保留；重绘判重仍在适配器的 setData 里，
 * 本文件只负责「首次装配」，update 段仍由屏幕调用 applyListViewBg + setData。
 */

/**
 * 目录列表：行高 ≥48dp、表冠连续滚动、选中项上方留一行的首次定位。
 * [onChapterClick] 只在命中有效索引时回调（与旧 factory 的 position in indices 守卫一致）。
 */
internal fun createChapterListView(
    context: Context,
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    colorScheme: ColorScheme,
    onChapterClick: (Int) -> Unit
): ListView {
    val density = context.resources.displayMetrics.density
    val padH = (20 * density).toInt()
    // padTop 与顶部 Tab 胶囊（44dp 起 + 48dp 高 + 内边距）对齐，列表内容不被 Tab 压住
    // padBottom 避让底部弧形操作条热区（径向深入屏缘 48dp + 悬离 6dp = 54dp），留 72dp 冗余
    val padTop = (100 * density).toInt()
    val padBottom = (72 * density).toInt()

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
        setBackgroundColor(colorScheme.background.toArgb())
        setPadding(padH, padTop, padH, padBottom)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    // 表冠物理旋转无缝滚动（统一管线扩展，见 bindCrownScroll）
    listView.bindCrownScroll(context)
    listView.adapter = ChapterListAdapter(chapters, currentChapterIndex, colorScheme, density)

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

    return listView
}

/**
 * 书签列表：行内两段式删除由 BookmarkListAdapter 负责，本函数只管装配与首次定位。
 * 点击/删除回调直接进适配器（旧 factory 也没有额外的 item 点击监听）。
 */
internal fun createBookmarkListView(
    context: Context,
    bookmarks: List<Bookmark>,
    colorScheme: ColorScheme,
    onBookmarkClick: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
): ListView {
    val density = context.resources.displayMetrics.density
    val padH = (18 * density).toInt()
    // 与目录模式一致：避让顶部 Tab 胶囊（44dp 起 + 48dp 高 + 内边距）
    // padBottom 避让底部弧形操作条热区（深入 54dp），留 72dp 冗余
    val padTop = (100 * density).toInt()
    val padBottom = (72 * density).toInt()

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

    // 表冠物理旋转无缝滚动（统一管线扩展）
    listView.bindCrownScroll(context)
    listView.adapter = BookmarkListAdapter(bookmarks, colorScheme, density, onBookmarkClick, onDeleteBookmark)

    return listView
}

/**
 * 顶部 Tab 胶囊：选中态填充与文字颜色双通道动画，填充绘制于 drawBehind（仅绘制层失效）
 */
@Composable
internal fun ChapterTabCapsule(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN),
        label = "tab-pill"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN),
        label = "tab-text"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}
