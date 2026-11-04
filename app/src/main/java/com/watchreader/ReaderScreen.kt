package com.watchreader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.ScrollView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 阅读页入口：输入收敛为「一页的全部事实 + 全部回调」两个参数。
 *
 * 参数收口不是风格问题：23 个平铺参数会让父级的每一次重组都重新进入本页组合，
 * 而 [ReaderPaneState] 相等时 Compose 直接跳过整页——阅读时长 tick、传书进度这类
 * 与正文无关的状态变化不再触碰整章 StaticLayout。
 */
@Composable
fun ReaderScreen(state: ReaderPaneState, actions: ReaderPaneActions) {
    ReaderPane(
        chapterContent = state.chapterContent,
        initialCharOffset = state.initialCharOffset,
        totalChapters = state.totalChapters,
        fullTextLength = state.fullTextLength,
        onCharOffsetChange = actions.onCharOffsetChange,
        onNextChapter = actions.onNextChapter,
        onPrevChapter = actions.onPrevChapter,
        onLongPress = actions.onLongPress,
        onBack = actions.onBack,
        fontSize = state.fontSize,
        autoScrollSpeed = state.autoScrollSpeed,
        isAutoScrolling = state.isAutoScrolling,
        onAutoScrollToggle = actions.onAutoScrollToggle,
        onAutoScrollSpeedChange = actions.onAutoScrollSpeedChange,
        tapPageArea = state.tapPageArea,
        fontType = state.fontType,
        lineSpacing = state.lineSpacing,
        letterSpacing = state.letterSpacing,
        chapters = state.chapters,
        currentChapterIndex = state.currentChapterIndex,
        onSeekChapter = actions.onSeekChapter,
        onFlushReadingPosition = actions.onFlushReadingPosition,
        covered = state.covered
    )
}

/**
 * 阅读页实现（接口见 [ReaderScreen]）。
 *
 * 本函数只负责三件事：**组合**（输入 → 状态的接线）、**生命周期**（常亮 / 前后台 / 销毁清理）、
 * **叠加层**（弧形页眉、侧栏、进度、寻道）。三块被抽出的实现分别是：
 * - 原生 View 树的装配、差量绑定与滚动恢复：[createReaderViewHolder] / [updateReaderPane] / [ReaderPaneViews.kt]
 * - 触摸 / 寻道 / 表冠输入管线：[ReaderPaneInputBinding]
 * - 可纯 JVM 驱动的判定：[ReaderPaneLogic]
 */
