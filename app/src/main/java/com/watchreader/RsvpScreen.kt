package com.watchreader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * RSVP 单个词元单元（包含 ORP 最佳注视焦点索引）
 */
data class RsvpToken(
    val text: String,
    val charOffset: Int,
    val pauseMultiplier: Float = 1.0f,
    val orpIndex: Int = 0
)

/**
 * RSVP 动态闪读 / 单行速读屏幕（极简防裁切 + 物理表冠高精度调速 + 触屏档位切换）
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

    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val focusRequester = remember { FocusRequester() }

    // 闪读模式屏幕常亮
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val colorScheme = MaterialTheme.colorScheme

    // 分词并计算相对偏移
    val fullText = chapterContent?.formattedBody ?: ""
    val chapterStartOffset = chapterContent?.startCharOffset ?: 0

    val tokens = remember(fullText) {
        tokenizeRsvpText(fullText, chapterStartOffset)
    }

    // 找到初始 Token 索引
    var currentIndex by remember(tokens, initialCharOffset) {
        mutableStateOf(
            if (tokens.isEmpty()) 0 else {
                val idx = tokens.indexOfFirst { it.charOffset >= initialCharOffset }
                if (idx >= 0) idx else 0
            }
        )
    }

    var isPlaying by remember { mutableStateOf(true) }

    val currentToken = if (tokens.isNotEmpty() && currentIndex in tokens.indices) tokens[currentIndex] else null

    // 闪读主时钟循环（响应 wordsPerMinute 毫秒级动态调速）
    LaunchedEffect(isPlaying, currentIndex, wordsPerMinute, tokens) {
        if (!isPlaying || tokens.isEmpty()) return@LaunchedEffect

        val token = tokens.getOrNull(currentIndex) ?: return@LaunchedEffect
        val baseDelayMs = (60_000f / wordsPerMinute) * (token.text.length / 2.5f).coerceIn(0.7f, 1.8f)
        val actualDelay = (baseDelayMs * token.pauseMultiplier).toLong().coerceIn(40L, 1200L)

        delay(actualDelay)

        if (currentIndex + 1 < tokens.size) {
            currentIndex++
            onCharOffsetChange(tokens[currentIndex].charOffset)
        } else {
            // 读完本章，若有下一章则自动请求翻章
            if (chapterContent?.hasNextChapter == true) {
                onNextChapter()
            } else {
                isPlaying = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            // 表冠物理旋转监听（Compose 辅助通道）
            .onRotaryScrollEvent { event ->
                val delta = event.verticalScrollPixels
                if (abs(delta) > 0.5f) {
                    val step = if (delta > 0) 25f else -25f
                    val newSpeed = (wordsPerMinute + step).coerceIn(100f, 900f)
                    onSpeedChange(newSpeed)
                    RotaryHapticManager.performScrollTick(context, null)
                    true
                } else false
            }
            // 水平滑动手势：左滑回退 5 词，右滑快进 5 词
            .pointerInput(tokens, currentIndex) {
                var dragAccumulator = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        if (dragAccumulator > 30f) {
                            dragAccumulator = 0f
                            if (tokens.isNotEmpty()) {
                                val nextIdx = (currentIndex + 5).coerceAtMost(tokens.lastIndex)
                                if (nextIdx != currentIndex) {
                                    currentIndex = nextIdx
                                    onCharOffsetChange(tokens[nextIdx].charOffset)
                                    RotaryHapticManager.performScrollTick(context, null)
                                }
                            }
                        } else if (dragAccumulator < -30f) {
                            dragAccumulator = 0f
                            if (tokens.isNotEmpty()) {
                                val prevIdx = (currentIndex - 5).coerceAtLeast(0)
                                if (prevIdx != currentIndex) {
                                    currentIndex = prevIdx
                                    onCharOffsetChange(tokens[prevIdx].charOffset)
                                    RotaryHapticManager.performScrollTick(context, null)
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
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        // 4. 屏幕正中心 RSVP 闪读文字呈现区（轻触中央切换 暂停/继续，ORP 焦点高亮）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    isPlaying = !isPlaying
                    RotaryHapticManager.performScrollTick(context, null)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ORP 顶部对齐微标指示器
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colorScheme.primary.copy(alpha = 0.55f))
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (currentToken != null) {
                val tokenText = currentToken.text
                val orp = currentToken.orpIndex.coerceIn(0, maxOf(0, tokenText.length - 1))
                val annotatedString = remember(currentToken, colorScheme) {
                    buildAnnotatedString {
                        if (orp > 0) {
                            withStyle(SpanStyle(color = colorScheme.onBackground.copy(alpha = 0.85f), fontWeight = FontWeight.Normal)) {
                                append(tokenText.substring(0, orp))
                            }
                        }
                        if (orp < tokenText.length) {
                            withStyle(SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.ExtraBold)) {
                                append(tokenText[orp])
                            }
                        }
                        if (orp + 1 < tokenText.length) {
                            withStyle(SpanStyle(color = colorScheme.onBackground.copy(alpha = 0.85f), fontWeight = FontWeight.Normal)) {
                                append(tokenText.substring(orp + 1))
                            }
                        }
                    }
                }

                Text(
                    text = annotatedString,
                    style = TextStyle(
                        fontSize = 36.sp,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1
                )
            } else {
                Text(
                    text = if (fullText.isEmpty()) "加载中…" else "全章阅读完毕",
                    style = TextStyle(fontSize = 16.sp, color = colorScheme.onSurfaceVariant)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ORP 底部对齐微标指示器
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colorScheme.primary.copy(alpha = 0.55f))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 进度小字
            val progressPercent = if (tokens.isNotEmpty()) {
                ((currentIndex.toFloat() / tokens.size) * 100).toInt().coerceIn(0, 100)
            } else 0

            Text(
                text = "${currentIndex + 1}/${tokens.size} · $progressPercent%",
                style = TextStyle(fontSize = 10.5.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            )
        }

        // 5. 底部极简操作胶囊（边距 30dp，彻底规避 466x466 圆屏底弧裁切）
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ‹ 退出
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .clickable {
                        RotaryHapticManager.performScrollTick(context, null)
                        onBack()
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹ 退出",
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 速度/播放状态胶囊（点击可循环切换预设档位，旋转表冠可任意线性微调）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .clickable {
                        // 点击循环档位：250 -> 350 -> 450 -> 600 -> 800 -> 250
                        val nextSpeed = when {
                            wordsPerMinute < 300f -> 350f
                            wordsPerMinute < 400f -> 450f
                            wordsPerMinute < 550f -> 600f
                            wordsPerMinute < 750f -> 800f
                            else -> 250f
                        }
                        onSpeedChange(nextSpeed)
                        RotaryHapticManager.performScrollTick(context, null)
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "⚡ ${wordsPerMinute.toInt()}字/分" else "▶ 点击继续",
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                )
            }
        }
    }
}

/**
 * 计算词元的 ORP (Optimal Recognition Point) 最佳注视焦点索引
 * 0~2 字符: 索引 0 (首字)
 * 3~5 字符: 索引 1 (次字/中字)
 * 6~9 字符: 索引 2
 * 10+ 字符: 约 35% 黄金分割点
 */
