package com.watchreader

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
        // 寻道中屏幕右侧边缘微光弧线与光标
        AnimatedVisibility(
            visible = seekState.isSeeking,
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

                // 2. 当前选中位置微光游标（角度于绘制阶段读取，MOVE 事件仅触发重绘）
                val cursorAngleRad = Math.toRadians(seekState.touchAngle.toDouble())
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

        // 寻道中屏幕中央大字悬浮胶囊（文案经 derivedStateOf 去重，仅跨越章节时重组）
        AnimatedVisibility(
            visible = seekState.isSeeking,
            enter = fadeIn(),
            exit = fadeOut()
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

/**
 * 弧形寻道手势识别状态机（原生触摸管线驱动）
 *
 * 设计约束：寻道交互不可再由全屏 Compose pointerInput 承接 —— 在 AndroidView 互操作下
 * 该叠加层会吞掉阅读页整条触摸流，导致点击翻页、长按菜单、滑动滚动全部失效。
 * 因此将识别逻辑下沉为不依赖平台的可测状态机，由阅读页原生 setOnTouchListener 驱动。
 */
class ArcSeekGestureRecognizer {

    /** 按下点落在寻道带内，进入候选态 */
    var isTracking: Boolean = false
        private set

    /** 已越过拖动门限，进入寻道态（此时应阻断正文滚动） */
    var isSeeking: Boolean = false
        private set

    /** 当前手指极坐标角度（已钳制到寻道区间） */
    var currentAngle: Float = 0f
        private set

    /** 当前指向的章节索引 */
    var targetChapterIndex: Int = 0
        private set

    private var initAngle = 0f
    private var startX = 0f
    private var startY = 0f
    private var hasDragged = false
    private var cancelled = false
    private var lastValidIndex = 0

    /**
     * ACTION_DOWN
     * @return true 表示按下落在寻道带内（后续移动需交由寻道识别）
     */
    fun onDown(x: Float, y: Float, width: Float, height: Float, currentChapterIndex: Int): Boolean {
        reset()
        val cx = width / 2f
        val cy = height / 2f
        if (!ArcSeekMath.isInSeekZone(x, y, cx, cy)) return false

        isTracking = true
        startX = x
        startY = y
        initAngle = angleOf(x, y, cx, cy)
        lastValidIndex = currentChapterIndex
        targetChapterIndex = currentChapterIndex
        return true
    }

    /**
     * ACTION_MOVE
     * @return true 表示本次移动已被寻道消费（应阻断正文滚动与点按判定）
     */
    fun onMove(x: Float, y: Float, width: Float, height: Float, totalChapters: Int): Boolean {
        if (!isTracking) return false

        val cx = width / 2f
        val cy = height / 2f
        val r = kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
        val angle = angleOf(x, y, cx, cy)
        val dragDist = kotlin.math.hypot((x - startX).toDouble(), (y - startY).toDouble()).toFloat()
        val angleDelta = kotlin.math.abs(angle - initAngle)

        if (!hasDragged) {
            // 门限：角度变化 ≥3° 或位移 ≥10px 才认定为寻道拖动，避免轻触误跳
            if (angleDelta >= 3.0f || dragDist >= 10f) {
                hasDragged = true
                isSeeking = true
            }
        }

        if (!isSeeking) return false

        // 划入屏幕过深或角度超出范围则取消本次寻道
        if (r < 165f || angle < -75f || angle > 75f) {
            cancelled = true
        } else {
            cancelled = false
            currentAngle = angle.coerceIn(ArcSeekMath.MIN_ANGLE, ArcSeekMath.MAX_ANGLE)
            targetChapterIndex = ArcSeekMath.angleToChapterIndex(currentAngle, totalChapters)
            lastValidIndex = targetChapterIndex
        }
        return true
    }

    /**
     * ACTION_UP / ACTION_CANCEL
     * @return 确认跳转的章节索引；null 表示不跳转
     */
    fun onUp(): Int? {
        val result = if (isTracking && hasDragged && !cancelled) lastValidIndex else null
        val wasTracking = isTracking
        reset()
        if (!wasTracking) return null
        return result
    }

    fun reset() {
        isTracking = false
        isSeeking = false
        hasDragged = false
        cancelled = false
    }

    private fun angleOf(x: Float, y: Float, cx: Float, cy: Float): Float {
        return Math.toDegrees(kotlin.math.atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    }
}