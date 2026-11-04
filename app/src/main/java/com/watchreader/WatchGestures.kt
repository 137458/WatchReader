package com.watchreader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs

/**
 * 手势与触感：按压缩放、表冠目标注册、右滑返回
 *
 * 右滑返回的判定收敛在 [SwipeBackArbiter] 这个纯状态机里，Modifier 只采集事件、映射位移；
 * 这样「拖多远算返回」可以脱离 Compose 单独验证，页面也不必各自实现同一套阈值。
 */

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
 * ADR-001 不引 androidx.wear.compose，故在 WatchGestures 内自持复刻同一运动语义。
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
