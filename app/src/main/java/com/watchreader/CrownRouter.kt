package com.watchreader

import kotlin.math.abs

/**
 * 一次表冠旋转的归一化结果（顺时针向下为正、逆时针向上为负，口径见 CrownScrollHelper）
 *
 * 路由层只吃这个值而不是 MotionEvent：分派顺序与消费语义都是纯逻辑，
 * 脱离 Android 框架即可在 JVM 单测里锁定；需要真实事件的下发动作走 [CrownNativeDispatch] 接缝。
 */
data class CrownTurn(val delta: Float) {

    /** 是否达到页面级消费门限：低于此值的抖动不足以驱动调速或页面滚动，只继续下发给原生 View 树 */
    val isSignificant: Boolean get() = abs(delta) > SIGNIFICANT_EPSILON

    companion object {
        /** 有效增量门限（轴值单位）：对齐旧实现 `abs(delta) > 0.001f` 的判定 */
        const val SIGNIFICANT_EPSILON = 0.001f
    }
}

/**
 * 原生下发接缝：把「事件交给 View 树」这类必须持真实 MotionEvent 的动作从路由策略里剥出来
 *
 * 三条原生策略（默认链 / 焦点视图 / 兜底遍历）只表达「在第几位尝试」，
 * 具体怎么找视图由实现方决定；单测注入假实现即可验证顺序，无需构造 MotionEvent。
 */
interface CrownNativeDispatch {

    /** Activity 默认链（super.dispatchGenericMotionEvent） */
    fun dispatchDefault(): Boolean

    /** 当前焦点视图 */
    fun dispatchToFocused(): Boolean

    /** 兜底：向视图树中第一个可见的 ScrollView / ListView 直接下发 */
    fun dispatchToActiveScrollableView(): Boolean

    companion object {
        /** 无原生环境（单测 / 预览）：所有下发均未消费 */
        val NONE: CrownNativeDispatch = object : CrownNativeDispatch {
            override fun dispatchDefault(): Boolean = false
            override fun dispatchToFocused(): Boolean = false
            override fun dispatchToActiveScrollableView(): Boolean = false
        }
    }
}

/**
 * 一条表冠路由策略
 *
 * @return true 表示本次事件已消费，路由器不再尝试后续策略
 */
fun interface CrownRoute {
    fun onRotary(turn: CrownTurn, native: CrownNativeDispatch): Boolean
}

/**
 * 表冠路由器：Activity 顶层唯一的表冠分派点
 *
 * 收口前，一次表冠事件由谁处理取决于散落在 Activity / 阅读页 / 注册表 / 修饰符里的隐式优先级：
 * 顶层先试 ViewModel 的调速、再试页面注册目标、再交给原生链、再试焦点视图、最后递归遍历视图树兜底，
 * 没有任何一处能回答「这次事件给了谁、为什么」。现在顺序只在本类构造参数里定义一次，
 * 每条策略可单独替换、单独测试。
 *
 * 无状态、主线程调用（与 dispatchGenericMotionEvent 同线程），可安全复用于单实例。
 */
class CrownRouter(private val routes: List<CrownRoute>) {

    /** 按声明顺序尝试，首个消费即终止 */
    fun onRotary(turn: CrownTurn, native: CrownNativeDispatch = CrownNativeDispatch.NONE): Boolean {
        for (route in routes) {
            if (route.onRotary(turn, native)) return true
        }
        return false
    }
}

/**
 * 策略工厂与生产分派顺序
 *
 * 顺序与收口前一致，逐条对应一条旧路径：
 * 1. RSVP 调速（ReaderViewModel.handleRotaryScroll，仅速读页消费）
 * 2. 页面级滚动目标（Compose 页面经 CrownScrollTargetRegistry 注册，原是全局单槽的隐式优先级）
 * 3. 原生默认链（阅读页 ScrollView / 目录 ListView 的自持监听从这里被触达）
 * 4. 焦点视图（Compose 悬浮组件夺焦后的补位）
 * 5. 活跃滚动容器兜底（焦点被夺且原生链未命中时直接寻址）
 */
object CrownRoutes {

    fun ordered(
        onRsvpRotary: (Float) -> Boolean,
        pageTarget: () -> CrownScrollTarget?
    ): List<CrownRoute> = listOf(
        CrownRoutes.rsvpSpeed(onRsvpRotary),
        // 显式限定：参数名与工厂同名，裸调用会被解析成「调用参数」
        CrownRoutes.pageTarget(pageTarget),
        nativeDefault(),
        focusedView(),
        activeScrollableView()
    )

    /** RSVP 调速：速读页在调速即视为消费，未调速（门限内抖动 / 非速读页）交给后续策略 */
    fun rsvpSpeed(onRsvpRotary: (Float) -> Boolean): CrownRoute = CrownRoute { turn, _ ->
        turn.isSignificant && onRsvpRotary(turn.delta)
    }

    /**
     * 页面级滚动目标
     *
     * 注册槽位以 lambda 注入而不直接读全局对象：生产传 `{ CrownScrollTargetRegistry.active }`，
     * 单测传假目标即可验证「已注册目标优先于原生树」与「目标触边不消费时继续下沉」。
     */
    fun pageTarget(target: () -> CrownScrollTarget?): CrownRoute = CrownRoute { turn, _ ->
        turn.isSignificant && target()?.onCrownDelta(turn.delta) == true
    }

    fun nativeDefault(): CrownRoute = CrownRoute { _, native -> native.dispatchDefault() }

    fun focusedView(): CrownRoute = CrownRoute { _, native -> native.dispatchToFocused() }

    fun activeScrollableView(): CrownRoute = CrownRoute { _, native -> native.dispatchToActiveScrollableView() }
}
