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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    // 微内容切换（数值轮换 / Tab 填充 / 局部淡入淡出）：比页面级淡入淡出更快一档，
    // 全应用所有小面积状态切换统一走这一对时长，杜绝各页自定 120/180/190/200 漂移
    const val DUR_SWAP_IN = 180
    const val DUR_SWAP_OUT = 120

    const val STAGGER_STEP_MS = 36L
    const val MAX_STAGGER = 8

    val EnterEasing = FastOutSlowInEasing
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

    /** 按压回弹：快速沉稳，不过弹 */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}

/**
 * 统一低透明度令牌：强调描边 / 轻量衬底在三类主题下的共享 alpha。
 * 分隔线与进度轨已升级为 outlineVariant 预混弱档角色（不再运行时叠透明度）。
 */
object WatchAlpha {
    /** 卡片发丝描边 */
    const val HAIRLINE = 0.16f

    /** 主色强调描边（outline 胶囊 / 角标徽章） */
    const val ACCENT_BORDER = 0.45f

    /** 轻量衬底（地址胶囊 / 步进器底） */
    const val SUBTLE_SCRIM = 0.55f
}

/**
 * 统一圆角体系：Card / Row / Badge 为超椭圆平滑圆角半径（squircleClip/squircleBorder，
 * API 33 以下自动降级 RoundedCornerShape）；Pill 为全弧 stadium 胶囊——半径即半高，
 * 超椭圆连续曲率在整条边均为圆弧的形态上无增益，保留百分比圆角（记录在案的豁免）
 */
object WatchShapes {
    val Card = 18.dp
    val Row = 14.dp
    val Pill = RoundedCornerShape(50)
    val Badge = 6.dp
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
 * 表冠滚动目标注册：Activity 顶层管线直接寻址页面滚动（正向线性步进 + 齿轮微振），
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
 * 发丝描边卡片：surface + 1dp 低透明度轮廓描边，构成腕上卡片层次基元
 * （直绘 squircleSurface+squircleBorder：填充与裁切共用同一超椭圆轮廓，
 * 配套描边严格走 squircleBorder，杜绝圆角抗锯齿破缝）
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = WatchShapes.Card,
    containerColor: Color = MiuixTheme.colorScheme.surface,
    borderColor: Color = MiuixTheme.colorScheme.dividerLine,
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .squircleSurface(containerColor, cornerRadius)
            .squircleBorder(borderWidth, borderColor, cornerRadius),
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
    val colors = MiuixTheme.colorScheme
    val isFilled = emphasis == PillEmphasis.Primary || active
    val container = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.5f)
        isFilled -> colors.primary
        emphasis == PillEmphasis.Outline -> colors.primary.copy(alpha = 0.16f)
        else -> colors.surfaceVariant
    }
    val contentColor = when {
        !enabled -> colors.onSurfaceVariantSummary.copy(alpha = 0.6f)
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
                    Modifier.border(1.dp, colors.primary.copy(alpha = WatchAlpha.ACCENT_BORDER), WatchShapes.Pill)
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
            style = MiuixTheme.textStyles.button,
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
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary
) {
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        letterSpacing = 1.4.sp
    )
}

/** 发丝分隔线（miuix HorizontalDivider，dividerLine 预混弱档，三主题免调） */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MiuixTheme.colorScheme.dividerLine
    )
}

/**
 * 细进度轨：miuix LinearProgressIndicator（圆头圆角轨，primary/弱档预混色）；
 * 动画值经 animateFloatAsState 一次性推进（开卷/进度刷新等一次性场景）
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MiuixTheme.colorScheme.dividerLine,
    fillColor: Color = MiuixTheme.colorScheme.primary,
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
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = fillColor,
            backgroundColor = trackColor
        ),
        height = height
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
    color: Color = MiuixTheme.colorScheme.primary
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
    color: Color = MiuixTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .squircleClip(WatchShapes.Badge)
            .squircleBorder(1.dp, color.copy(alpha = WatchAlpha.ACCENT_BORDER), WatchShapes.Badge)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            letterSpacing = 0.6.sp
        )
    }
}

/** 呼吸状态点：服务就绪等活态指示（6dp 点级开销，仅图层失效） */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary
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
            .squircleClip(WatchShapes.Badge)
            .background(color)
    )
}

/** 静态状态点 */
@Composable
fun StaticDot(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(6.dp)
            .squircleClip(WatchShapes.Badge)
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
    color: Color = MiuixTheme.colorScheme.background
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
