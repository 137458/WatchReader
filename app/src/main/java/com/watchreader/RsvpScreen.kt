package com.watchreader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    val window = (context as? Activity)?.window

    // 闪读模式常亮
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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

    // 表冠旋转调速与按键拦截
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isPlaying = !isPlaying
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. 顶部圆周弧形章节标题
        CurvedChapterHeader(
            title = chapterContent?.title ?: "",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 2. 侧边弧形电量与时间
        CurvedSideStatusBar(
            modifier = Modifier.fillMaxSize(),
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )

        // 3. 屏幕正中心 RSVP 闪读文字核心呈现区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (currentToken != null) {
                Text(
                    text = currentToken.text,
                    style = TextStyle(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 1.2.sp,
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

            Spacer(modifier = Modifier.height(18.dp))

            // 进度小字
            val progressPercent = if (tokens.isNotEmpty()) {
                ((currentIndex.toFloat() / tokens.size) * 100).toInt().coerceIn(0, 100)
            } else 0

            Text(
                text = "${currentIndex + 1} / ${tokens.size} · $progressPercent%",
                style = TextStyle(fontSize = 10.sp, color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            )
        }

        // 4. 底部播放/暂停与语速控制浮标
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                .clickable { isPlaying = !isPlaying }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPlaying) "⏸ ${wordsPerMinute.toInt()} 字/分" else "▶ 已暂停 (轻触继续)",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colorScheme.primary)
            )
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