fun calculateOrpIndex(word: String): Int {
    val len = word.length
    if (len <= 2) return 0
    if (len in 3..5) return 1
    if (len in 6..9) return 2
    return (len * 0.35f).toInt().coerceIn(0, len - 1)
}

/**
 * 将整段正文智能拆解为符合眼球注视节奏的 RSVP 词元（包含 ORP 焦点）
 */
fun tokenizeRsvpText(text: String, startOffset: Int): List<RsvpToken> {
    if (text.isEmpty()) return emptyList()

    val tokens = mutableListOf<RsvpToken>()
    var i = 0
    val len = text.length

    while (i < len) {
        val c = text[i]
        if (c.isWhitespace()) {
            i++
            continue
        }

        val tokenStart = i
        val isChinese = c.code in 0x4E00..0x9FA5 || c.code in 0x3400..0x4DBF

        if (isChinese) {
            var count = 1
            while (tokenStart + count < len && count < 2) {
                val nextC = text[tokenStart + count]
                if (nextC.code in 0x4E00..0x9FA5) {
                    count++
                } else break
            }

            var pause = 1.0f
            var endIdx = tokenStart + count
            while (endIdx < len && isPunctuation(text[endIdx])) {
                val p = text[endIdx]
                pause = when (p) {
                    '。', '！', '？', '…' -> 2.2f
                    '，', '、', '；', '：' -> 1.6f
                    else -> 1.2f
                }
                endIdx++
            }

            val tokenStr = text.substring(tokenStart, endIdx)
            val orp = calculateOrpIndex(tokenStr)
            tokens.add(RsvpToken(tokenStr, startOffset + tokenStart, pause, orp))
            i = endIdx
        } else {
            var endIdx = tokenStart
            var pause = 1.0f
            while (endIdx < len && !text[endIdx].isWhitespace()) {
                if (isPunctuation(text[endIdx])) {
                    val p = text[endIdx]
                    pause = when (p) {
                        '.', '!', '?' -> 2.2f
                        ',', ';', ':' -> 1.6f
                        else -> 1.2f
                    }
                    endIdx++
                    break
                }
                endIdx++
            }

            val tokenStr = text.substring(tokenStart, endIdx)
            val orp = calculateOrpIndex(tokenStr)
            tokens.add(RsvpToken(tokenStr, startOffset + tokenStart, pause, orp))
            i = endIdx
        }
    }

    return tokens
}

private fun isPunctuation(c: Char): Boolean {
    return c in "，。！？；：、“”‘’（）《》〈〉【】—…,.!?;:'\"()[]<>-~"
}
