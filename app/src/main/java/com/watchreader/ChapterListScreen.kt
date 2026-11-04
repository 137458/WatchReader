package com.watchreader

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 章节目录与书签列表 — 原生 ListView 极速架构 + 双 Tab 切换 + 千章范围快速分卷直达
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
    var selectedTab by remember { mutableStateOf(if (openBookmarkTab) 1 else 0) } // 0: 目录, 1: 书签
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
            val activeRangeIndex = ranges.indexOfFirst { currentChapterIndex in it.startIndex..it.endIndex }.coerceAtLeast(0)
            // 选卷列表条件化刷新状态：[0]=章节总数快照, [1]=当前章节快照
            val rangeSyncState = remember { intArrayOf(-1, -1) }

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
                                        cornerRadius = 12 * density
                                        setColor(colorScheme.surfaceContainerHigh.toArgb())
                                    }
                                    currentBg = android.graphics.drawable.GradientDrawable().apply {
                                        cornerRadius = 12 * density
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
                                        (48 * density).toInt()
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
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
                    // 重绘判重收进适配器：章节表/当前章/配色都没变时不会整页 invalidate
                    // （父级每次重组都会执行 update，如阅读时长 tick）
                    (listView.adapter as? ChapterListAdapter)
                        ?.setData(chapters, currentChapterIndex, colorScheme)
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
                        text = "暂无书签\n点击下方“存为书签”保存当前位置",
                        style = MaterialTheme.typography.bodyMedium,
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
                        // 与目录模式一致：避让顶部 Tab 胶囊（44dp 起 + 48dp 高 + 内边距）
                        // padBottom 避让底部弧形操作条热区（深入 54dp），留 72dp 冗余
                        val padTop = (100 * density).toInt()
                        val padBottom = (72 * density).toInt()

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
                        // 判重与重绘时机由适配器守（见 BookmarkListAdapter.setData）
                        (bookmarkListView.adapter as? BookmarkListAdapter)
                            ?.setData(bookmarks, colorScheme)
                    }
                )
            }
        }


        // 顶部 Tab 切换胶囊（滑动式指示：填充与文字颜色双通道动画，仅绘制层失效）。
        // 顶部 44dp 起：18dp 处弦宽仅 ~124dp 装不下双胶囊，外端贴弧甚至点不到；
        // 胶囊最小高度 48dp，列表顶部留白同步上移
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .clip(WatchShapes.Pill)
                .background(colorScheme.surfaceContainerHigh)
                .border(1.dp, colorScheme.outlineVariant, WatchShapes.Pill)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabCapsule("目录 (${chapters.size})", selected = selectedTab == 0) { selectedTab = 0 }
            TabCapsule("书签 (${bookmarks.size})", selected = selectedTab == 1) { selectedTab = 1 }
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