@Composable
private fun ReaderPane(
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
    tapPageArea: Int = 0,
    fontType: Int = 0,
    lineSpacing: Int = LineSpacingMode.STANDARD.value,
    letterSpacing: Int = LetterSpacingMode.STANDARD.value,
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
    // 阅读位置唯一所有者：事件路径写入、组合读进度、update 阶段读 latestValue。
    // 此前是"Compose 状态 + volatile 数组"两个字段各自被写一次，漏写一处即错位
    val readingPosition = remember(initialCharOffset) { ReadingPosition(initialCharOffset) }

    // 弧形寻道：识别下沉至原生触摸管线，叠加层仅负责绘制（其全屏 pointerInput 曾吞掉正文全部触摸流）。
    // 三项渲染状态聚合于单个状态容器：高频 MOVE 写入经叠加层绘制阶段延迟读取，寻道全程零重组
    val seekRecognizer = remember { ArcSeekGestureRecognizer() }
    val seekState = remember { ArcSeekUiState() }
    val latestChapterIndex = remember { mutableStateOf(currentChapterIndex) }
    val latestChapterCount = remember { mutableStateOf(chapters.size) }
    latestChapterIndex.value = currentChapterIndex
    latestChapterCount.value = chapters.size
    // 手势闭包在只执行一次的 factory 中按值捕获会固化首帧参数（自动滚屏点按暂停失效、
    // 热区切换不生效的根因），与章节数同以状态持有，事件期读取最新值
    val latestIsAutoScrolling = remember { mutableStateOf(isAutoScrolling) }
    val latestTapPageArea = remember { mutableStateOf(tapPageArea) }
    latestIsAutoScrolling.value = isAutoScrolling
    latestTapPageArea.value = tapPageArea

    val scrollDebounceHandler = remember { Handler(Looper.getMainLooper()) }
    val resetScrollingRunnable = remember {
        Runnable { isScrolling = false }
    }
    val notifyScrollActivity: () -> Unit = remember {
        {
            if (!isScrolling) {
                isScrolling = true
            }
            scrollDebounceHandler.removeCallbacks(resetScrollingRunnable)
            scrollDebounceHandler.postDelayed(resetScrollingRunnable, 700L)
        }
    }

    var activeAutoEngine by remember { mutableStateOf<AutoScrollEngine?>(null) }
    var activeScrollView by remember { mutableStateOf<ScrollView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            scrollDebounceHandler.removeCallbacksAndMessages(null)
            onCharOffsetChange(readingPosition.latestValue)
            onFlushReadingPosition()
            // 组合销毁必须停掉 Choreographer 帧循环与表冠惯性：引擎 doFrame 自续
            // postFrameCallback 且 release 无其他调用方，否则带滚屏返回书架后逐帧空转
            activeAutoEngine?.stop()
            activeScrollView?.let { CrownScrollHelper.abortFling(it) }
        }
    }

    // 常亮 / 息屏 / 被覆盖三态的生命周期口径见 [rememberReaderPaneScreenAwake]
    val resetInactivityKeepScreenOn = rememberReaderPaneScreenAwake(
        window = window,
        keepScreenOnHandler = keepScreenOnHandler,
        isAutoScrolling = isAutoScrolling,
        covered = covered,
        activeEngine = { activeAutoEngine }
    )

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()
    val textColor = colorScheme.onBackground.toArgb()
    val titleColor = colorScheme.primary.toArgb()
    val surfaceVariantColor = colorScheme.surfaceContainerHigh.toArgb()
    val onSurfaceVariantColor = colorScheme.onSurfaceVariant.toArgb()

    // 当前排版偏好的目标外观：工厂首绑与每次 update 共用同一份构造（差量判定见 ReaderPaneStyles）
    val targetAppearance = ReaderPaneAppearance(
        backgroundArgb = bgColor, chromeArgb = surfaceVariantColor, bodyColorArgb = textColor,
        titleColorArgb = titleColor, metaColorArgb = onSurfaceVariantColor, fontSizeSp = fontSize,
        lineSpacingMultiplier = LineSpacingMode.fromValue(lineSpacing).multiplier,
        letterSpacingEm = LetterSpacingMode.fromValue(letterSpacing).em,
        bodyTypefaceToken = bodyTypefaceFor(fontType)
    )

    val currentChapterTitle = chapterContent?.title ?: ""

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 原生极速 ScrollView 渲染核心（固定 5 View 复用池 + Choreographer 引擎挂载）
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val holder = createReaderViewHolder(
                    ctx = ctx,
                    backgroundArgb = bgColor,
                    chromeArgb = surfaceVariantColor,
                    bodyColorArgb = textColor,
                    titleColorArgb = titleColor,
                    metaColorArgb = onSurfaceVariantColor,
                    fontSizeSp = fontSize,
                    autoScrollSpeedPxPerSec = autoScrollSpeed,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter
                )
                val scrollView = holder.scrollView
                activeAutoEngine = holder.autoScrollEngine
                activeScrollView = scrollView

                // 触摸 / 寻道 / 表冠：装配一次、事件期读最新值（见 ReaderPaneInputBinding）
                ReaderPaneInputBinding(
                    context = ctx,
                    scrollView = scrollView,
                    autoEngine = holder.autoScrollEngine,
                    seekRecognizer = seekRecognizer,
                    seekState = seekState,
                    chapterIndex = { latestChapterIndex.value },
                    chapterCount = { latestChapterCount.value },
                    isAutoScrolling = { latestIsAutoScrolling.value },
                    tapPageArea = { latestTapPageArea.value },
                    keepScreenOnTrigger = resetInactivityKeepScreenOn,
                    onScrollActivity = notifyScrollActivity,
                    onAutoScrollToggle = onAutoScrollToggle,
                    onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                    onLongPress = onLongPress,
                    onSeekChapter = onSeekChapter
                ).install()

                // 首次绑定数据：先落文本再下发外观，与收口前的排版顺序一致（文本一次排版 + 外观差异一次）
                bindChapterData(holder, chapterContent)
                applyChapterStyles(holder, targetAppearance)

                // 首次布局完成后精准恢复阅读位置并请求焦点
                scrollView.post {
                    scrollView.requestFocus()
                    safeRestoreScrollPosition(scrollView, holder, chapterContent, initialCharOffset)
                    if (latestIsAutoScrolling.value) {
                        holder.autoScrollEngine.start()
                    }
                }

                // 滚动监听：基于 bodyTv 真实行定位进行精准防抖持久化
                installReaderPaneScrollPersistence(
                    scrollView = scrollView,
                    holder = holder,
                    readingPosition = readingPosition,
                    onCharOffsetChange = onCharOffsetChange,
                    onScrollActivity = notifyScrollActivity
                )

                scrollView
            },
            update = { scrollView ->
                updateReaderPane(
                    scrollView = scrollView,
                    covered = covered,
                    isAutoScrolling = isAutoScrolling,
                    autoScrollSpeed = autoScrollSpeed,
                    chapterContent = chapterContent,
                    initialCharOffset = initialCharOffset,
                    targetAppearance = targetAppearance,
                    readingPosition = readingPosition
                )
            }
        )

        // 被覆盖页压顶时整组叠加层退出组合：底层阅读页已转为不可见，
        // 这些叠加层若不退出会透过覆盖页的透明底直接穿帮（侧边时间 / 羽化渐变 / 进度指示）
        if (!covered) {
            // 顶部/底部平滑渐变羽化遮罩（统一 EdgeFadeMask 基元 + 全应用统一 48/44dp 高度）
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

            // 顶部沿表盘外边缘弧形排布的章节名
            CurvedChapterHeader(
                title = currentChapterTitle,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 9 点与 3 点方向贴边弧形排布的竖排电量与时间
            CurvedSideStatusBar(
                modifier = Modifier.fillMaxSize(),
                textColor = colorScheme.onSurfaceVariant
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
                        ReaderPaneLogic.progressPercent(readingPosition.current, fullTextLength)
                    }
                }
                val currentChapterNum = chapterContent.chapterIndex + 1
                val progressText = ReaderPaneLogic.progressText(currentChapterNum, totalChapters, progressPercent)
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 自动滚屏胶囊贴住下缘时，进度字上移让位，避免两层文字叠在一起
                        .padding(bottom = if (isAutoScrolling) 58.dp else 10.dp)
                        .graphicsLayer { alpha = bottomProgressAlpha }
                )
            }

            // 自动滚屏运行时底部弧形贴边按钮：扇带外缘与屏幕圆周齐边
            if (isAutoScrolling) {
                EdgeActionBar(
                    EdgeButtonSpec(
                        label = "自动滚屏 ${autoScrollSpeed.toInt()} px/s",
                        active = true,
                        onClick = onAutoScrollToggle
                    )
                )
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
 * 常亮与前后台三态的唯一实现：
 *
 * - 自动滚屏中永久常亮；静态阅读 5 分钟无交互自动释放休眠省电；
 * - 被覆盖页压顶时立即释放常亮（阅读页已不可见，交还系统息屏策略），退出时自动恢复；
 * - 切到后台或息屏时立即挂起 Choreographer 循环，返回前台自动恢复。
 *
 * 返回的触发器只在触摸/表冠事件里调用，因此取 [activeEngine] 而不是引擎实例：
 * 事件期必须读最新值，按值捕获会把首帧的 null 固化下来。
 */
@Composable
private fun rememberReaderPaneScreenAwake(
    window: Window?,
    keepScreenOnHandler: Handler,
    isAutoScrolling: Boolean,
    covered: Boolean,
    activeEngine: () -> AutoScrollEngine?
): () -> Unit {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    val resetInactivityKeepScreenOn = remember(window, isAutoScrolling) {
        val timeoutMs = ReaderPaneLogic.KEEP_SCREEN_ON_TIMEOUT_MS // 5 分钟无交互超时
        val timeoutRunnable = Runnable {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val trigger: () -> Unit = {
            val flags = window?.attributes?.flags ?: 0
            if ((flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            keepScreenOnHandler.removeCallbacks(timeoutRunnable)
            if (ReaderPaneLogic.shouldArmKeepScreenOnTimeout(isAutoScrolling)) {
                keepScreenOnHandler.postDelayed(timeoutRunnable, timeoutMs)
            }
        }
        trigger
    }

    // 智能后台/息屏能效冻结：切到后台或息屏时立即挂起 Choreographer 循环，返回前台自动恢复
    DisposableEffect(lifecycleOwner, isAutoScrolling) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE, androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    activeEngine()?.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (isAutoScrolling) {
                        activeEngine()?.start()
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

    return resetInactivityKeepScreenOn
}
