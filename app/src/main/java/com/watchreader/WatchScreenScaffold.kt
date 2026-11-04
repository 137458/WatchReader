package com.watchreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 圆屏安全区几何（纯函数，可脱离 Compose 验证）
 *
 * 466x466 圆屏的内接正方形边长约 329px，弧顶到内接方形顶边的余量约 68px；
 * 弧线区是视觉上「没有内容」的地带，页面顶部留白必须盖住它，否则文字会被圆周裁切。
 * 这里把「屏内约定值」与「几何下限」取大：正常手表（466px / density 2.0 → 下限 34.1dp）
 * 用既有的 44dp，不被几何值篡改；异常尺寸或低 density（预览、平板投屏）时由几何值兜底，
 * 避免内容侵入弧角。数值同时被 WatchSafeAreaTest 两组尺寸锁定。
 */
object WatchSafeArea {
    /** 页面顶部约定留白（466px 圆屏实测值） */
    const val DEFAULT_TOP_INSET_DP = 44f

    /** 页面底部约定留白（同时避让弧形操作条热区） */
    const val DEFAULT_BOTTOM_INSET_DP = 84f

    /** 页面左右约定留白 */
    const val HORIZONTAL_PADDING_DP = 24f

    private const val SQRT2 = 1.4142135f

    /** 正方形屏幕的内接正方形边长（与入参同单位） */
    fun inscribedSide(screenPx: Float): Float = screenPx / SQRT2

    /** 弧顶到内接正方形顶边的垂直余量（与入参同单位） */
    fun arcMargin(screenPx: Float): Float = (screenPx - inscribedSide(screenPx)) / 2f

    /** 单侧几何下限换算成 dp；density 非法时退化为 0，交给约定值兜底 */
    fun minInsetDp(screenPx: Float, density: Float): Float =
        if (density <= 0f) 0f else arcMargin(screenPx) / density

    /** 顶部留白：约定值与几何下限取大 */
    fun topInsetDp(
        screenPx: Float,
        density: Float,
        preferredDp: Float = DEFAULT_TOP_INSET_DP
    ): Float = max(preferredDp, minInsetDp(screenPx, density))

    /** 底部留白：约定值与几何下限取大 */
    fun bottomInsetDp(
        screenPx: Float,
        density: Float,
        preferredDp: Float = DEFAULT_BOTTOM_INSET_DP
    ): Float = max(preferredDp, minInsetDp(screenPx, density))
}

/** 解析后的安全区（dp）；屏幕直接消费它，不再各自写裸 dp */
@Immutable
data class WatchSafeInsets(
    val top: Dp,
    val bottom: Dp,
    val horizontal: Dp
)

/**
 * 读取当前屏幕的物理尺寸与 density 并解析安全区
 *
 * 用 resources.displayMetrics 而不是 Compose 的窗口宽度：安全区是屏幕硬件属性，
 * 不该随窗口内边距或分屏变化。
 */
@Composable
fun rememberWatchSafeInsets(): WatchSafeInsets {
    val metrics = LocalContext.current.resources.displayMetrics
    val screenPx = metrics.widthPixels.toFloat()
    val density = metrics.density
    return remember(screenPx, density) {
        WatchSafeInsets(
            top = WatchSafeArea.topInsetDp(screenPx, density).dp,
            bottom = WatchSafeArea.bottomInsetDp(screenPx, density).dp,
            horizontal = WatchSafeArea.HORIZONTAL_PADDING_DP.dp
        )
    }
}

/**
 * 上下羽化档位：null 表示该侧不要遮罩
 *
 * 收拢成一个参数而不是两个 Dp? 参数，是因为「遮罩要不要」是整屏决策，
 * 逐侧开关会让调用点出现 top = 48.dp, bottom = null 这类难以判读的组合。
 */
@Immutable
data class WatchScreenFades(
    val top: Dp?,
    val bottom: Dp?
) {
    companion object {
        /** 上下等宽 48dp：搜索页等单列内容 */
        val Default = WatchScreenFades(48.dp, 48.dp)

        /** 不要遮罩：内容自带底色或本身居中（菜单、传书页） */
        val None = WatchScreenFades(null, null)
    }
}

/**
 * 圆屏页面壳：右滑退出 + 上下羽化 + 底部操作条槽
 *
 * 五个页面此前各自重复同一段「Box(fillMaxSize) + swipeDismissBack + 两个 EdgeFadeMask +
 * EdgeActionBar」装配（约 12~40 行/页），差异只有遮罩高度与操作条内容。壳把这些
 * 「屏幕级 chrome」收成一处，滚动容器与内边距仍留在各页内部 —— 壳不规定布局范式，
 * 只保证所有页面在圆屏上的边缘行为一致。删除它会退回五份平行实现，
 * 任何一次行为调整（例如遮罩挡不挡操作条）都要改五遍。
 */
@Composable
fun WatchScreenScaffold(
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    onDismiss: () -> Unit,
    fades: WatchScreenFades = WatchScreenFades.Default,
    contentAlignment: Alignment = Alignment.TopStart,
    actionBar: (@Composable BoxScope.() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .swipeDismissBack(enabled = swipeEnabled, onDismiss = onDismiss),
        contentAlignment = contentAlignment
    ) {
        content()
        // 遮罩压在内容之上、操作条之下：否则主卡文字会盖住渐隐，边缘出现硬切线
        fades.top?.let {
            EdgeFadeMask(
                edge = Alignment.Top,
                modifier = Modifier.align(Alignment.TopCenter),
                height = it
            )
        }
        fades.bottom?.let {
            EdgeFadeMask(
                edge = Alignment.Bottom,
                modifier = Modifier.align(Alignment.BottomCenter),
                height = it
            )
        }
        actionBar?.invoke(this)
        // 全屏浮层必须是最后一层：否则会被遮罩与操作条压住，热区也抢不过操作条
        overlay?.invoke(this)
    }
}
