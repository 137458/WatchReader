package com.watchreader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * 速读节拍与进度口径：全部为纯函数（只依赖 Kotlin 标准库，不引用任何 Android / Compose 类型），
 * 用于 JVM 单测锁定档位循环、节拍公式、跳词钳制与进度文案。
 *
 * 强阻尼口径来自 ADR-007（节拍由 wordsPerMinute 线性映射，40~1200ms 钳制）；
 * 本对象只做取值与钳制，不含任何动画参数。
 */
internal object RsvpPacing {
    /** 横滑跳词门限（px）：累积超过该值才跳一格，避免轻触抖动误跳 */
    const val DRAG_THRESHOLD_PX = 55f

    /** 单次横滑跳词的词元数 */
    const val DRAG_STEP_TOKENS = 5

    /** 档位循环：250 -> 350 -> 450 -> 600 -> 800 -> 250 */
    fun nextSpeedTier(wordsPerMinute: Float): Float = when {
        wordsPerMinute < 300f -> 350f
        wordsPerMinute < 400f -> 450f
        wordsPerMinute < 550f -> 600f
        wordsPerMinute < 750f -> 800f
        else -> 250f
    }

    /** 词长加权的基准节拍（毫秒），长词停留更久 */
    fun baseDelayMs(wordsPerMinute: Float, tokenLength: Int): Float =
        (60_000f / wordsPerMinute) * (tokenLength / 2.5f).coerceIn(0.7f, 1.8f)

    /** 叠加标点停顿倍率后的实际节拍，钳制在 40~1200ms */
    fun actualDelayMs(wordsPerMinute: Float, tokenLength: Int, pauseMultiplier: Float): Long =
        (baseDelayMs(wordsPerMinute, tokenLength) * pauseMultiplier).toLong().coerceIn(40L, 1200L)

    /** 横滑跳词目标索引：正负方向均钳制在 [0, total-1]；空词表时原地不动 */
    fun jumpTarget(base: Int, delta: Int, total: Int): Int =
        if (total <= 0) base else (base + delta).coerceIn(0, total - 1)

    /** 本章阅读进度百分比 */
    fun progressPercent(index: Int, total: Int): Int =
        if (total > 0) ((index.toFloat() / total) * 100).toInt().coerceIn(0, 100) else 0

    /** 进度小字：序号 / 总词元数 / 百分比 */
    fun progressLabel(index: Int, total: Int): String =
        "${index + 1}/$total · ${progressPercent(index, total)}%"

    /** 操作条速度键文案：播放中显示当前节拍，暂停时显示「继续」 */
    fun speedLabel(wordsPerMinute: Float, isPlaying: Boolean): String =
        if (isPlaying) "${wordsPerMinute.toInt()} 字/分" else "继续"
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
        val isChinese = isCjkIdeograph(c.code)

        if (isChinese) {
            var count = 1
            while (tokenStart + count < len && count < 2) {
                val nextC = text[tokenStart + count]
                if (isCjkIdeograph(nextC.code)) {
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

/** CJK 汉字判定（基本区 + 扩展 A 区），首字与成组续字共用同一集合 */
private fun isCjkIdeograph(code: Int): Boolean {
    return code in 0x4E00..0x9FA5 || code in 0x3400..0x4DBF
}

private fun isPunctuation(c: Char): Boolean {
    return c in "，。！？；：、“”‘’（）《》〈〉【】—…,.!?;:'\"()[]<>-~"
}

/**
 * 屏幕正中心 RSVP 闪读文字呈现区：ORP 焦点高亮 + 上下对齐微标 + 进度小字。
 * 轻触该区域切换 暂停/继续（触觉与播放状态由页面注入）。
 */
@Composable
internal fun RsvpWordStage(
    token: RsvpToken?,
    fullText: String,
    currentIndex: Int,
    totalTokens: Int,
    onTogglePlay: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onTogglePlay()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ORP 顶部对齐微标指示器
        OrpMarker(color = colorScheme.primary)

        Spacer(modifier = Modifier.height(4.dp))

        if (token != null) {
            val tokenText = token.text
            val orp = token.orpIndex.coerceIn(0, maxOf(0, tokenText.length - 1))
            val annotatedString = remember(token, colorScheme) {
                buildAnnotatedString {
                    // ORP 焦点字前后的非焦点部分用次级墨角色（onSurfaceVariant）：
                    // 视觉降调由角色承担，不再运行时叠 alpha
                    if (orp > 0) {
                        withStyle(SpanStyle(color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                            append(tokenText.substring(0, orp))
                        }
                    }
                    if (orp < tokenText.length) {
                        withStyle(SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.ExtraBold)) {
                            append(tokenText[orp])
                        }
                    }
                    if (orp + 1 < tokenText.length) {
                        withStyle(SpanStyle(color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                            append(tokenText.substring(orp + 1))
                        }
                    }
                }
            }

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 36.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1
            )
        } else {
            Text(
                text = if (fullText.isEmpty()) "加载中…" else "全章阅读完毕",
                style = MaterialTheme.typography.titleLarge.copy(color = colorScheme.onSurfaceVariant)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ORP 底部对齐微标指示器
        OrpMarker(color = colorScheme.primary)

        Spacer(modifier = Modifier.height(8.dp))

        // 进度小字
        Text(
            text = RsvpPacing.progressLabel(currentIndex, totalTokens),
            style = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)
        )
    }
}

@Composable
private fun OrpMarker(color: Color) {
    Box(
        modifier = Modifier
            .size(width = 3.dp, height = 5.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(color)
    )
}
