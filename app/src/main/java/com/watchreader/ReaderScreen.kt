package com.watchreader

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * 固定 5 节点 View 持有器 + 自动滚屏引擎（永久复用，零 View 分配与销毁）
 */
private class ReaderViewHolder(
    val scrollView: ScrollView,
    val container: LinearLayout,
    val prevBtn: FrameLayout,
    val prevTv: TextView,
    val titleTv: TextView,
    val bodyTv: TextView,
    val nextBtn: FrameLayout,
    val nextTv: TextView,
    val endTv: TextView,
    var autoScrollEngine: AutoScrollEngine? = null,
    var currentContent: ChapterContent? = null
) {
    // 卡片背景复用池：滚动期间 ReaderScreen 高频重组，update 阶段仅重着色不再新建 GradientDrawable
    var prevBackground: GradientDrawable? = null
    var nextBackground: GradientDrawable? = null
    var appliedChromeColor: Int = 0
}

/**
 * 阅读页 — 极致单 TextLayout + 永久 5 节点零分配 View 复用池 + 0 GC Choreographer 自动平滑滚屏 + 双轨调光
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ReaderScreen(
    chapterContent: ChapterContent?,
    initialCharOffset: Int,
    totalChapters: Int = 1,
    fullTextLength: Int = 0,
    onCharOffsetChange: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onLongPress: () -> Unit,
    onBack: () -> Unit,
    fontSize: Int,
    autoScrollSpeed: Float,
    isAutoScrolling: Boolean,
    onAutoScrollToggle: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    appBrightness: Float,
    onBrightnessChange: (Float) -> Unit,
    tapPageArea: Int = 0,
    fontType: Int = 0,
    chapters: List<Chapter> = emptyList(),
    currentChapterIndex: Int = 0,
    onSeekChapter: (Int) -> Unit = {},
    onFlushReadingPosition: () -> Unit = {},
    /**
     * 是否被覆盖页（菜单 / 目录 / 速读）压顶。为 true 时本页保持组合与原生视图挂载
     * （返回时零重建、整章排版与滚动位置原样保留），但原生视图转 INVISIBLE、
     * 叠加层退出组合，隐藏期不产生任何绘制与表冠交互
     */
    covered: Boolean = false
) {
    // 被覆盖时让出返回键：由上层覆盖页的 BackHandler 接管
    BackHandler(enabled = !covered, onBack = onBack)

    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val keepScreenOnHandler = remember { Handler(Looper.getMainLooper()) }

    var isScrolling by remember { mutableStateOf(false) }
    var currentReadingOffset by remember(initialCharOffset) { mutableStateOf(initialCharOffset) }

    // 弧形寻道：识别下沉至原生触摸管线，叠加层仅负责绘制（其全屏 pointerInput 曾吞掉正文全部触摸流）。
    // 三项渲染状态聚合于单个状态容器：高频 MOVE 写入经叠加层绘制阶段延迟读取，寻道全程零重组
    val seekRecognizer = remember { ArcSeekGestureRecognizer() }
    val seekState = remember { ArcSeekUiState() }
    val latestChapterIndex = remember { mutableStateOf(currentChapterIndex) }
    val latestChapterCount = remember { mutableStateOf(chapters.size) }
    latestChapterIndex.value = currentChapterIndex
    latestChapterCount.value = chapters.size

    val scrollDebounceHandler = remember { Handler(Looper.getMainLooper()) }
    val resetScrollingRunnable = remember {
        Runnable { isScrolling = false }
    }
    val notifyScrollActivity = remember {
        {
            if (!isScrolling) {
                isScrolling = true
            }
            scrollDebounceHandler.removeCallbacks(resetScrollingRunnable)
            scrollDebounceHandler.postDelayed(resetScrollingRunnable, 700L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scrollDebounceHandler.removeCallbacksAndMessages(null)
            onCharOffsetChange(currentReadingOffset)
            onFlushReadingPosition()
        }
    }
    val resetInactivityKeepScreenOn = remember(window, isAutoScrolling) {
        val timeoutMs = 5 * 60 * 1000L // 5 分钟无交互超时
        val timeoutRunnable = Runnable {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val trigger: () -> Unit = {
            val flags = window?.attributes?.flags ?: 0
            if ((flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            keepScreenOnHandler.removeCallbacks(timeoutRunnable)
            if (!isAutoScrolling) {
                keepScreenOnHandler.postDelayed(timeoutRunnable, timeoutMs)
            }
        }
        trigger
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var activeAutoEngine by remember { mutableStateOf<AutoScrollEngine?>(null) }

    // 智能后台/息屏能效冻结：切到后台或息屏时立即挂起 Choreographer 循环，返回前台自动恢复
    DisposableEffect(lifecycleOwner, isAutoScrolling) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE, androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    activeAutoEngine?.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (isAutoScrolling) {
                        activeAutoEngine?.start()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 阅读状态下智能常亮：自动滚屏中永久常亮；静态阅读 5 分钟无操作自动释放休眠省电；
    // 被覆盖页压顶时立即释放常亮（阅读页已不可见，交还系统息屏策略），退出时自动恢复
    DisposableEffect(isAutoScrolling, covered) {
        if (covered) {
            keepScreenOnHandler.removeCallbacksAndMessages(null)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            resetInactivityKeepScreenOn()
        }
        onDispose {
            keepScreenOnHandler.removeCallbacksAndMessages(null)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()
    val textColor = colorScheme.onBackground.toArgb()
    val titleColor = colorScheme.primary.toArgb()
    val surfaceVariantColor = colorScheme.surfaceVariant.toArgb()
    val onSurfaceVariantColor = colorScheme.onSurfaceVariant.toArgb()

    val currentChapterTitle = chapterContent?.title ?: ""

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 原生极速 ScrollView 渲染核心（固定 5 View 复用池 + Choreographer 引擎挂载）
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val density = ctx.resources.displayMetrics.density
                val padH = (24 * density).toInt() // 466x466 圆屏安全区域边距
                val padTop = (52 * density).toInt()
                val padBottom = (56 * density).toInt()

                val scrollView = ScrollView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setBackgroundColor(bgColor)
                    setPadding(padH, padTop, padH, padBottom)
                    clipToPadding = false
                }

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // 1. 上一章按钮卡片
                var prevBackgroundRef: GradientDrawable? = null
                val prevTv = TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(onSurfaceVariantColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val prevBtn = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (4 * density).toInt(), 0, (14 * density).toInt())
                    }
                    background = GradientDrawable().apply {
                        setColor(surfaceVariantColor)
                        cornerRadius = 14 * density
                    }
                    setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                    isClickable = true
                    setOnClickListener { onPrevChapter() }
                    addView(prevTv)
                }
                container.addView(prevBtn)
                prevBackgroundRef = prevBtn.background as GradientDrawable

                // 2. 章节标题
                val titleTv = TextView(ctx).apply {
                    tag = "title"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
                    setTextColor(titleColor)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                }
                container.addView(titleTv)

                // 3. 章节正文（单 TextLayout 硬件加速排版 + 中文两端平整对齐）
                val bodyTv = TextView(ctx).apply {
                    tag = "body"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                    setTextColor(textColor)
                    setLineSpacing(0f, 1.45f)
                    setPadding(0, 0, 0, (12 * density).toInt())
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        justificationMode = android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        @Suppress("DEPRECATION")
                        justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
                    }
                }
                container.addView(bodyTv)

                // 4. 下一章按钮卡片
                var nextBackgroundRef: GradientDrawable? = null
                val nextTv = TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(titleColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val nextBtn = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (14 * density).toInt(), 0, (20 * density).toInt())
                    }
                    background = GradientDrawable().apply {
                        setColor(surfaceVariantColor)
                        cornerRadius = 14 * density
                    }
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    isClickable = true
                    setOnClickListener { onNextChapter() }
                    addView(nextTv)
                }
                container.addView(nextBtn)
                nextBackgroundRef = nextBtn.background as GradientDrawable

                // 5. 全书完结指示
                val endTv = TextView(ctx).apply {
                    tag = "end"
                    text = "— 全书完 —"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(onSurfaceVariantColor)
                    gravity = Gravity.CENTER
                    setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
                }
                container.addView(endTv)

                scrollView.addView(container)

                // 6. 初始化 Choreographer 自动滚屏引擎
                val autoEngine = AutoScrollEngine(
                    scrollView = scrollView,
                    onNextChapterRequest = { onNextChapter() }
                )
                autoEngine.speedPxPerSec = autoScrollSpeed
                activeAutoEngine = autoEngine

                val holder = ReaderViewHolder(
                    scrollView = scrollView,
                    container = container,
                    prevBtn = prevBtn,
                    prevTv = prevTv,
                    titleTv = titleTv,
                    bodyTv = bodyTv,
                    nextBtn = nextBtn,
                    nextTv = nextTv,
                    endTv = endTv,
                    autoScrollEngine = autoEngine
                )
                holder.prevBackground = prevBackgroundRef
                holder.nextBackground = nextBackgroundRef
                holder.appliedChromeColor = surfaceVariantColor
                scrollView.tag = holder

                // 手势交互：支持上下/左右点按翻页、长按呼出菜单、自动滚屏时单击暂停
                val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onLongPress(e: MotionEvent) {
                        autoEngine.stop()
                        onLongPress()
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (isScrolling) return true

                        // 若正在自动滚屏，单击任意位置暂停
                        if (isAutoScrolling) {
                            onAutoScrollToggle()
                            return true
                        }

                        val w = scrollView.width.toFloat()
                        val h = scrollView.height.toFloat()
                        if (w <= 0 || h <= 0) return true

                        val action = TapPageHelper.resolveTapAction(e.x, e.y, w, h, tapPageArea)
                        val scrollDistance = TapPageHelper.calculateScrollDistance(h, density)

                        when (action) {
                            TapAction.PAGE_UP -> {
                                scrollView.smoothScrollBy(0, -scrollDistance)
                                RotaryHapticManager.performScrollTick(ctx, scrollView)
                            }
                            TapAction.PAGE_DOWN -> {
                                scrollView.smoothScrollBy(0, scrollDistance)
                                RotaryHapticManager.performScrollTick(ctx, scrollView)
                            }
                            TapAction.SHOW_MENU -> {
                                if (tapPageArea == TapPageArea.DISABLED.value) {
                                    onAutoScrollToggle()
                                } else {
                                    onLongPress()
                                }
                            }
                        }
                        return true
                    }
                })

                scrollView.setOnTouchListener { view, event ->
                    var seekHandled = false

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val tracking = seekRecognizer.onDown(
                                event.x,
                                event.y,
                                view.width.toFloat(),
                                view.height.toFloat(),
                                latestChapterIndex.value
                            )
                            if (tracking) {
                                seekState.targetIndex = latestChapterIndex.value
                                seekState.touchAngle = 0f
                                seekState.isSeeking = false
                            }
                            seekHandled = tracking
                            resetInactivityKeepScreenOn()
                            autoEngine.pauseTemporarily(1800L)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (seekRecognizer.isTracking) {
                                val consumed = seekRecognizer.onMove(
                                    event.x,
                                    event.y,
                                    view.width.toFloat(),
                                    view.height.toFloat(),
                                    latestChapterCount.value
                                )
                                if (consumed) {
                                    seekState.isSeeking = seekRecognizer.isSeeking
                                    seekState.touchAngle = seekRecognizer.currentAngle
                                    seekState.targetIndex = seekRecognizer.targetChapterIndex
                                    seekHandled = true
                                }
                            }
                            notifyScrollActivity()
                            // 手指持续滑动/慢读期间持续续租，杜绝长按拖拽时引擎强行复苏抢夺屏幕
                            autoEngine.pauseTemporarily(1800L)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (seekRecognizer.isTracking) {
                                val confirmed = seekRecognizer.onUp()
                                seekState.isSeeking = false
                                if (confirmed != null) {
                                    onSeekChapter(confirmed)
                                    seekHandled = true
                                }
                            }
                            resetInactivityKeepScreenOn()
                            // 手指离开屏幕后启动延时恢复
                            autoEngine.pauseTemporarily(1800L)
                        }
                    }

                    // 寻道态下不参与点按/长按判定，避免与翻页热区双重触发
                    val tapSuppressed = seekHandled || seekRecognizer.isTracking || seekRecognizer.isSeeking
                    if (!tapSuppressed) {
                        gestureDetector.onTouchEvent(event)
                    }
                    tapSuppressed
                }

                // 表冠物理旋转监听：自动滚屏中可动态调速，未开启时按固定步进翻页并触发齿轮微振
                scrollView.setOnGenericMotionListener { v, event ->
                    if (CrownScrollHelper.isCrownScrollEvent(event)) {
                        resetInactivityKeepScreenOn()
                        notifyScrollActivity()
                        val delta = CrownScrollHelper.extractCrownDelta(event)
                        if (autoEngine.isRunning) {
                            if (abs(delta) > 0.05f) {
                                val speedDelta = if (delta > 0) 5f else -5f
                                autoEngine.adjustSpeed(speedDelta)
                                onAutoScrollSpeedChange(autoEngine.speedPxPerSec)
                                // 调速档位微振：每物理档位（12 单位）一振，与全应用表冠振感节奏统一
                                CrownScrollHelper.dispatchAdjustTick(delta, ctx, v)
                            }
                            return@setOnGenericMotionListener true
                        }
                        // 常规阅读：统一走表冠管线（固定单格步进 + 24px 门限齿轮微振），
                        // 替代原先落给原生 ScrollView 的裸滚动（无振感且步进随系统滚动系数漂移）
                        if (abs(delta) > 0.001f) {
                            CrownScrollHelper.dispatchScroll(delta, scrollView, ctx, v)
                        }
                        return@setOnGenericMotionListener true
                    }
                    false
                }

                // 首次绑定数据
                bindChapterData(holder, chapterContent, fontSize, fontType, textColor, titleColor, onSurfaceVariantColor)

                // 首次布局完成后精准恢复阅读位置并请求焦点
                scrollView.post {
                    scrollView.requestFocus()
                    safeRestoreScrollPosition(scrollView, holder, chapterContent, initialCharOffset)
                    if (isAutoScrolling) {
                        autoEngine.start()
                    }
                }

                // 滚动监听：基于 bodyTv 真实行定位进行精准防抖持久化，消除线性映射偏差
                val handler = Handler(Looper.getMainLooper())
                var saveRunnable: Runnable? = null
                var lastReportedOffset = -1

                scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    notifyScrollActivity()
                    val content = holder.currentContent
                    if (content != null && content.formattedBody.isNotEmpty()) {
                        val bodyTop = holder.bodyTv.top
                        val layout = holder.bodyTv.layout
                        val currentOffset = if (layout != null && layout.lineCount > 0 && holder.bodyTv.height > 0) {
                            val clampedY = (scrollY - bodyTop).coerceIn(0, maxOf(0, holder.bodyTv.height - 1))
                            val line = layout.getLineForVertical(clampedY)
                            val charOffsetInBody = layout.getLineStart(line).coerceIn(0, content.formattedBody.length)
                            // 正文坐标 → 原文坐标：段落映射精确换算，持久化位置必须落在原文域
                            content.startCharOffset + ChapterOffsetMapper.bodyToRaw(
                                charOffsetInBody,
                                content.bodyParagraphStarts,
                                content.rawParagraphStarts,
                                content.formattedBody.length,
                                rawLength = content.endCharOffset - content.startCharOffset
                            )
                        } else {
                            val bodyHeight = maxOf(1, holder.bodyTv.height)
                            val relativeY = (scrollY - bodyTop).coerceIn(0, bodyHeight)
                            val scrollRatio = relativeY.toFloat() / bodyHeight
                            val chapterLen = content.endCharOffset - content.startCharOffset
                            content.startCharOffset + (chapterLen * scrollRatio).toInt()
                        }
                        currentReadingOffset = currentOffset

                        if (currentOffset != lastReportedOffset) {
                            lastReportedOffset = currentOffset
                            saveRunnable?.let { handler.removeCallbacks(it) }
                            saveRunnable = Runnable { onCharOffsetChange(currentOffset) }
                            handler.postDelayed(saveRunnable!!, 300L)
                        }
                    }
                }

                scrollView
            },
            update = { scrollView ->
                val holder = scrollView.tag as? ReaderViewHolder ?: return@AndroidView
                scrollView.setBackgroundColor(bgColor)

                // 被覆盖页压顶：原生视图转 INVISIBLE 退出绘制但保持挂载 ——
                // ViewGroup 跳过不可见子节点，隐藏期零绘制开销；返回阅读时零重建，
                // 整章排版、滚动位置与表冠监听原样保留（本页常驻化的核心收益）
                val targetVisibility = if (covered) View.INVISIBLE else View.VISIBLE
                if (scrollView.visibility != targetVisibility) {
                    scrollView.visibility = targetVisibility
                    if (covered) CrownScrollHelper.abortFling(scrollView)
                }

                // 卡片背景零分配复用：仅主题色真正变化时重着色。
                // 此前每次重组都新建 GradientDrawable 并重设 background，
                // 滚动期间（offset 状态逐帧推进）等于逐帧分配 + 双 View 失效，是滚动卡顿主源之一
                if (holder.appliedChromeColor != surfaceVariantColor) {
                    holder.appliedChromeColor = surfaceVariantColor
                    holder.prevBackground?.setColor(surfaceVariantColor)
                    holder.nextBackground?.setColor(surfaceVariantColor)
                }

                // 同步自动滚屏引擎状态（被覆盖时挂起，避免不可见状态下空转滚动）
                holder.autoScrollEngine?.let { engine ->
                    engine.speedPxPerSec = autoScrollSpeed
                    if (covered) {
                        if (engine.isRunning) engine.stop()
                    } else if (isAutoScrolling && !engine.isRunning) {
                        engine.start()
                    } else if (!isAutoScrolling && engine.isRunning) {
                        engine.stop()
                    }
                }

                val lastChapterIndex = holder.container.tag as? Int
                val currentChapterIdx = chapterContent?.chapterIndex

                if (lastChapterIndex != currentChapterIdx || holder.currentContent != chapterContent) {
                    bindChapterData(holder, chapterContent, fontSize, fontType, textColor, titleColor, onSurfaceVariantColor)
                    safeRestoreScrollPosition(scrollView, holder, chapterContent, initialCharOffset)
                    if (isAutoScrolling) {
                        holder.autoScrollEngine?.start()
                    }
                } else {
                    holder.titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
                    holder.titleTv.setTextColor(titleColor)

                    holder.bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                    holder.bodyTv.setTextColor(textColor)
                    holder.bodyTv.typeface = if (FontType.fromValue(fontType) == FontType.SERIF) Typeface.SERIF else Typeface.SANS_SERIF

                    holder.prevTv.setTextColor(onSurfaceVariantColor)
                    holder.nextTv.setTextColor(titleColor)
                    holder.endTv.setTextColor(onSurfaceVariantColor)
                }
            }
        )

        // 被覆盖页压顶时整组叠加层退出组合：底层阅读页已转为不可见，
        // 这些叠加层若不退出会透过覆盖页的透明底直接穿帮（侧边时间 / 羽化渐变 / 进度指示）
        if (!covered) {
            // 顶部/底部平滑渐变羽化遮罩（统一 EdgeFadeMask 基元，Brush 随主题色缓存）
            EdgeFadeMask(
                edge = Alignment.Top,
                modifier = Modifier.align(Alignment.TopCenter),
                height = 48.dp
            )
            EdgeFadeMask(
                edge = Alignment.Bottom,
                modifier = Modifier.align(Alignment.BottomCenter),
                height = 36.dp
            )

            // 顶部沿表盘外边缘弧形排布的章节名
            CurvedChapterHeader(
                title = currentChapterTitle,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 9 点与 3 点方向贴边弧形排布的竖排电量与时间
            CurvedSideStatusBar(
                modifier = Modifier.fillMaxSize(),
                textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            val bottomProgressAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (!isScrolling && !isAutoScrolling) 0.80f else 0.0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = if (isScrolling) 150 else 350
                ),
                label = "bottomProgressAlpha"
            )

            // 静态阅读时微光常驻的进度指示（滑动/转表冠时敏捷隐去避让，静止后平滑淡入恢复；自动滚屏时由底部胶囊接管避让）
            // 淡入淡出 alpha 于 graphicsLayer 内逐帧读取（仅图层失效）；整数百分比经 derivedStateOf 去重，
            // 滚动期间仅当百分比数字真正变化才重组，杜绝逐帧全页重组
            if (chapterContent != null) {
                val progressPercent by remember(fullTextLength) {
                    derivedStateOf {
                        if (fullTextLength > 0) {
                            ((currentReadingOffset.toFloat() / fullTextLength) * 100).toInt().coerceIn(0, 100)
                        } else 0
                    }
                }
                val currentChapterNum = chapterContent.chapterIndex + 1
                val progressText = if (totalChapters > 1) {
                    "第 $currentChapterNum/$totalChapters 章 · $progressPercent%"
                } else {
                    "$progressPercent%"
                }
                Text(
                    text = progressText,
                    style = TextStyle(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .graphicsLayer { alpha = bottomProgressAlpha }
                )
            }

            // 自动滚屏运行时右下角轻量胶囊状态提示
            if (isAutoScrolling) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.90f))
                        .clickable { onAutoScrollToggle() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "▶ 自动滚屏 ${autoScrollSpeed.toInt()} px/s",
                        style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                        color = colorScheme.primary
                    )
                }
            }

            // F-05 表盘边缘弧形快速寻道滑块（贴圆屏边缘交互，松手即跳）
            // 纯绘制：手势由原生触摸管线中的 ArcSeekGestureRecognizer 识别后驱动本层渲染，
            // 角度与目标章节在叠加层绘制/派生阶段读取，高频移动事件不引发本页重组
            ArcSeekOverlay(
                chapters = chapters,
                seekState = seekState
            )
        }
    }
}

