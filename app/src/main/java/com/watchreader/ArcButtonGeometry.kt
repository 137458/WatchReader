package com.watchreader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 弧形贴边按钮几何：环形扇带完全贴住圆屏边缘的纯数学。
 *
 * 背景约束：底部操作条由「直线胶囊靠边」改为「环形扇带」——
 * 1. 外缘半径 = 屏幕半径（0dp 齐边，任何像素不越过圆弧），内缘 = 外缘 − 径向厚度，
 *    两条侧边沿半径方向；文字轨道取带宽正中（textRadiusPx = (外缘+内缘)/2）；
 * 2. 槽位角度沿用既有口径：单键 90°，双键 125°/55°，三键 125°/90°/55°；
 *    每槽半跨度 = min(32°, 邻槽中心距/2 − 间隙/2)，单键固定 32°（64° 宽扇带容纳长标签）；
 * 3. 厚度吃穿半径时钳制内缘为半径一半，保证扇带恒为合法环形；
 * 4. 文字沿弧逐字排布：阅读方向沿屏幕 +x（向右），底部弧上向右行进角度递减，
 *    整段文字按累计字宽在中点角两侧对称展开（弧长 = 半径 × 角度换算）。
 *
 * 仅支持 1~3 键布局；其余数量退化为单槽（全部按钮落回 90° 正中）。
 */
object ArcButtonGeometry {
    /** 单键扇带半跨度上限：64° 宽弧带可容纳「自动滚屏 350 px/s」级长标签 */
    const val SINGLE_SLOT_HALF_SPAN_DEG = 32f

    /** 一个环形扇带按钮槽位 */
    data class Sector(
        val centerAngleDeg: Float,
        val startAngleDeg: Float,
        val endAngleDeg: Float,
        val outerRadiusPx: Float,
        val innerRadiusPx: Float,
        val textRadiusPx: Float
    )

    /** 按钮数量 → 各槽中心角（度，屏幕坐标顺时针，90° = 正下方） */
    fun slotAnglesDeg(count: Int): List<Float> = when (count) {
        2 -> listOf(125f, 55f)
        3 -> listOf(125f, 90f, 55f)
        else -> listOf(90f)
    }

    /**
     * 计算各按钮扇带。
     *
     * @param thicknessPx 径向厚度（外缘到内缘）
     * @param gapDeg 相邻扇带侧边的间隙角
     */
    fun sectors(
        buttonCount: Int,
        screenWidthPx: Float,
        screenHeightPx: Float,
        thicknessPx: Float,
        gapDeg: Float
    ): List<Sector> {
        val angles = slotAnglesDeg(buttonCount)
        if (angles.isEmpty() || screenWidthPx <= 0f || screenHeightPx <= 0f) return emptyList()

        val outerRadius = min(screenWidthPx, screenHeightPx) / 2f
        // 厚度吃穿半径时钳制：内缘不低于半径一半
        val innerRadius = max(outerRadius - thicknessPx, outerRadius / 2f)
        val textRadius = (outerRadius + innerRadius) / 2f

        val halfSpan = if (angles.size <= 1) {
            SINGLE_SLOT_HALF_SPAN_DEG
        } else {
            val sorted = angles.sorted()
            val minSpacing = (1 until sorted.size)
                .minOf { abs(sorted[it] - sorted[it - 1]) }
            min(SINGLE_SLOT_HALF_SPAN_DEG, minSpacing / 2f - gapDeg / 2f)
        }

        return angles.map { center ->
            Sector(
                centerAngleDeg = center,
                startAngleDeg = center - halfSpan,
                endAngleDeg = center + halfSpan,
                outerRadiusPx = outerRadius,
                innerRadiusPx = innerRadius,
                textRadiusPx = textRadius
            )
        }
    }

    /**
     * 弧线逐字中心角：第 i 字中心距文字串起点的累计弧长决定其角度。
     * 底部弧上阅读方向向右 = 角度递减，首字（最左）角度最大。
     *
     * @param totalAdvancePx 整段文字总宽（各字 advance 之和，含字距）
     * @param advancesPx 逐字 advance 宽度
     * @param radiusPx 文字轨道半径
     * @param centerAngleDeg 整段文字的中点角
     */
    fun charCenterAnglesDeg(
        totalAdvancePx: Float,
        advancesPx: List<Float>,
        radiusPx: Float,
        centerAngleDeg: Float
    ): List<Float> {
        if (advancesPx.isEmpty() || radiusPx <= 0f) return emptyList()
        var cursor = -totalAdvancePx / 2f
        return advancesPx.map { advance ->
            val centerOffsetPx = cursor + advance / 2f
            cursor += advance
            centerAngleDeg - Math.toDegrees((centerOffsetPx / radiusPx).toDouble()).toFloat()
        }
    }
}
