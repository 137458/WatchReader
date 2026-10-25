package com.watchreader

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * WatchReader 腕上设计系统（Material 3）
 *
 * 动效与质感基线：
 * 1. 全部连续动画仅在 graphicsLayer / 绘制阶段读取状态，逐帧失效不引发重组，杜绝高刷滚动 GC 抖动；
 * 2. 统一入场编排（错峰上浮 + 轻缩放）与按压反馈（弹簧缩放），营造沉稳高级的腕上触感；
 * 3. 层次表达以 MD3 tonal surface（surfaceContainer 系列）代替阴影与色块堆叠，
 *    契合 OLED 纯黑与浅色双档；描边一律使用 outlineVariant 角色，不再运行时叠 alpha。
 */

/** 统一动效令牌：时长、缓动与弹簧参数（对齐 M3 motion tokens + emphasized 曲线） */
object WatchMotion {
    /** M3 medium2：页面级入场 */
    const val DUR_ENTER = 300

    /** M3 short4：页面淡入 */
    const val DUR_FADE = 200

    /** M3 short3：页面淡出 */
    const val DUR_FADE_OUT = 150

    // 微内容切换（数值轮换 / Tab 填充 / 局部淡入淡出）：比页面级淡入淡出更快一档，
    // 全应用所有小面积状态切换统一走这一对时长（M3 short4 / short2），
    // 杜绝各页自定 120/180/190/200 漂移
    const val DUR_SWAP_IN = 200
    const val DUR_SWAP_OUT = 100

    const val STAGGER_STEP_MS = 36L
    const val MAX_STAGGER = 8

    /** M3 emphasized decelerate / accelerate */
    val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 按压回弹：快速沉稳，不过弹 */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}

/**
 * 统一低透明度令牌：仅用于纯装饰的微光 / 阴影，不得用于文字或结构线。
 * 结构线与分隔线已升级为 outlineVariant / outline 角色（MD3）。
 */
object WatchAlpha {
    /** 弧形寻道微光与选中描边（装饰豁免） */
    const val ACCENT_BORDER = 0.45f
}

/**
 * 统一圆角体系：卡片 / 行 / 角标映射 MD3 shape scale；Pill 为全弧胶囊（手表触控友好）
 */
object WatchShapes {
    val Card = 16.dp
    val Row = 12.dp
    val Pill = CircleShape
    val Badge = RoundedCornerShape(6.dp)
}

/**
 * 按压缩放反馈：仅在按下/抬起瞬间重组两次，缩放值于 graphicsLayer 内逐帧推进
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = WatchMotion.pressSpring(),
        label = "press-scale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 轻触感点击反馈（系统 CLOCK_TICK 表冠刻度波形，与阅读页原生 View 路径同链路同手感） */
@Composable
fun rememberTickHaptic(): () -> Unit {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) {
        { RotaryHapticManager.performScrollTick(context, view) }
    }
}

/**
 * 表冠滚动目标注册：Activity 顶层管线直接寻址页面滚动（连续跟手 + 距离门限微振），
 * 替代依赖原生焦点存活的 1dp 隐形锚点——后者在焦点迁移后会使表冠事件丢失。
 * [key] 变化时重新注册（如滚动状态对象更换）；离开组合自动注销。
 */
@Composable
fun rememberCrownScrollTarget(key: Any?, onDelta: (Float) -> Boolean) {
    DisposableEffect(key) {
        val target = CrownScrollTarget(onDelta)
        CrownScrollTargetRegistry.activate(target)
        onDispose { CrownScrollTargetRegistry.deactivate(target) }
    }
}

/**
 * 右滑退出裁决器：把「是否构成一次右滑」与「跟手进度」从 Compose 手势管线里剥出来做
 * 纯位移判定，行为由 SwipeBackArbiterTest 锁定（水平主导、累加门限、进度线性、触发即封顶）。
 */
class SwipeBackArbiter(private val thresholdPx: Float) {
    private var accumulated = 0f

    /** 0..1 归一化进度，驱动内容跟手平移与淡出；越限后保持 1 直到 reset */
    var progress: Float = 0f
        private set

    /** 竖向或等量斜向位移直接放行给滚动容器；反向位移回吐累加，杜绝画圈式手抖误触 */
    fun onDrag(dx: Float, dy: Float): Boolean {
        if (abs(dx) <= abs(dy)) return false
        accumulated += dx
        progress = (accumulated / thresholdPx).coerceIn(0f, 1f)
        if (accumulated > thresholdPx) {
            accumulated = 0f
            progress = 1f
            return true
        }
        return false
    }

    fun reset() {
        accumulated = 0f
        progress = 0f
    }
}

/**
 * 右滑退出（Wear 运动口径的本应用自持实现）：Google 对 Wear 侧滑返回的定义是
 * 「内容跟手平移 + 未越门限松手弹回 + 越门限整屏滑出」，动画属于手势本身而非事后补帧。
 * ADR-001 不引 androidx.wear.compose，故在 UiKit 内复刻同一运动语义。
 *
 * 逐帧只改 graphicsLayer（平移与 alpha），不触发重组；走 EventPass.Initial 在子节点之前
 * 取得位移，只认水平主导，竖向滚动与行内点按全程不受影响；[enabled] 为 false 时整条
 * 识别器停摆（浮层开启期由浮层自己接右滑，宿主页面让位，避免一次滑动同时关浮层又退页）。
 */
