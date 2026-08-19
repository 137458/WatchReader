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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * RSVP 单个词元单元
 */
data class RsvpToken(
    val text: String,
    val charOffset: Int,
    val pauseMultiplier: Float = 1.0f
)

/**
 * RSVP 动态闪读 / 单行速读屏幕（极简纯粹防裁切 + 物理表冠调速）
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

    // 闪读主时钟循环
    LaunchedEffect(isPlaying, currentIndex, wordsPerMinute, tokens) {
        if (!isPlaying || tokens.isEmpty()) return@LaunchedEffect

        val token = tokens.getOrNull(currentIndex) ?: return@LaunchedEffect
        val baseDelayMs = (60_000f / wordsPerMinute) * (token.text.length / 2.5f).coerceIn(0.7f, 1.8f)
        val actualDelay = (baseDelayMs * token.pauseMultiplier).toLong().coerceIn(60L, 1200L)

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
            // 右滑手势极速退出返回阅读
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 25f) {
                        onBack()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 顶部圆周弧形章节标题
        CurvedChapterHeader(
            title = chapterContent?.title ?: "闪读模式",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 顶部透明点击感应区（点击顶部直接返回）
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

        // 侧边弧形电量与时间
        CurvedSideStatusBar(
            modifier = Modifier.fillMaxSize(),
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        // 屏幕正中心 RSVP 闪读文字呈现区（轻触中央切换暂停/播放）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
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
            if (currentToken != null) {
                Text(
                    text = currentToken.text,
                    style = TextStyle(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
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

            Spacer(modifier = Modifier.height(10.dp))

            // 进度小字
            val progressPercent = if (tokens.isNotEmpty()) {
                ((currentIndex.toFloat() / tokens.size) * 100).toInt().coerceIn(0, 100)
            } else 0

            Text(
                text = "${currentIndex + 1}/${tokens.size} · $progressPercent%",
                style = TextStyle(fontSize = 10.5.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            )
        }

        // 底部极简胶囊操作栏（高度抬升至 safe area，杜绝任何边缘裁切）
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 退出按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .clickable {
                        RotaryHapticManager.performScrollTick(context, null)
                        onBack()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹ 退出",
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurfaceVariant)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 速度/播放状态胶囊（提示旋转表冠调速）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .clickable {
                        isPlaying = !isPlaying
                        RotaryHapticManager.performScrollTick(context, null)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "⏸ ${wordsPerMinute.toInt()}字/分" else "▶ 继续",
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = colorScheme.primary)
                )
            }
        }
    }
}

/**
 * 将整段正文智能拆解为符合眼球注视节奏的 RSVP 词元
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
            tokens.add(RsvpToken(tokenStr, startOffset + tokenStart, pause))
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
            tokens.add(RsvpToken(tokenStr, startOffset + tokenStart, pause))
            i = endIdx
        }
    }

    return tokens
}

private fun isPunctuation(c: Char): Boolean {
    return c in "，。！？；：、“”‘’（）《》〈〉【】—…,.!?;:'\"()[]<>-~"
}
