package com.watchreader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    var autoScrollEngine: AutoScrollEngine? = null
)

/**
 * 阅读页 — 极致单 TextLayout + 永久 5 节点零分配 View 复用池 + 0 GC Choreographer 自动平滑滚屏 + 双轨调光
 */
@Composable
fun ReaderScreen(
    chapterContent: ChapterContent?,
    initialCharOffset: Int,
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
    onBrightnessChange: (Float) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current

    // 阅读状态下自动开启屏幕常亮，退出阅读页面时自动恢复系统省电息屏
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
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
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        // 原生极速 ScrollView 渲染核心（固定 5 View 复用池 + Choreographer 引擎挂载）
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val density = ctx.resources.displayMetrics.density
                val padH = (24 * density).toInt() // 466x466 圆屏安全区域边距
                val padTop = (44 * density).toInt()
                val padBottom = (52 * density).toInt()

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

                // 2. 章节标题
                val titleTv = TextView(ctx).apply {
                    tag = "title"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
                    setTextColor(titleColor)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                }
                container.addView(titleTv)

                // 3. 章节正文（单 TextLayout 硬件加速排版）
                val bodyTv = TextView(ctx).apply {
                    tag = "body"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                    setTextColor(textColor)
                    setLineSpacing(0f, 1.45f)
                    setPadding(0, 0, 0, (12 * density).toInt())
                }
                container.addView(bodyTv)

                // 4. 下一章按钮卡片
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
                scrollView.tag = holder

                // 手势与多模态交互协同：左侧边缘滑动调光 + 单击切换自动滚屏 + 长按呼出菜单
                var isLeftEdgeDrag = false
                var startDragY = 0f
                var initialDragBrightness = appBrightness

                val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onLongPress(e: MotionEvent) {
                        autoEngine.stop()
                        onLongPress()
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        onAutoScrollToggle()
                        return true
                    }
                })

                scrollView.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            autoEngine.pauseTemporarily(1800L)
                            // 检测是否在左侧 18% 区域开始滑动（用于直接调光）
                            if (event.x < (70 * density)) {
                                isLeftEdgeDrag = true
                                startDragY = event.y
                                initialDragBrightness = if (appBrightness < 0f) 0.5f else appBrightness
                            } else {
                                isLeftEdgeDrag = false
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isLeftEdgeDrag) {
                                val deltaY = startDragY - event.y // 向上滑增加亮度，向下滑减弱
                                val deltaBrightness = deltaY / (240 * density)
                                val newBrightness = (initialDragBrightness + deltaBrightness).coerceIn(0.01f, 1.0f)
                                onBrightnessChange(newBrightness)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isLeftEdgeDrag = false
                        }
                    }
                    false
                }

                // 表冠物理旋转监听：自动滚屏中可动态调速，未开启时正常翻页（36px/格）
                scrollView.setOnGenericMotionListener { v, event ->
                    if (CrownScrollHelper.isCrownScrollEvent(event)) {
                        val delta = CrownScrollHelper.extractCrownDelta(event)
                        if (autoEngine.isRunning) {
                            if (abs(delta) > 0.05f) {
                                val speedDelta = if (delta > 0) 5f else -5f
                                autoEngine.adjustSpeed(speedDelta)
                                onAutoScrollSpeedChange(autoEngine.speedPxPerSec)
                                RotaryHapticManager.performScrollTick(ctx, v)
                            }
                            return@setOnGenericMotionListener true
                        } else {
                            if (abs(delta) > 0.001f) {
                                val stepPixels = (delta * 36f).toInt()
                                scrollView.scrollBy(0, stepPixels)
                                RotaryHapticManager.performScrollTick(ctx, v)
                                return@setOnGenericMotionListener true
                            }
                        }
                    }
                    false
                }

                // 首次绑定数据
                bindChapterData(holder, chapterContent, fontSize, textColor, titleColor, onSurfaceVariantColor)

                // 首次布局完成后精准恢复阅读位置并请求焦点
                scrollView.post {
                    scrollView.requestFocus()
                    restoreScrollPosition(scrollView, holder, chapterContent, initialCharOffset)
                    if (isAutoScrolling) {
                        autoEngine.start()
                    }
                }

                // 滚动监听：基于 bodyTv 真实高度进行精准防抖持久化
                val handler = Handler(Looper.getMainLooper())
                var saveRunnable: Runnable? = null
                var lastReportedOffset = -1

                scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (chapterContent != null && chapterContent.formattedBody.isNotEmpty()) {
                        val bodyTop = holder.bodyTv.top
                        val bodyHeight = maxOf(1, holder.bodyTv.height)
                        val relativeY = (scrollY - bodyTop).coerceIn(0, bodyHeight)
                        val scrollRatio = relativeY.toFloat() / bodyHeight
                        val chapterLen = chapterContent.endCharOffset - chapterContent.startCharOffset
                        val currentOffset = chapterContent.startCharOffset + (chapterLen * scrollRatio).toInt()

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

                val density = scrollView.context.resources.displayMetrics.density
                holder.prevBtn.background = GradientDrawable().apply {
                    setColor(surfaceVariantColor)
                    cornerRadius = 14 * density
                }
                holder.nextBtn.background = GradientDrawable().apply {
                    setColor(surfaceVariantColor)
                    cornerRadius = 14 * density
                }

                // 同步自动滚屏引擎状态
                holder.autoScrollEngine?.let { engine ->
                    engine.speedPxPerSec = autoScrollSpeed
                    if (isAutoScrolling && !engine.isRunning) {
                        engine.start()
                    } else if (!isAutoScrolling && engine.isRunning) {
                        engine.stop()
                    }
                }

                val lastChapterIndex = holder.container.tag as? Int
                val currentChapterIdx = chapterContent?.chapterIndex

                if (lastChapterIndex != currentChapterIdx) {
                    bindChapterData(holder, chapterContent, fontSize, textColor, titleColor, onSurfaceVariantColor)
                    scrollView.post {
                        restoreScrollPosition(scrollView, holder, chapterContent, initialCharOffset)
                        if (isAutoScrolling) {
                            holder.autoScrollEngine?.start()
                        }
                    }
                } else {
                    holder.titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
                    holder.titleTv.setTextColor(titleColor)

                    holder.bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
                    holder.bodyTv.setTextColor(textColor)

                    holder.prevTv.setTextColor(onSurfaceVariantColor)
                    holder.nextTv.setTextColor(titleColor)
                    holder.endTv.setTextColor(onSurfaceVariantColor)
                }
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
                        0.75f to colorScheme.background.copy(alpha = 0.85f),
                        1f to Color.Transparent
                    )
                )
                .align(Alignment.TopCenter)
        )

        // 底部平滑渐变羽化遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.8f to colorScheme.background.copy(alpha = 0.85f),
                        1f to colorScheme.background
                    )
                )
                .align(Alignment.BottomCenter)
        )

        // 顶部沿表盘外边缘弧形排布的章节名
        CurvedChapterHeader(
            title = currentChapterTitle,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 9 点与 15 点方向常驻侧边状态栏
        WatchSideStatusBar(
            modifier = Modifier.fillMaxSize(),
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )

        // 自动滚屏运行时右下角轻量胶囊状态提示
        if (isAutoScrolling) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
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
    }
}