@Composable
fun Modifier.swipeDismissBack(
    enabled: Boolean = true,
    thresholdPx: Float = 60f,
    onDismiss: () -> Unit
): Modifier {
    // 视觉位移只有一个来源：拖拽期 spec 为 snap 逐帧跟手，松手后切 spring（弹回）
    // 或 tween（滑出），滑出结束由 finishedListener 回调 onDismiss。
    var dragging by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var targetPx by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(
        targetValue = targetPx,
        animationSpec = when {
            dragging -> snap()
            dismissing -> tween(WatchMotion.DUR_FADE_OUT, easing = WatchMotion.ExitEasing)
            else -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "swipe-dismiss",
        finishedListener = {
            if (dismissing) {
                dismissing = false
                targetPx = 0f
                onDismiss()
            }
        }
    )
    LaunchedEffect(enabled) {
        if (!enabled) {
            dragging = false
            dismissing = false
            targetPx = 0f
        }
    }
    return this
        .graphicsLayer {
            val layerWidth = size.width
            translationX = offset
            alpha = if (layerWidth <= 0f) 1f else 1f - 0.28f * (offset / layerWidth)
        }
        .pointerInput(enabled, thresholdPx) {
            if (!enabled) return@pointerInput
            val width = size.width.toFloat()
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val arbiter = SwipeBackArbiter(thresholdPx)
                var dragged = false
                var fired = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (!change.pressed) break
                    if (arbiter.onDrag(
                            change.position.x - change.previousPosition.x,
                            change.position.y - change.previousPosition.y
                        )) {
                        fired = true
                        change.consume()
                        break
                    }
                    if (arbiter.progress > 0f) {
                        dragged = true
                        change.consume()
                        dragging = true
                        targetPx = arbiter.progress * width
                    }
                }
                if (dragged || fired) {
                    dragging = false
                    if (fired) {
                        dismissing = true
                        targetPx = width
                    } else {
                        targetPx = 0f
                    }
                }
            }
        }
}

/**
 * MD3 卡片：默认 filled card（surfaceContainer 底、零阴影、圆角走 shape scale）。
 * 传 [borderWidth] > 0 时追加 outline 描边，供需要明确边界的浮层使用。
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = WatchShapes.Card,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (borderWidth > 0.dp) BorderStroke(borderWidth, borderColor) else null
    ) {
        content()
    }
}

/** 胶囊按钮三档强调级共用的容器 / 内容色契约 */
@Composable
private fun pillColors(
    emphasis: PillEmphasis,
    active: Boolean,
    enabled: Boolean
): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    val isFilled = emphasis == PillEmphasis.Primary || active
    val container = when {
        !enabled -> colors.surfaceContainer
        isFilled -> colors.primary
        emphasis == PillEmphasis.Outline -> Color.Transparent
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        !enabled -> colors.onSurfaceVariant
        isFilled -> colors.onPrimary
        emphasis == PillEmphasis.Outline -> colors.primary
        else -> colors.onSurface
    }
    return container to content
}

/**
 * 胶囊按钮：primary（filled）/ tonal（surfaceContainerHigh）/ outline（描边）三种强调级。
 *
 * 不直接套 `Button` / 可点击 `Surface`：两者都会施加 Material 3 的最小交互尺寸
 * （Button 内容层 58dp、Surface 48dp），而圆屏最窄弦宽下四键并排的可用宽度只有约 185dp，
 * 必然溢出并被圆屏弧边裁切。本实现保留 M3 色彩角色、胶囊 shape、按压回弹与触觉反馈，
 * 最小触控高度 48dp（Google 无障碍基线），宽度完全服从父布局权重。
 */
