package com.watchreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弧形寻道渲染状态容器
 *
 * 手势管线（原生 setOnTouchListener）在每次 MOVE 事件高频写入；叠加层全部在
 * 绘制阶段（Canvas）或派生阶段（derivedStateOf）延迟读取 —— 寻道全程不引发阅读页重组。
 */
class ArcSeekUiState {
    var isSeeking by mutableStateOf(false)
    var touchAngle by mutableStateOf(0f)
    var targetIndex by mutableStateOf(0)
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
    seekState: ArcSeekUiState,
    modifier: Modifier = Modifier
) {
    if (chapters.size <= 1) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val totalChapters = chapters.size

    // 纯绘制组件：手势识别由阅读页原生触摸管线（ArcSeekGestureRecognizer）承担，
    // 禁止在此叠加可命中全屏的 pointerInput，否则会吞掉 AndroidView 正文的全部触摸事件。
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 寻道中屏幕右侧边缘微光弧线与光标（时长统一走 WatchMotion 微切换令牌）
        AnimatedVisibility(
            visible = seekState.isSeeking,
            enter = fadeIn(tween(WatchMotion.DUR_SWAP_OUT)),
            exit = fadeOut(tween(WatchMotion.DUR_SWAP_OUT))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val arcRadius = minOf(cx, cy) - 10f

                // 1. 底层微光导轨弧 (-60° 到 +60°)：强调描边档预混 alpha 令牌
                drawArc(
                    color = primaryColor.copy(alpha = WatchAlpha.ACCENT_BORDER),
                    startAngle = -60f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(cx - arcRadius, cy - arcRadius),
                    size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                // 2. 当前选中位置微光游标（角度于绘制阶段读取，MOVE 事件仅触发重绘）
                val cursorAngleRad = Math.toRadians(seekState.touchAngle.toDouble())
                val cursorX = cx + (arcRadius * cos(cursorAngleRad)).toFloat()
                val cursorY = cy + (arcRadius * sin(cursorAngleRad)).toFloat()

                // 游标晕影（纯装饰辉光，按装饰豁免对比度）
                drawCircle(
                    color = primaryColor.copy(alpha = WatchAlpha.ACCENT_BORDER),
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

        // 寻道中屏幕中央大字悬浮胶囊（文案经 derivedStateOf 去重，仅跨越章节时重组）
        AnimatedVisibility(
            visible = seekState.isSeeking,
            enter = fadeIn(tween(WatchMotion.DUR_SWAP_OUT)),
            exit = fadeOut(tween(WatchMotion.DUR_SWAP_OUT))
        ) {
            val label by remember(chapters) {
                derivedStateOf {
                    val title = chapters.getOrNull(seekState.targetIndex)?.title ?: ""
                    ArcSeekMath.formatSeekLabel(seekState.targetIndex, totalChapters, title)
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(WatchFixed.HudScrim)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    // 恒黑胶囊配固定柔白字：主题 primary 为表面设计，浅色档黑底上对比不足
                    color = WatchFixed.HudInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
