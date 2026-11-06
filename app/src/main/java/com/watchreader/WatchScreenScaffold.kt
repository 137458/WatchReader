package com.watchreader

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.wear.compose.material3.AppScaffold
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

    /** 阅读页顶部约定留白：比页面级 44dp 多让 8dp，给弧形章节名留出气口 */
    const val READER_TOP_INSET_DP = 52f

    /** 阅读页底部约定留白：正文末行不贴弧形进度字，也不需要避让操作条 */
    const val READER_BOTTOM_INSET_DP = 56f

    private const val SQRT2 = 1.4142135f

    /** 正方形屏幕的内接正方形边长（与入参同单位） */
    fun inscribedSide(screenPx: Float): Float = screenPx / SQRT2

    /** 弧顶到内接正方形顶边的垂直余量（与入参同单位） */
    fun arcMargin(screenPx: Float): Float = (screenPx - inscribedSide(screenPx)) / 2f

    /** 单侧几何下限换算成 dp；density 非法时退化为 0，交给约定值兜底 */
    fun minInsetDp(screenPx: Float, density: Float): Float =
        if (density <= 0f) 0f else arcMargin(screenPx) / density

    /** 顶部留白：约定值、几何下限与系统安全区三者取大 */
    fun topInsetDp(
        screenPx: Float,
        density: Float,
        preferredDp: Float = DEFAULT_TOP_INSET_DP,
        systemInsetDp: Float = 0f
    ): Float = contentInsetDp(preferredDp, minInsetDp(screenPx, density), systemInsetDp)

    /** 底部留白：约定值、几何下限与系统安全区三者取大 */
    fun bottomInsetDp(
        screenPx: Float,
        density: Float,
        preferredDp: Float = DEFAULT_BOTTOM_INSET_DP,
        systemInsetDp: Float = 0f
    ): Float = contentInsetDp(preferredDp, minInsetDp(screenPx, density), systemInsetDp)

    /** 左右留白：固定约定值与系统安全区取大 */
    fun horizontalInsetDp(systemInsetsDp: Float): Float =
        max(HORIZONTAL_PADDING_DP, systemInsetsDp)

    /**
     * 单个方向的内容留白
     *
     * 三等项取大：项目约定值（圆屏实测留白）、几何下限（任意圆屏尺寸兜底）、
     * 系统安全区（刘海 / 状态栏 / 导航条）。为什么不是直接 `windowInsetsPadding`：
     * 圆屏的弧顶留白是「视觉几何」而不是系统 inset，两者必须同时满足，
     * 只用系统值会让 466px 圆屏内容侵入弧角，只用约定值会在方屏手机上压到状态栏。
     */
    fun contentInsetDp(preferredDp: Float, geometricMinDp: Float, systemInsetDp: Float): Float =
        max(max(preferredDp, geometricMinDp), systemInsetDp)
}

/** 解析后的安全区（dp）；屏幕直接消费它，不再各自写裸 dp */
@Immutable
data class WatchSafeInsets(
    val top: Dp,
    val bottom: Dp,
    val horizontal: Dp
)

/** 系统安全区像素快照；作为 remember 键值参与相等比较 */
@Immutable
data class SystemInsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val Zero = SystemInsetsPx(0, 0, 0, 0)
    }
}

/**
 * 读取当前屏幕的物理尺寸、density 与系统安全区并解析内容留白
 *
 * 圆屏几何（弧顶攻击区）与系统安全区（刘海 / 状态栏 / 导航条）是两件独立的事：
 * 前者由屏幕物理尺寸决定，后者由系统窗口 inset 决定。这里两者取大，
 * 方形手机上不会被状态栏压住，圆屏手表上也不会因为系统 inset 为 0 就丢掉弧顶留白。
 * 屏幕尺寸仍取 resources.displayMetrics（硬件属性，不随窗口内边距变化）。
 *
 * 系统 inset 必须经窗口回调写入状态：组合期直接读一次 `rootWindowInsets`/`safeDrawing`
 * 只在首次组合时成立，之后导航条出现或刘海变化不会触发重组，值要等下一次无关重组才更新。
 * 监听器挂在窗口根视图上（不是 Compose 自己的 AndroidComposeView）并把 inset 原样交还，
 * ViewGroup 的派发链继续下发到子视图，Compose 自身的 WindowInsets 读取不受影响。
 */
