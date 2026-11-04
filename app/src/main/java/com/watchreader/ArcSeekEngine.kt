package com.watchreader

/**
 * 弧形寻道的纯 JVM 内核：极坐标几何、角度↔进度换算、触点命中判定与手势状态机
 *
 * 零 Android / Compose import —— 绘制层（ArcSeekOverlay.kt）与阅读页原生触摸管线共用同一份判定；
 * 寻道带 r∈[SEEK_RADIUS_INNER, SEEK_RADIUS_OUTER]、角度 ∈[MIN_ANGLE, MAX_ANGLE]，数值沿用拆分前的既有取值
 * （docs/adr 中没有约束寻道几何的决策记录，本次只搬移位置、不改任何常量）。
 */

import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ArcSeekMath {
    const val MIN_ANGLE = -60f
    const val MAX_ANGLE = 60f

    // 寻道响应带半径容差：消除边缘触控硬切
    const val SEEK_RADIUS_INNER = 200f
    const val SEEK_RADIUS_OUTER = 245f

    // 划入屏幕过深（半径过小）或角度超出容差即取消本次寻道
    const val SEEK_CANCEL_RADIUS = 165f
    const val SEEK_CANCEL_ANGLE = 75f

    /**
     * 判断触控点是否位于右侧弧形寻道响应带（容差判定，便于手指触达）
     *
     * 圆心必须由调用方按真实容器尺寸给出：默认 233f 只是 466 方屏的一半，
     * 一旦容器尺寸变化（分屏 / 不同机型）就会静默判定错位。
     */
    fun isInSeekZone(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        val r = sqrt(dx * dx + dy * dy)
        if (r < SEEK_RADIUS_INNER || r > SEEK_RADIUS_OUTER) return false
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
        if (r < ArcSeekMath.SEEK_CANCEL_RADIUS || angle < -ArcSeekMath.SEEK_CANCEL_ANGLE || angle > ArcSeekMath.SEEK_CANCEL_ANGLE) {
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