/**
 * 极速数据绑定（微秒级直接属性赋值，0 对象分配）
 */
private fun bindChapterData(
    holder: ReaderViewHolder,
    content: ChapterContent?,
    fontSize: Int,
    fontType: Int,
    textColor: Int,
    titleColor: Int,
    onSurfaceVariantColor: Int
) {
    holder.currentContent = content
    if (content == null) {
        holder.container.tag = null
        holder.prevBtn.visibility = View.GONE
        holder.titleTv.text = ""
        holder.bodyTv.text = ""
        holder.nextBtn.visibility = View.GONE
        holder.endTv.visibility = View.GONE
        return
    }

    holder.container.tag = content.chapterIndex

    // 1. 上一章
    if (content.hasPrevChapter) {
        holder.prevBtn.visibility = View.VISIBLE
        holder.prevTv.text = "‹ 上一章: ${content.prevChapterTitle}"
        holder.prevTv.setTextColor(onSurfaceVariantColor)
    } else {
        holder.prevBtn.visibility = View.GONE
    }

    // 2. 章节标题（无上一章按钮时增加顶部安全避让间距）
    val density = holder.scrollView.context.resources.displayMetrics.density
    val topPad = if (content.hasPrevChapter) (8 * density).toInt() else (20 * density).toInt()
    holder.titleTv.setPadding(0, topPad, 0, (10 * density).toInt())
    holder.titleTv.text = content.title
    holder.titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
    holder.titleTv.setTextColor(titleColor)

    // 3. 章节正文（单 TextLayout 一体排版 + 字体切换）
    holder.bodyTv.text = content.formattedBody
    holder.bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
    holder.bodyTv.setTextColor(textColor)
    holder.bodyTv.typeface = if (fontType == 1) Typeface.SERIF else Typeface.SANS_SERIF

    // 4. 下一章 / 全书完
    if (content.hasNextChapter) {
        holder.nextBtn.visibility = View.VISIBLE
        holder.nextTv.text = "下一章: ${content.nextChapterTitle} ›"
        holder.nextTv.setTextColor(titleColor)
        holder.endTv.visibility = View.GONE
    } else {
        holder.nextBtn.visibility = View.GONE
        holder.endTv.visibility = View.VISIBLE
        holder.endTv.setTextColor(onSurfaceVariantColor)
    }
}

