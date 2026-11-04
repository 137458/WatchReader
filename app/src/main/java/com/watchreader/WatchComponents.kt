package com.watchreader

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 容器与装饰基元：卡片、浮层进出场、分区标签、发丝分隔、错峰入场、边缘羽化
 *
 * 组件只读 MaterialTheme 令牌，不感知具体页面；圆屏页面级布局约束在 WatchScreenScaffold.kt。
 * 可交互控件与状态指示（胶囊按钮、进度轨、加载环、角标、状态点）在 WatchControls.kt。
 */

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