/**
 * 手表常驻侧边状态栏 — 9 点钟方向电量从上到下排布，15 点钟方向时间竖直排列
 */
@Composable
fun WatchSideStatusBar(
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
) {
    val context = LocalContext.current
    var currentTime by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }
    var batteryLevel by remember {
        mutableStateOf(getBatteryCapacity(context))
    }
    var isCharging by remember {
        mutableStateOf(false)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        if (level >= 0 && scale > 0) {
                            batteryLevel = (level * 100) / scale
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val sticky = context.registerReceiver(receiver, filter)
        sticky?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100) / scale
            }
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 9 点钟方向（左侧垂直居中）：电量从上到下排
        val batteryText = (if (isCharging) "⚡\n" else "") + "$batteryLevel%".map { it.toString() }.joinToString("\n")
        Text(
            text = batteryText,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 5.dp),
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            color = textColor
        )

        // 15 点钟方向（3 点钟右侧垂直居中）：时间竖直排列
        val timeText = currentTime.map { it.toString() }.joinToString("\n")
        Text(
            text = timeText,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 5.dp),
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            color = textColor
        )
    }
}

private fun getBatteryCapacity(context: Context): Int {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
    } catch (_: Exception) {
        100
    }
}

/**
 * 极速数据绑定（微秒级直接属性赋值，0 对象分配）
 */
private fun bindChapterData(
    holder: ReaderViewHolder,
    content: ChapterContent?,
    fontSize: Int,
    textColor: Int,
    titleColor: Int,
    onSurfaceVariantColor: Int
) {
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

    // 2. 章节标题
    holder.titleTv.text = content.title
    holder.titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize + 2).toFloat())
    holder.titleTv.setTextColor(titleColor)

    // 3. 章节正文（单 TextLayout 一体排版）
    holder.bodyTv.text = content.formattedBody
    holder.bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
    holder.bodyTv.setTextColor(textColor)

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
 * 恢复滚动位置
 */
private fun restoreScrollPosition(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    content: ChapterContent?,
    initialCharOffset: Int
) {
    if (content == null || initialCharOffset <= content.startCharOffset) {
        val density = scrollView.resources.displayMetrics.density
        val targetTop = maxOf(0, holder.titleTv.top - (6 * density).toInt())
        scrollView.scrollTo(0, targetTop)
        return
    }

    val chapterLen = maxOf(1, content.endCharOffset - content.startCharOffset)
    val relativeOffset = (initialCharOffset - content.startCharOffset).coerceIn(0, chapterLen)
    val ratio = relativeOffset.toFloat() / chapterLen

    val bodyTop = holder.bodyTv.top
    val bodyHeight = maxOf(1, holder.bodyTv.height)
    val targetY = (bodyTop + (bodyHeight * ratio)).toInt()
    scrollView.scrollTo(0, targetY)
}