@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasis: PillEmphasis = PillEmphasis.Tonal,
    active: Boolean = false,
    verticalPadding: Dp = 10.dp,
    horizontalPadding: Dp = 14.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tick = rememberTickHaptic()
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val (containerTarget, contentTarget) = pillColors(emphasis, active, enabled)
    // 强调级 / 选中 / 禁用切换时容器与内容色按 swap 档时长平滑过渡，避免状态直切闪变
    val containerColor by animateColorAsState(
        targetValue = containerTarget,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "pillContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = contentTarget,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "pillContent"
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(WatchShapes.Pill)
            .background(containerColor)
            .then(
                if (emphasis == PillEmphasis.Outline) {
                    Modifier.border(
                        1.dp,
                        if (enabled) colors.outline else colors.outlineVariant,
                        WatchShapes.Pill
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button
            ) {
                tick()
                onClick()
            }
            .padding(PaddingValues(horizontal = horizontalPadding, vertical = verticalPadding)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 底部弧形贴边按钮规格：一条环形扇带的标签、外观与点击回调。
 * [focused] 供表冠焦点轮选高亮，不参与点击态。
 */
class EdgeButtonSpec(
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
        val pressScales = buildList {
            buttons.forEachIndexed { index, spec ->
                val pressed by interactions[index].collectIsPressedAsState()
                add(
                    animateFloatAsState(
                        targetValue = if (pressed && spec.enabled) 0.94f else 1f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
                        label = "edgePress" + index
                    ).value
                )
            }
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
                scale(pressScales[index], pivot = trackPivot) {
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

enum class PillEmphasis { Tonal, Primary, Outline }

/**
 * 浮层进出场统一档位：scale + fade，时长复用 WatchMotion 的微内容档
 * （200ms 进 / 150ms 出），全部条件浮层共用，杜绝各页自定 scale 与时长漂移
 */
object WatchOverlay {
    val Enter: EnterTransition = fadeIn(tween(WatchMotion.DUR_SWAP_IN)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(WatchMotion.DUR_SWAP_IN))

    val Exit: ExitTransition = fadeOut(tween(WatchMotion.DUR_FADE_OUT)) +
        scaleOut(targetScale = 0.92f, animationSpec = tween(WatchMotion.DUR_FADE_OUT))
}

/** 分区小标题：MD3 labelMedium，加宽字距的克星级标签 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        letterSpacing = 1.4.sp
    )
}

/** 发丝分隔线（outlineVariant 预混弱档角色，双主题免调） */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 细进度轨：MD3 LinearProgressIndicator（圆头轨、无轨道间隙）；
 * 动画值经 animateFloatAsState 一次性推进（开卷/进度刷新等一次性场景）
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 4.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(520, easing = WatchMotion.EnterEasing),
        label = "progress-track"
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(height),
        color = fillColor,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

/**
 * 错峰入场：alpha + 上浮，动画值全程在 graphicsLayer 块内读取（仅图层失效）
 *
 * 帧率取舍（大卡片页面的入场动效）：
 * 1. 不做缩放 —— 逐帧改变缩放矩阵会让整块卡片的字形光栅化缓存全部失效并重光栅化，
 *    文字越多的卡片代价越大（菜单 / 书架的主卡片正是文字最密处）；
 * 2. alpha 采用 Modulate 合成策略 —— 直接调制各绘制指令的透明度，不再为每张卡片
 *    开辟并逐帧重绘整块离屏缓冲（自动策略下 alpha<1 的子树必须整组离屏合成）。
 *    卡片底色均为不透明色块，调制结果与整组合成在视觉上一致。
 */
@Composable
fun Modifier.staggeredEnter(order: Int): Modifier {
    // 不以 order 为 remember 键：书架增删书籍会使行序漂移，键控复位会让全部位移行
    // 整体重放入场动画（一次性全屏动画突发 + 无谓掉帧）；单例持有使动画仅在首次组合执行
    val progress = remember { Animatable(0f) }
    LaunchedEffect(order) {
        if (progress.value < 1f) {
            delay(max(0, order.coerceAtMost(WatchMotion.MAX_STAGGER)) * WatchMotion.STAGGER_STEP_MS)
            progress.animateTo(1f, tween(WatchMotion.DUR_ENTER, easing = WatchMotion.EnterEasing))
        }
    }
    return graphicsLayer {
        val v = progress.value
        alpha = v
        translationY = (1f - v) * 14.dp.toPx()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** MD3 环形加载指示（indeterminate） */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 2.4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = strokeWidth,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

/**
 * 格式角标（TXT / EPUB）：MD3 tonal badge（容器底 + 配对内容色，无描边）。
 * 填充走 MD3 容器角色（primaryContainer / secondaryContainer 对），
 * 替代旧版「同色 10% alpha 自底」——亮主题下自衬对不达标且产生影子令牌。
 */
@Composable
fun TextBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        modifier = modifier,
        shape = WatchShapes.Badge,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            letterSpacing = 0.6.sp
        )
    }
}

/** 呼吸状态点：服务就绪等活态指示（6dp 点级开销，仅图层失效） */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(1200, easing = WatchMotion.EnterEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

/** 静态状态点 */
@Composable
fun StaticDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 上下羽化渐隐遮罩：滚动内容在圆屏表盘弧线区平滑淡出的统一基元
 * （书架 / 菜单 / 目录 / 阅读页曾各自内联同一段 verticalGradient，统一收敛于此）
 *
 * @param edge 遮罩贴附的屏幕边缘（Top 向下渐隐 / Bottom 向上渐隐）
 */
@Composable
fun EdgeFadeMask(
    edge: Alignment.Vertical,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.background
) {
    val brush = remember(color, edge) {
        if (edge == Alignment.Top) {
            Brush.verticalGradient(
                0f to color,
                0.75f to color.copy(alpha = 0.9f),
                1f to Color.Transparent
            )
        } else {
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.7f to color.copy(alpha = 0.9f),
                1f to color
            )
        }
    }
    Box(modifier = modifier.fillMaxWidth().height(height).background(brush))
}