@Composable
fun rememberWatchSafeInsets(
    preferredTopDp: Float = WatchSafeArea.DEFAULT_TOP_INSET_DP,
    preferredBottomDp: Float = WatchSafeArea.DEFAULT_BOTTOM_INSET_DP
): WatchSafeInsets {
    val metrics = LocalContext.current.resources.displayMetrics
    val screenPx = metrics.widthPixels.toFloat()
    val density = metrics.density
    val composeDensity = LocalDensity.current
    val view = LocalView.current
    var systemInsets by remember { mutableStateOf(SystemInsetsPx.Zero) }

    DisposableEffect(view) {
        val host = view.rootView
        // 未挂载时 rootView 就是自身：不接管它的 inset 派发，退化为首帧零值由几何下限兜底
        if (host === view) return@DisposableEffect onDispose { }

        fun readInsets(insets: android.view.WindowInsets): SystemInsetsPx {
            // 与 Compose `WindowInsets.safeDrawing` 同口径：系统栏 + 刘海/挖孔
            val types = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            val safe = WindowInsetsCompat.toWindowInsetsCompat(insets, view).getInsets(types)
            return SystemInsetsPx(safe.left, safe.top, safe.right, safe.bottom)
        }

        view.rootWindowInsets?.let { systemInsets = readInsets(it) }
        val listener = View.OnApplyWindowInsetsListener { v, insets ->
            systemInsets = readInsets(insets)
            // 原样交还：不消费 inset，根 ViewGroup 会继续把同一次派发下发给子视图
            v.onApplyWindowInsets(insets)
        }
        host.setOnApplyWindowInsetsListener(listener)
        host.requestApplyInsets()
        onDispose {
            host.setOnApplyWindowInsetsListener(null)
            host.requestApplyInsets()
        }
    }

    val systemDensity = if (composeDensity.density <= 0f) 1f else composeDensity.density
    return remember(
        screenPx, density, systemInsets, systemDensity, preferredTopDp, preferredBottomDp
    ) {
        WatchSafeInsets(
            top = WatchSafeArea.topInsetDp(
                screenPx, density, preferredTopDp, systemInsets.top / systemDensity
            ).dp,
            bottom = WatchSafeArea.bottomInsetDp(
                screenPx, density, preferredBottomDp, systemInsets.bottom / systemDensity
            ).dp,
            horizontal = WatchSafeArea.horizontalInsetDp(
                max(systemInsets.left, systemInsets.right) / systemDensity
            ).dp
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
 * 全屏阻塞浮层是否在显示。页面壳据此禁用右滑返回；浮层自身不在 provide 范围内，
 * 因此它的右滑仍按"取消浮层"语义工作。
 */
val LocalBlockingOverlay = staticCompositionLocalOf { false }

/**
 * 圆屏页面壳：右滑退出 + 上下羽化 + 底部操作条槽 + 官方 TimeText
 *
 * 五个页面此前各自重复同一段「Box(fillMaxSize) + swipeDismissBack + 两个 EdgeFadeMask +
 * EdgeActionBar」装配（约 12~40 行/页），差异只有遮罩高度与操作条内容。壳把这些
 * 「屏幕级 chrome」收成一处，滚动容器与内边距仍留在各页内部 —— 壳不规定布局范式，
 * 只保证所有页面在圆屏上的边缘行为一致。删除它会退回五份平行实现，
 * 任何一次行为调整（例如遮罩挡不挡操作条）都要改五遍。
 *
 * 顶栏时钟由官方 [AppScaffold] 的 TimeText 槽位绘制：内边距、字体与圆屏弧口对齐
 * 都交给 Wear M3 默认实现，页面只保留内容安全区（[WatchSafeArea]）。
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
    val blockedByOverlay = LocalBlockingOverlay.current
    AppScaffold(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .swipeDismissBack(
                    enabled = swipeEnabled && !blockedByOverlay,
                    onDismiss = onDismiss
                ),
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
}
