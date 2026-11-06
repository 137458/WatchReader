package com.watchreader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * RSVP 动态闪读 / 单行速读屏幕（极简防裁切 + 物理表冠高精度调速 + 触屏档位切换）
 *
 * 表冠统一走 Activity 顶层分派（MainActivity → CrownRouter 第 1 条策略 → ReaderViewModel.handleRotaryScroll），
 * 本页不再自带 onRotaryScrollEvent —— 该路径在路由消费事件后永远收不到输入，
 * 历史上属于与主管线调参互不相认的第二套死代码。
 *
 * 组件口径：页面外壳保持自建全屏 Box（RSVP 无滚动区与安全区留白需求，
 * 中心词元需要按圆屏几何最大化）；中心呈现区改用 Wear M3 官方件
 * （Text / LinearProgressIndicator），弧形标题、侧边状态条与底部操作条沿用既有实现。
 *
 * 词元结构（RsvpToken）、分词与 ORP、节拍/进度纯口径、中心呈现区段组件见 RsvpSections.kt。
 */
@Composable
fun RsvpScreen(
    chapterContent: ChapterContent?,
    initialCharOffset: Int,
    wordsPerMinute: Float = 350f,
    onCharOffsetChange: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onSpeedChange: (Float) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val window = (LocalContext.current as? Activity)?.window

    // 闪读模式屏幕常亮
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val tick = rememberTickHaptic()

    val colorScheme = MaterialTheme.colorScheme

    // 分词：tokenizer 给出的 charOffset 落在正文域（formattedBody 索引），
    // 而跨页跳转与持久化的约定是「全书绝对原文偏移」，中间必须经 ChapterOffsetMapper
    // 补回每段缩进与段间换行，直接相加章节起点会把位置落在错误字符上。
    val fullText = chapterContent?.formattedBody ?: ""
    val bodyToBookOffset: (Int) -> Int = remember(chapterContent) {
        { bodyIndex ->
            val content = chapterContent
            if (content == null) {
                bodyIndex
            } else {
                content.startCharOffset + ChapterOffsetMapper.bodyToRaw(
                    bodyIndex,
                    content.bodyParagraphStarts,
                    content.rawParagraphStarts,
                    content.formattedBody.length,
                    rawLength = content.endCharOffset - content.startCharOffset,
                    indentChars = content.paragraphIndentChars
                )
            }
        }
    }

    val tokens = remember(fullText) {
        tokenizeRsvpText(fullText, startOffset = 0)
    }

    // 找到初始 Token 索引
    var currentIndex by remember(tokens, initialCharOffset) {
        mutableStateOf(
            if (tokens.isEmpty()) 0 else {
                val idx = tokens.indexOfFirst { bodyToBookOffset(it.charOffset) >= initialCharOffset }
                if (idx >= 0) idx else 0
            }
        )
    }

    var isPlaying by remember { mutableStateOf(true) }

    val currentToken = if (tokens.isNotEmpty() && currentIndex in tokens.indices) tokens[currentIndex] else null

    // 手势管线内读取的最新索引：pointerInput 只随分词结果重建（换章），
    // 若以 currentIndex 为键，播放中每个词推进都会拆掉重建手势通道，
    // 拖词手势中途被取消（accumulator 清零），表现为"播放中横滑跳词经常无效"
    val latestIndex = rememberUpdatedState(currentIndex)

    // 闪读主时钟循环（响应 wordsPerMinute 毫秒级动态调速）
    LaunchedEffect(isPlaying, currentIndex, wordsPerMinute, tokens) {
        if (!isPlaying || tokens.isEmpty()) return@LaunchedEffect

        val token = tokens.getOrNull(currentIndex) ?: return@LaunchedEffect
        delay(RsvpPacing.actualDelayMs(wordsPerMinute, token.text.length, token.pauseMultiplier))

        if (currentIndex + 1 < tokens.size) {
            currentIndex++
            onCharOffsetChange(bodyToBookOffset(tokens[currentIndex].charOffset))
        } else {
            // 读完本章，若有下一章则自动请求翻章
            if (chapterContent?.hasNextChapter == true) {
                onNextChapter()
            } else {
                isPlaying = false
            }
        }
    }

    WatchReaderWearTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 水平滑动手势：增加阻尼门限，避免轻触抖动时误跳词
                .pointerInput(tokens) {
                    var dragAccumulator = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            dragAccumulator += dragAmount
                            val base = latestIndex.value
                            if (dragAccumulator > RsvpPacing.DRAG_THRESHOLD_PX) {
                                dragAccumulator = 0f
                                if (tokens.isNotEmpty()) {
                                    val nextIdx = RsvpPacing.jumpTarget(base, RsvpPacing.DRAG_STEP_TOKENS, tokens.size)
                                    if (nextIdx != base) {
                                        currentIndex = nextIdx
                                        onCharOffsetChange(bodyToBookOffset(tokens[nextIdx].charOffset))
                                        tick()
                                    }
                                }
                            } else if (dragAccumulator < -RsvpPacing.DRAG_THRESHOLD_PX) {
                                dragAccumulator = 0f
                                if (tokens.isNotEmpty()) {
                                    val prevIdx = RsvpPacing.jumpTarget(base, -RsvpPacing.DRAG_STEP_TOKENS, tokens.size)
                                    if (prevIdx != base) {
                                        currentIndex = prevIdx
                                        onCharOffsetChange(bodyToBookOffset(tokens[prevIdx].charOffset))
                                        tick()
                                    }
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // 1. 顶部圆周弧形章节标题
            CurvedChapterHeader(
                title = chapterContent?.title ?: "闪读模式",
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 2. 顶部透明点击感应区（点击顶部直接退出）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.TopCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack
                    )
            )

            // 3. 侧边弧形电量与时间
            CurvedSideStatusBar(
                modifier = Modifier.fillMaxSize(),
                textColor = colorScheme.onSurfaceVariant
            )

            // 4. 屏幕正中心 RSVP 闪读文字呈现区（轻触中央切换 暂停/继续，ORP 焦点高亮）
            RsvpWordStage(
                token = currentToken,
                fullText = fullText,
                currentIndex = currentIndex,
                totalTokens = tokens.size,
                onTogglePlay = {
                    isPlaying = !isPlaying
                    tick()
                }
            )

            // 5. 底部弧形贴边操作条：扇带处处贴合圆屏下缘
            EdgeActionBar(
                // ‹ 退出
                EdgeButtonSpec(
                    label = "‹ 退出",
                    onClick = onBack
                ),

                // 速度/播放状态扇带（点击可循环切换预设档位，旋转表冠可任意线性微调）
                EdgeButtonSpec(
                    label = RsvpPacing.speedLabel(wordsPerMinute, isPlaying),
                    emphasis = PillEmphasis.Outline,
                    onClick = {
                        // 点击循环档位：250 -> 350 -> 450 -> 600 -> 800 -> 250
                        onSpeedChange(RsvpPacing.nextSpeedTier(wordsPerMinute))
                    }
                )
            )
        }
    }
}
