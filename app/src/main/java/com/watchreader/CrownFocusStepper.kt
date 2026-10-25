package com.watchreader

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

/**
 * 表冠焦点轮选步进：复刻系统设置主页对齐模式（ScaleAlign）的翻项语义。
 *
 * 背景约束（逆向自 com.android.settings RecyclerView 对齐分支，见 ADR-020）：
 * 1. 单事件轴值先做 |δ|≥80 钳 ±79——快甩也不会单事件跨多项；
 * 2. 位移按 round(axis × 系统滚动系数) 累积（对齐分支不吃 ×0.5 阻尼）；
 * 3. 累计满 120px（设置项行高）翻上/下一项并给一次齿轮微振，零头跨事件保留；
 * 4. 换向先抵消剩余量再走新方向。
 *
 * 喂入表冠原始轴值，返回本次事件应移动的焦点步数（带符号，正 = 顺时针向下一项）。
 * 钳制保证单事件至多产出一步：触觉反馈不会在快甩时连成一片。
 */
class CrownFocusStepper(
    val pxPerStep: Float = 120f,
    val scrollFactorPxPerUnit: Float = 1f
) {
    private var remainderPx = 0f

    fun feed(units: Float): Int {
        var px = round(units * scrollFactorPxPerUnit)
        if (abs(px) >= EVENT_CLAMP_PX) px = sign(px) * (EVENT_CLAMP_PX - 1f)
        remainderPx += px

        var steps = 0
        while (remainderPx >= pxPerStep) {
            remainderPx -= pxPerStep
            steps++
        }
        while (remainderPx <= -pxPerStep) {
            remainderPx += pxPerStep
            steps--
        }
        return steps
    }

    fun reset() {
        remainderPx = 0f
    }

    companion object {
        /** 单事件钳制界：|δ|≥80 收敛为 ±79，与系统对齐分支一致（79 < 120 保证单事件单步） */
        const val EVENT_CLAMP_PX = 80f

        /** 默认每步累积位移 = 设置主页行高 120px */
        const val DEFAULT_PX_PER_STEP = 120f
    }
}