/**
 * 恢复滚动位置：采用原生 Layout 行高映射定位，根除线性比例漂移
 */
private fun restoreScrollPosition(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    content: ChapterContent?,
    initialCharOffset: Int
) {
    if (content == null) return
    if (initialCharOffset <= content.startCharOffset) {
        val density = scrollView.resources.displayMetrics.density
        val targetTop = maxOf(0, holder.titleTv.top - (6 * density).toInt())
        scrollView.scrollTo(0, targetTop)
        return
    }

    val layout = holder.bodyTv.layout
    if (layout != null && layout.lineCount > 0 && holder.bodyTv.height > 0) {
        // 原文坐标 → 正文坐标：段落映射精确反解后再定位排版行
        val charOffsetInBody = ChapterOffsetMapper.rawToBody(
            initialCharOffset - content.startCharOffset,
            content.bodyParagraphStarts,
            content.rawParagraphStarts,
            content.formattedBody.length
        )
        val line = layout.getLineForOffset(charOffsetInBody)
        val lineTop = layout.getLineTop(line)
        val targetY = maxOf(0, holder.bodyTv.top + lineTop)
        scrollView.scrollTo(0, targetY)
    } else {
        val chapterLen = maxOf(1, content.endCharOffset - content.startCharOffset)
        val relativeOffset = (initialCharOffset - content.startCharOffset).coerceIn(0, chapterLen)
        val ratio = relativeOffset.toFloat() / chapterLen
        val bodyTop = holder.bodyTv.top
        val bodyHeight = maxOf(1, holder.bodyTv.height)
        val targetY = (bodyTop + (bodyHeight * ratio)).toInt()
        scrollView.scrollTo(0, targetY)
    }
}

/**
 * 安全恢复滚动位置：若 bodyTv 尚未完成 measure/layout，自动监听布局就绪后精准还原，杜绝 0 高度归零
 */
private fun safeRestoreScrollPosition(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    content: ChapterContent?,
    initialCharOffset: Int
) {
    // 章节切换/位置恢复与惯性滑动互斥，防止 fling 把恢复位置再拖走
    CrownScrollHelper.abortFling(scrollView)
    if (content == null) return
    if (holder.bodyTv.height > 0 && holder.bodyTv.layout != null) {
        restoreScrollPosition(scrollView, holder, content, initialCharOffset)
    } else {
        holder.bodyTv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (bottom - top > 0 && holder.bodyTv.layout != null) {
                    holder.bodyTv.removeOnLayoutChangeListener(this)
                    restoreScrollPosition(scrollView, holder, holder.currentContent, initialCharOffset)
                }
            }
        })
    }
}
