package com.watchreader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * WatchReader 腕上设计系统
 *
 * 动效与质感基线：
 * 1. 全部连续动画仅在 graphicsLayer / 绘制阶段读取状态，逐帧失效不引发重组，杜绝高刷滚动 GC 抖动；
 * 2. 统一入场编排（错峰上浮 + 轻缩放）与按压反馈（弹簧缩放），营造沉稳高级的腕上触感；
 * 3. 层次表达以发丝描边（hairline border）代替阴影与色块堆叠，契合 OLED 纯黑与羊皮纸双主题。
 */

/** 统一动效令牌：时长、缓动与弹簧参数 */
object WatchMotion {
    const val DUR_ENTER = 340
    const val DUR_FADE = 200
    const val DUR_FADE_OUT = 160
    const val STAGGER_STEP_MS = 36L
    const val MAX_STAGGER = 8

    val EnterEasing = FastOutSlowInEasing
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

    /** 按压回弹：快速沉稳，不过弹 */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}

/** 统一圆角体系 */
object WatchShapes {
    val Card = RoundedCornerShape(18.dp)
    val Row = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
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
 * 发丝描边卡片：surface + 1dp 低透明度轮廓描边，构成腕上卡片层次基元
 * （直绘 clip+底色+描边，不用 M3 OutlinedCard：卡片页每卡省去 Surface 阴影语义
 * 与 BorderStroke 一套机器，书架 / 菜单这类多卡页面的组合开销显著更低）
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = WatchShapes.Card,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(borderWidth, borderColor, shape),
        content = content
    )
}

/**
 * 胶囊按钮：tonal（次要）/ primary（主要）/ outline（主色描边强调）三种强调级
 * 按压缩放 + 轻触感，无水波纹叠加，保持克制纯粹
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
    val colors = MaterialTheme.colorScheme
    val isFilled = emphasis == PillEmphasis.Primary || active
    val container = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.5f)
        isFilled -> colors.primary
        emphasis == PillEmphasis.Outline -> colors.primary.copy(alpha = 0.16f)
        else -> colors.surfaceVariant
    }
    val contentColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.6f)
        isFilled -> colors.onPrimary
        emphasis == PillEmphasis.Outline -> colors.primary
        else -> colors.onSurface
    }
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    Box(
        modifier = modifier
            .pressScale(interaction)
            .clip(WatchShapes.Pill)
            .background(container)
            .then(
                if (emphasis == PillEmphasis.Outline && enabled) {
                    Modifier.border(1.dp, colors.primary.copy(alpha = 0.45f), WatchShapes.Pill)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) {
                tick()
                onClick()
            }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }
}

enum class PillEmphasis { Tonal, Primary, Outline }

/** 分区小标题：加宽字距的克星级标签 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 1.4.sp
    )
}

/** 发丝分隔线（基于原生 Divider，统一低透明度轮廓色） */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
    )
}

/**
 * 细进度轨：基于原生 LinearProgressIndicator，圆头描边与设计令牌一致；
 * 动画值经 animateFloatAsState 一次性推进（开卷/进度刷新等一次性场景）
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    fillColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 4.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(520, easing = WatchMotion.EnterEasing),
        label = "progress-track"
    )
    LinearProgressIndicator(
        progress = animated,
        modifier = modifier.fillMaxWidth().height(height),
        color = fillColor,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
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
            delay(order.coerceAtMost(WatchMotion.MAX_STAGGER) * WatchMotion.STAGGER_STEP_MS)
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

/**
 * 优雅加载指示：旋转圆弧（LinearEasing 匀速旋转，仅图层失效）+ 柔和呼吸
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 2.4.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "loading-arc")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label = "loading-rotation"
    )
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1050, easing = WatchMotion.EnterEasing), RepeatMode.Reverse),
        label = "loading-breath"
    )
    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                rotationZ = rotation
                alpha = breath
            }
    ) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 96f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = -90f + 96f + 18f,
            sweepAngle = 360f - 96f - 18f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** 格式角标（TXT / EPUB）：发丝描边小徽章 */
@Composable
fun TextBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .clip(WatchShapes.Badge)
            .border(1.dp, color.copy(alpha = 0.45f), WatchShapes.Badge)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
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
    val transition = rememberInfiniteTransition(label = "pulse-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = WatchMotion.EnterEasing), RepeatMode.Reverse),
        label = "pulse-alpha"
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(WatchShapes.Badge)
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
            .clip(WatchShapes.Badge)
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
