package com.watchreader

/**
 * 可交互控件与状态指示基元：胶囊按钮、进度轨、加载环、角标、状态点
 *
 * 全部只读 MaterialTheme 令牌与 WatchShapes/WatchMotion，不感知页面；
 * 容器与装饰基元（卡片、浮层进出场、分区标签、发丝分隔、错峰入场、边缘羽化）在 WatchComponents.kt。
 */

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults as WearButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.CircularProgressIndicator as WearCircularProgressIndicator
import androidx.wear.compose.material3.LinearProgressIndicator as WearLinearProgressIndicator

/** 胶囊按钮三档强调级共用的容器 / 内容色契约 */
@Composable
internal fun pillColors(
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
 * 承载件为 Wear Compose Material 3 的 CompactButton（容器高 48dp，恰为腕上最小交互尺寸），
 * 配色沿用 MD3 语义角色，形状为全圆角胶囊，按压回弹与齿轮触觉反馈保持不变。
 * 宽度完全服从父布局权重，圆屏最窄弦宽下四键并排不会被弧边裁切。
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
    val scheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = pillColors(emphasis, active, enabled)
    CompactButton(
        onClick = {
            tick()
            onClick()
        },
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = WatchShapes.Pill,
        colors = WearButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            secondaryContentColor = contentColor,
            iconColor = contentColor,
            disabledContainerColor = scheme.surfaceContainer,
            disabledContentColor = scheme.onSurfaceVariant,
            disabledSecondaryContentColor = scheme.onSurfaceVariant,
            disabledIconColor = scheme.onSurfaceVariant
        ),
        border = if (emphasis == PillEmphasis.Outline) {
            BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outlineVariant)
        } else {
            null
        },
        contentPadding = PaddingValues(
            horizontal = horizontalPadding,
            vertical = verticalPadding
        ),
        label = {
            Text(
                text = label,
                style = WearMaterialTheme.typography.labelLarge,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    )
}

/**
 * 细进度轨：Wear Compose Material 3 LinearProgressIndicator（官方实现自带轨道与圆角端点）。
 * 官方线性进度最小线宽 8dp，故此处取 8dp 作为腕上轨道厚度。
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 8.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(520, easing = WatchMotion.EnterEasing),
        label = "progress-track"
    )
    WearLinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(height),
        strokeWidth = height.coerceAtLeast(8.dp),
        colors = ProgressIndicatorDefaults.colors(
            indicatorColor = fillColor,
            trackColor = trackColor
        )
    )
}

/** Wear Compose Material 3 环形加载指示（indeterminate） */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 2.4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    WearCircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        colors = ProgressIndicatorDefaults.colors(
            indicatorColor = color,
            trackColor = trackColor
        )
    )
}

/**
 * 格式角标（TXT / EPUB）：MD3 tonal badge（容器底 + 配对内容色，无描边）。
 * Wear M3 无 Surface 组件，角标以背景色 + 文本自绘，填充走 MD3 容器角色
 * （primaryContainer / secondaryContainer 对），替代旧版「同色 10% alpha 自底」。
 */
@Composable
fun TextBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = modifier
            .clip(WatchShapes.Pill)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = WearMaterialTheme.typography.labelSmall,
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
