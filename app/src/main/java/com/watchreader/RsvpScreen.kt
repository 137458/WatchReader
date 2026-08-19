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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * RSVP 单个词元单元
 */
data class RsvpToken(
    val text: String,
    val charOffset: Int,
    val pauseMultiplier: Float = 1.0f
)

/**
 * RSVP 动态闪读 / 单行速读屏幕
 */
@Composable
fun RsvpScreen(
    chapterContent: ChapterContent?,
    initialCharOffset: Int,
    onCharOffsetChange: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val focusRequester = remember { FocusRequester() }

    // 闪读模式常亮
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
    var wordsPerMinute by remember { mutableStateOf(350f) } // 默认 350 字/分钟

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
            // 1. 表冠旋转实时调速
            .onRotaryScrollEvent { event ->
                val delta = event.verticalScrollPixels
                if (abs(delta) > 1f) {
                    val step = if (delta > 0) 25f else -25f
                    wordsPerMinute = (wordsPerMinute + step).coerceIn(150f, 900f)
                    RotaryHapticManager.performScrollTick(context, null)
                    true
                } else false
            }
            // 2. 右滑手势极速退出返回阅读
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
                .height(52.dp)
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
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
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
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (currentToken != null) {
                Text(
                    text = currentToken.text,
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 1.5.sp,
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

            Spacer(modifier = Modifier.height(14.dp))

            // 进度小字
            val progressPercent = if (tokens.isNotEmpty()) {
                ((currentIndex.toFloat() / tokens.size) * 100).toInt().coerceIn(0, 100)
            } else 0

            Text(
                text = "${currentIndex + 1}/${tokens.size} · $progressPercent%",
                style = TextStyle(fontSize = 10.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            )
        }

        // 底部多功能胶囊操作栏（返回 / 暂停播放 / 调速）
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 退出按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "‹ 退出",
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurfaceVariant)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 播放/暂停状态胶囊
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.94f))
                    .clickable { isPlaying = !isPlaying }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "⏸ ${wordsPerMinute.toInt()}字/分" else "▶ 已暂停",
                    style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = colorScheme.primary)
                )
            }
        }
    }
}

/**
 * 将整段正文智能拆解为符合眼球注视节奏的 RSVP 词元
 */
fun tokenizeRsvpText(text: String, baseCharOffset: Int): List<RsvpToken> {
    if (text.isEmpty()) return emptyList()
    val list = mutableListOf<RsvpToken>()
    var i = 0
    val len = text.length

    while (i < len) {
        // 跳过前导空白字符
        while (i < len && text[i].isWhitespace()) {
            i++
        }
        if (i >= len) break

        val tokenStart = i
        var tokenEnd = i

        val firstChar = text[i]
        if (firstChar.isLetterOrDigit() && firstChar.code < 128) {
            // 英文单词
            while (tokenEnd < len && !text[tokenEnd].isWhitespace() && text[tokenEnd].code < 128 && !isPunctuation(text[tokenEnd])) {
                tokenEnd++
            }
        } else {
            // 中文字词：取 2~4 个字
            var count = 0
            while (tokenEnd < len && count < 3 && !text[tokenEnd].isWhitespace() && !isPunctuation(text[tokenEnd])) {
                tokenEnd++
                count++
            }
        }

        // 包含紧随其后的标点符号
        var pauseMult = 1.0f
        while (tokenEnd < len && isPunctuation(text[tokenEnd])) {
            val p = text[tokenEnd]
            pauseMult = when (p) {
                '。', '！', '？', '…' -> 2.2f
                '，', '、', '；', '：' -> 1.5f
                '”', '’', '）' -> 1.3f
                else -> 1.2f
            }
            tokenEnd++
        }

        if (tokenEnd > tokenStart) {
            val chunk = text.substring(tokenStart, tokenEnd).trim()
            if (chunk.isNotEmpty()) {
                list.add(
                    RsvpToken(
                        text = chunk,
                        charOffset = baseCharOffset + tokenStart,
                        pauseMultiplier = pauseMult
                    )
                )
            }
        }

        i = maxOf(tokenEnd, i + 1)
    }

    return list
}

private fun isPunctuation(c: Char): Boolean {
    return c == '，' || c == '。' || c == '！' || c == '？' || c == '、' ||
            c == '；' || c == '：' || c == '…' || c == '—' || c == '”' ||
            c == '“' || c == '’' || c == '‘' || c == '（' || c == '）' ||
            c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':'
}
