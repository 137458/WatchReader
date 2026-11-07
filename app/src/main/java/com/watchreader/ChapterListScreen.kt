package com.watchreader

import android.widget.ListView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 章节目录与书签列表 — 原生 ListView 极速架构 + 双 Tab 切换 + 千章范围快速分卷直达
 *
 * 本函数只保留屏幕级状态与编排：原生列表装配见 ChapterListParts.kt，选卷浮层适配器见 ChapterRangePickerAdapter.kt。
 */
@Composable
fun ChapterListScreen(
    openBookmarkTab: Boolean = false,
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    bookmarks: List<Bookmark> = emptyList(),
    onChapterClick: (Int) -> Unit,
    onBookmarkClick: (Bookmark) -> Unit = {},
    onDeleteBookmark: (Bookmark) -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (openBookmarkTab) 1 else 0) } // 0: 目录, 1: 书签
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
    // 页面安全区：顶部 Tab 与列表内边距共用同一份解析结果（系统 inset 与圆屏几何取大）
    val safeInsets = rememberWatchSafeInsets()

    val noIndication = remember { MutableInteractionSource() }
    var currentListView by remember { mutableStateOf<ListView?>(null) }
    // 选卷浮层的表冠归属：浮层打开时注册 Compose 侧目标抢占顶层管线，
    // 兜底焦点路由不再有机会命中被浮层盖住的章节列表（"滚的是看不见的列表"缺陷）
    val rangeListViewRef = remember { arrayOfNulls<ListView>(1) }
    val crownContext = LocalContext.current
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

    // 底部弧形贴边操作条：扇带处处贴合圆屏下缘（目录 Tab 1~3 键，书签 Tab 2 键）
    val barSpecs = buildList {
        add(EdgeButtonSpec(label = "‹ 返回", onClick = onBack))

        if (selectedTab == 1) {
            add(EdgeButtonSpec(label = "存为书签", onClick = onAddBookmark))
        }

        if (selectedTab == 0 && chapters.isNotEmpty()) {
            add(
                EdgeButtonSpec(
                    label = "当前",
                    emphasis = PillEmphasis.Outline,
                    onClick = {
                        currentListView?.let { lv ->
                            if (currentChapterIndex in chapters.indices) {
                                val viewHeight = lv.height
                                val itemHeight = (48 * lv.resources.displayMetrics.density).toInt()
                                val targetTop = maxOf(0, (viewHeight - itemHeight) / 2)
                                lv.smoothScrollToPositionFromTop(currentChapterIndex, targetTop, 300)
                            }
                        }
                    }
                )
            )
        }

        if (selectedTab == 0 && ranges.isNotEmpty()) {
            add(EdgeButtonSpec(label = "选卷", onClick = { showRangePicker = true }))
        }
    }

    // 2. 范围分卷极速直达浮层（进出场走统一 scale + fade 档位）
    // 浮层必须以 overlay 槽交给壳：留在 content 内会被遮罩与操作条压住，热区也抢不过操作条
    val rangePickerOverlay: @Composable BoxScope.() -> Unit = {
        AnimatedVisibility(
            visible = showRangePicker && ranges.isNotEmpty(),
            enter = WatchOverlay.Enter,
            exit = WatchOverlay.Exit,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    // 右滑关浮层：内容跟手平移并淡出，越过门限滑出后关闭
                    .swipeDismissBack { showRangePicker = false }
                    .clickable(interactionSource = noIndication, indication = null) {
                        showRangePicker = false
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        createRangePickerListView(
                            context = context,
                            ranges = ranges,
                            currentChapterIndex = currentChapterIndex,
                            colorScheme = colorScheme
                        ) { range ->
                            currentListView?.setSelection(range.startIndex)
                            showRangePicker = false
                        }.also { rangeListViewRef[0] = it }
                    },
                    update = { rangeListView ->
                        applyListViewBg(rangeListView, bgColor)
                        // 重绘判重收进适配器：分卷表/当前章都没变时不会整页 invalidate
                        (rangeListView.adapter as? ChapterRangePickerAdapter)
                            ?.setData(ranges, currentChapterIndex)
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

                // 底部弧形贴边关闭扇带（48dp 热区）
                EdgeActionBar(
                    EdgeButtonSpec(label = "✕ 关闭", onClick = { showRangePicker = false })
                )
            }
        }
    }

    WatchScreenScaffold(
        swipeEnabled = !showRangePicker,
        onDismiss = onBack,
        fades = WatchScreenFades(top = 48.dp, bottom = 44.dp),
        contentAlignment = Alignment.TopCenter,
        actionBar = { EdgeActionBar(*barSpecs.toTypedArray()) },
        overlay = rangePickerOverlay
    ) {
        if (selectedTab == 0) {
            // ── 目录模式：原生 ListView 核心 ──
            if (chapters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateBlock(
                        overline = "目录",
                        title = "还没有章节",
                        hint = "返回阅读页等目录加载完成"
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        createChapterListView(
                            context = context,
                            insets = safeInsets,
                            chapters = chapters,
                            currentChapterIndex = currentChapterIndex,
                            colorScheme = colorScheme,
                            onChapterClick = onChapterClick
                        ).also { currentListView = it }
                    },
                    update = { listView ->
                        currentListView = listView
                        applyListViewBg(listView, bgColor)
                        // 重绘判重收进适配器：章节表/当前章/配色都没变时不会整页 invalidate
                        // （父级每次重组都会执行 update，如阅读时长 tick）
                        (listView.adapter as? ChapterListAdapter)
                            ?.setData(chapters, currentChapterIndex, colorScheme)
                    }
                )
            }
        } else {
            // ── 书签模式：原生卡片 ListView ──
            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateBlock(
                        overline = "书签",
                        title = "还没有书签",
                        hint = "阅读时点击下方“存为书签”保存当前位置"
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        createBookmarkListView(
                            context = context,
                            insets = safeInsets,
                            bookmarks = bookmarks,
                            colorScheme = colorScheme,
                            onBookmarkClick = onBookmarkClick,
                            onDeleteBookmark = onDeleteBookmark
                        )
                    },
                    update = { bookmarkListView ->
                        applyListViewBg(bookmarkListView, bgColor)
                        // 判重与重绘时机由适配器守（见 BookmarkListAdapter.setData）
                        (bookmarkListView.adapter as? BookmarkListAdapter)
                            ?.setData(bookmarks, colorScheme)
                    }
                )
            }
        }

        // 顶部页签：官方 Wear M3 按钮并排，不再自拼胶囊底与描边。
        // 顶部 44dp 起：18dp 处弦宽仅 ~124dp 装不下双按钮；按钮最小高度 48dp，列表顶部留白同步上移
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeInsets.top),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChapterTabCapsule("目录 (${chapters.size})", selected = selectedTab == 0) { selectedTab = 0 }
            ChapterTabCapsule("书签 (${bookmarks.size})", selected = selectedTab == 1) { selectedTab = 1 }
        }
    }
}
