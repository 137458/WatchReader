package com.watchreader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 贴边弧形操作条（ADR-020）：径向 48dp 热区，1–3 键按 140°/90°/40° 分布
 *
 * 文字沿弧逐字排布与扇形命中判定共用同一套极坐标换算，几何契约一改则视觉与热区同时偏移，
 * 因此与 WatchComponents.kt 的通用组件分开，保证这两件事永远同步评审。
 */

enum class PillEmphasis { Tonal, Primary, Outline }

/**
 * 底部弧形贴边按钮规格：一条环形扇带的标签、外观与点击回调。
 * [focused] 供表冠焦点轮选高亮，不参与点击态。
 */
data class EdgeButtonSpec(
    val label: String,
    val emphasis: PillEmphasis = PillEmphasis.Tonal,
    val active: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * 底部弧形胶囊操作条：每个按钮是一条两端圆头的弧形胶囊——中线沿圆屏同心弧，
 * 外缘悬离屏幕圆周 [edgeInset]，内缘 = 外缘 − [thickness]，端头为标准半圆帽
 * （真胶囊），整体悬浮在下缘、处处跟随圆屏弧度。文字沿 [ArcButtonGeometry]
 * 的文字轨道逐字排布并随弧线旋转。配色复用 [pillColors]；[focused] 键额外
 * 描 primary 细边。透明热区按径向 48dp 深、弧向不小于 48dp 宽独立放置，
 * 不限制视觉胶囊大小。
 *
 * [gapDeg] 缺省 −1 表示按键数自动：两键 24°、三键及以上 16°（端帽外凸后
 * 中缝仍明显分开，呈独立胶囊），其余键数 6°。
 */
@Composable
fun EdgeActionBar(
    vararg buttons: EdgeButtonSpec,
    modifier: Modifier = Modifier,
    thickness: Dp = 20.dp,
    gapDeg: Float = -1f,
    edgeInset: Dp = 6.dp
) {
    if (buttons.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints
        val thicknessPx = with(density) { thickness.toPx() }
        val edgeInsetPx = with(density) { edgeInset.toPx() }
        val charGapPx = with(density) { 1.dp.toPx() }
        val hitDepthPx = with(density) { 48.dp.toPx() }
        // 端帽半径 = 厚度/2，帽沿弧向外凸 asin(帽半径/中线半径)；两键/三键需大间隙角才呈独立胶囊
        val effectiveGapDeg = when {
            gapDeg >= 0f -> gapDeg
            buttons.size == 2 -> 24f
            buttons.size >= 3 -> 16f
            else -> 6f
        }
        val sectors = remember(widthPx.roundToInt(), heightPx.roundToInt(), thicknessPx.roundToInt(), effectiveGapDeg, edgeInsetPx.roundToInt(), buttons.size) {
            ArcButtonGeometry.sectors(buttons.size, widthPx, heightPx, thicknessPx, effectiveGapDeg, edgeInsetPx)
        }
        val colorScheme = MaterialTheme.colorScheme
        val textStyle = MaterialTheme.typography.labelMedium
        val textMeasurer = rememberTextMeasurer()
        val tick = rememberTickHaptic()
        val interactions = remember(buttons.size) { Array(buttons.size) { MutableInteractionSource() } }
        val labelsKey = buttons.joinToString("\u0000") { it.label }
        // 逐字排版：每字测量一次，沿文字轨道按 advance 推进得到各字中心角
        val runs = remember(sectors, labelsKey, textMeasurer, charGapPx.roundToInt()) {
            sectors.mapIndexed { index, sector ->
                val label = buttons[index].label
                val layouts = label.map { char -> textMeasurer.measure(AnnotatedString(char.toString()), textStyle) }
                val advances = layouts.map { it.size.width.toFloat() + charGapPx }
                val totalAdvance = advances.sum() - charGapPx
                val angles = ArcButtonGeometry.charCenterAnglesDeg(totalAdvance, advances, sector.textRadiusPx, sector.centerAngleDeg)
                ArcCharRun(layouts, angles)
            }
        }
        // 按压缩放只持有 State，逐帧读值下沉到下面的 Canvas 绘制阶段：
        // 组合期读 .value 会让这条全屏 Canvas（逐键弧线 + 逐字 drawText）每帧重组
        val pressScales = buttons.mapIndexed { index, spec ->
            val pressed by interactions[index].collectIsPressedAsState()
            animateFloatAsState(
                targetValue = if (pressed && spec.enabled) 0.94f else 1f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
                label = "edgePress" + index
            )
        }
        val screenCenter = Offset(widthPx / 2f, heightPx / 2f)
        // 扇带配色需读 MaterialTheme（@Composable），在绘制外统一解析
        val colorPairs = buttons.map { spec -> pillColors(spec.emphasis, spec.active, spec.enabled) }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            sectors.forEachIndexed { index, sector ->
                val spec = buttons[index]
                val (containerColor, contentColor) = colorPairs[index]
                val sweep = sector.endAngleDeg - sector.startAngleDeg
                val circleCenter = Offset(cx, cy)
                // 弧形胶囊：中线沿同心弧，两端各接一个半圆帽（帽心在中线端点、半径 = 厚度/2）
                val capRadius = (sector.outerRadiusPx - sector.innerRadiusPx) / 2f
                val midRadius = (sector.outerRadiusPx + sector.innerRadiusPx) / 2f
                val path = Path().apply {
                    // 外弧 start → end
                    arcTo(
                        rect = Rect(center = circleCenter, radius = sector.outerRadiusPx),
                        startAngleDegrees = sector.startAngleDeg,
                        sweepAngleDegrees = sweep,
                        forceMoveTo = true
                    )
                    // 右端帽：从外弧切点经远离按钮主体一侧转到内弧切点
                    arcTo(
                        rect = Rect(center = polarOffset(circleCenter, sector.endAngleDeg, midRadius), radius = capRadius),
                        startAngleDegrees = sector.endAngleDeg,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    // 内弧 end → start
                    arcTo(
                        rect = Rect(center = circleCenter, radius = sector.innerRadiusPx),
                        startAngleDegrees = sector.endAngleDeg,
                        sweepAngleDegrees = -sweep,
                        forceMoveTo = false
                    )
                    // 左端帽：从内弧切点经远离按钮主体一侧转回外弧切点
                    arcTo(
                        rect = Rect(center = polarOffset(circleCenter, sector.startAngleDeg, midRadius), radius = capRadius),
                        startAngleDegrees = sector.startAngleDeg + 180f,
                        sweepAngleDegrees = 180f,
                        forceMoveTo = false
                    )
                    close()
                }
                val trackPivot = polarOffset(Offset(cx, cy), sector.centerAngleDeg, sector.textRadiusPx)
                scale(pressScales[index].value, pivot = trackPivot) {
                    drawPath(path, containerColor)
                    if (spec.emphasis == PillEmphasis.Outline) {
                        drawPath(
                            path,
                            if (spec.enabled) colorScheme.outline else colorScheme.outlineVariant,
                            style = Stroke(1.dp.toPx())
                        )
                    }
                    if (spec.focused && spec.enabled) {
                        drawPath(path, colorScheme.primary, style = Stroke(1.dp.toPx()))
                    }
                    val run = runs[index]
                    run.layouts.forEachIndexed { charIndex, layout ->
                        val angle = run.angles[charIndex]
                        val pivot = polarOffset(Offset(cx, cy), angle, sector.textRadiusPx)
                        rotate(degrees = angle - 90f, pivot = pivot) {
                            drawText(
                                textLayoutResult = layout,
                                color = contentColor,
                                topLeft = Offset(
                                    pivot.x - layout.size.width / 2f,
                                    pivot.y - layout.size.height / 2f
                                )
                            )
                        }
                    }
                }
            }
        }
        sectors.forEachIndexed { index, sector ->
            val spec = buttons[index]
            val hitMidRadius = sector.outerRadiusPx - hitDepthPx / 2f
            val halfSpan = Math.toRadians(((sector.endAngleDeg - sector.startAngleDeg) / 2f).toDouble())
            val arcWidthPx = 2f * hitMidRadius * sin(halfSpan).toFloat()
            val hitWidthPx = max(arcWidthPx, hitDepthPx)
            val hitCenter = polarOffset(screenCenter, sector.centerAngleDeg, hitMidRadius)
            // 热区 = 弦长矩形的轴对齐外接框（不旋转）；是否命中以扇带极坐标判定为准。
            // 外接框只负责接住触摸：扇带外的点击静默丢弃，既不误触按钮也不透传底层列表
            // （此前旋转矩形热区与 clipToPadding=false 滚入底部的列表行重叠，会吞行触发返回）
            val theta = (sector.centerAngleDeg - 90f) * (PI / 180f).toFloat()
            val cosT = abs(cos(theta))
            val sinT = abs(sin(theta))
            val hitHalfW = hitWidthPx / 2f
            val hitHalfH = hitDepthPx / 2f
            val bboxHalfW = hitHalfW * cosT + hitHalfH * sinT
            val bboxHalfH = hitHalfW * sinT + hitHalfH * cosT
            val downPos = remember { mutableStateOf(Offset.Zero) }
            val hasDown = remember { mutableStateOf(false) }
            fun inSector(): Boolean = ArcButtonGeometry.hitTest(
                x = hitCenter.x - bboxHalfW + downPos.value.x,
                y = hitCenter.y - bboxHalfH + downPos.value.y,
                centerX = screenCenter.x,
                centerY = screenCenter.y,
                startAngleDeg = sector.startAngleDeg,
                endAngleDeg = sector.endAngleDeg,
                outerRadiusPx = sector.outerRadiusPx,
                hitDepthPx = hitDepthPx
            )
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (hitCenter.x - bboxHalfW).roundToInt(),
                            (hitCenter.y - bboxHalfH).roundToInt()
                        )
                    }
                    .size(with(density) { (2 * bboxHalfW).toDp() }, with(density) { (2 * bboxHalfH).toDp() })
                    .pointerInput(sector) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            downPos.value = down.position
                            hasDown.value = true
                        }
                    }
                    .semantics { contentDescription = spec.label }
                    .clickable(
                        interactionSource = interactions[index],
                        indication = null,
                        enabled = spec.enabled,
                        role = Role.Button
                    ) {
                        // 无触摸 DOWN 的语义点击（表冠确认、无障碍激活）始终放行；
                        // 触摸点击仅在扇带内触发
                        val tracked = hasDown.value
                        hasDown.value = false
                        if (!tracked || inSector()) {
                            tick()
                            spec.onClick()
                        }
                    }
            )
        }
    }
}

private data class ArcCharRun(
    val layouts: List<TextLayoutResult>,
    val angles: List<Float>
)

private fun polarOffset(center: Offset, angleDeg: Float, radius: Float): Offset {
    val rad = Math.toRadians(angleDeg.toDouble())
    return Offset(
        center.x + radius * cos(rad).toFloat(),
        center.y + radius * sin(rad).toFloat()
    )
}
