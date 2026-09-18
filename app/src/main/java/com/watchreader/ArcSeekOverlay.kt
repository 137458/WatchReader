package com.watchreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

object ArcSeekMath {
    const val SCREEN_SIZE = 466f
    const val RADIUS = 233f
    const val MIN_RADIUS = 210f
    const val MAX_RADIUS = 233f
    const val MIN_ANGLE = -60f
    const val MAX_ANGLE = 60f

    /**
     * 判断触控点是否位于右侧弧形寻道响应带（容差判定，便于手指触达）
     */
    fun isInSeekZone(x: Float, y: Float, cx: Float = 233f, cy: Float = 233f): Boolean {
        val dx = x - cx
        val dy = y - cy
        val r = sqrt(dx * dx + dy * dy)
        // 允许内侧适度容差 [200, 245]，消除边缘触控硬切
        if (r < 200f || r > 245f) return false
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return angleDeg in MIN_ANGLE..MAX_ANGLE
    }

    /**
     * 计算当前极坐标角度对应的进度比例 [0.0, 1.0]
     */
    fun calculateProgress(angleDeg: Float): Float {
        val clamped = angleDeg.coerceIn(MIN_ANGLE, MAX_ANGLE)
        return (clamped - MIN_ANGLE) / (MAX_ANGLE - MIN_ANGLE)
    }

    /**
     * 将极坐标角度映射为目标章节索引
     */
    fun angleToChapterIndex(angleDeg: Float, totalChapters: Int): Int {
        if (totalChapters <= 0) return 0
        val progress = calculateProgress(angleDeg)
        return (progress * (totalChapters - 1)).roundToInt().coerceIn(0, totalChapters - 1)
    }

    /**
     * 格式化寻道悬浮胶囊文案：“第 X/Y 章 · Z% (章节名)”
     */
    fun formatSeekLabel(chapterIndex: Int, totalChapters: Int, chapterTitle: String): String {
        val currentNum = chapterIndex + 1
        val percent = if (totalChapters > 0) ((currentNum * 100) / totalChapters) else 0
        val cleanTitle = chapterTitle.trim()
        val titlePart = if (cleanTitle.isNotEmpty()) " ($cleanTitle)" else ""
        return "第 $currentNum/$totalChapters 章 · $percent%$titlePart"
    }
}

/**
 * F-05 表盘边缘弧形快速寻道滑块组件
 *
 * 适用于 466x466 圆屏，在屏幕右侧边缘弧形带识别滑动手势；
 * 滑动时屏幕中央弹出半透明大字悬浮胶囊，右侧弧形绘制跟随手指的微光游标；
 * 松手时确认跳转，划出边缘自动取消。
 */
@Composable
fun ArcSeekOverlay(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onSeekConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (chapters.size <= 1) return

    var isSeeking by remember { mutableStateOf(false) }
    var currentTouchAngle by remember { mutableStateOf(0f) }
    var targetChapterIndex by remember { mutableStateOf(currentChapterIndex) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val totalChapters = chapters.size

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(totalChapters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    if (ArcSeekMath.isInSeekZone(down.position.x, down.position.y, cx, cy)) {
                        val dx = down.position.x - cx
                        val dy = down.position.y - cy
                        val initAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        val startX = down.position.x
                        val startY = down.position.y

                        var hasDragged = false
                        var cancelled = false
                        var lastValidIndex = currentChapterIndex

                        while (true) {
                            val event = awaitPointerEvent()
                            val change: PointerInputChange? = event.changes.firstOrNull()

                            if (change == null || change.isConsumed) {
                                break
                            }

                            if (change.pressed) {
                                val curDx = change.position.x - cx
                                val curDy = change.position.y - cy
                                val r = sqrt(curDx * curDx + curDy * curDy)
                                val curAngle = Math.toDegrees(atan2(curDy.toDouble(), curDx.toDouble())).toFloat()
                                val dragDist = hypot(change.position.x - startX, change.position.y - startY)
                                val angleDelta = abs(curAngle - initAngle)

                                if (!hasDragged) {
                                    if (angleDelta >= 3.0f || dragDist >= 10f) {
                                        hasDragged = true
                                        isSeeking = true
                                        down.consume()
                                    }
                                }

                                if (hasDragged) {
                                    change.consume()
                                    // 划入屏幕过深 (r < 165) 或角度超出范围则取消寻道
                                    if (r < 165f || curAngle < -75f || curAngle > 75f) {
                                        cancelled = true
                                    } else {
                                        cancelled = false
                                        currentTouchAngle = curAngle.coerceIn(ArcSeekMath.MIN_ANGLE, ArcSeekMath.MAX_ANGLE)
                                        targetChapterIndex = ArcSeekMath.angleToChapterIndex(currentTouchAngle, totalChapters)
                                        lastValidIndex = targetChapterIndex
                                    }
                                }
                            } else {
                                // 松手确认
                                if (hasDragged) {
                                    change.consume()
                                    if (!cancelled) {
                                        onSeekConfirm(lastValidIndex)
                                    }
                                }
                                break
                            }
                        }
                        isSeeking = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 寻道中屏幕右侧边缘微光弧线与光标
        AnimatedVisibility(
            visible = isSeeking,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val arcRadius = minOf(cx, cy) - 10f

                // 1. 底层微光导轨弧 (-60° 到 +60°)
                drawArc(
                    color = primaryColor.copy(alpha = 0.25f),
                    startAngle = -60f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(cx - arcRadius, cy - arcRadius),
                    size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                // 2. 当前选中位置微光游标
                val cursorAngleRad = Math.toRadians(currentTouchAngle.toDouble())
                val cursorX = cx + (arcRadius * cos(cursorAngleRad)).toFloat()
                val cursorY = cy + (arcRadius * sin(cursorAngleRad)).toFloat()

                // 游标晕影
                drawCircle(
                    color = primaryColor.copy(alpha = 0.40f),
                    radius = 12.dp.toPx(),
                    center = Offset(cursorX, cursorY)
                )
                // 游标中心高亮光点
                drawCircle(
                    color = primaryColor,
                    radius = 5.dp.toPx(),
                    center = Offset(cursorX, cursorY)
                )
            }
        }

        // 寻道中屏幕中央大字悬浮胶囊
        AnimatedVisibility(
            visible = isSeeking,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val title = chapters.getOrNull(targetChapterIndex)?.title ?: ""
            val label = ArcSeekMath.formatSeekLabel(targetChapterIndex, totalChapters, title)

            Box(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .background(
                        color = Color(0xDD000000),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = primaryColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
