package com.watchreader

import android.view.MotionEvent
import android.view.ViewGroup

/**
 * 表冠事件入口：Activity.dispatchGenericMotionEvent 的全部分派语义
 *
 * 表冠分支交给 [CrownRouter] 一次分派，非表冠分支保持「原生默认链 → 焦点视图」的原生顺序；
 * 三条需要真实事件 / 窗口引用的下发动作通过 [CrownNativeDispatch] 接缝回注，本类不持有
 * 任何策略顺序知识（顺序只在 [CrownRoutes.ordered] 声明一次）。删除本类会让 5 条策略的
 * 顺序与 3 个原生下发动作重新散回 Activity 的四个成员里。
 */
internal class CrownEventDispatch(
    onRsvpRotary: (Float) -> Boolean,
    pageTarget: () -> CrownScrollTarget?,
    private val dispatchDefaultChain: (MotionEvent) -> Boolean,
    private val dispatchToFocused: (MotionEvent) -> Boolean,
    private val rootView: () -> ViewGroup?
) {

    private val router = CrownRouter(CrownRoutes.ordered(onRsvpRotary, pageTarget))

    /** true 表示本次事件已被消费，调用方不再二次下发 */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            return router.onRotary(
                CrownTurn(CrownScrollHelper.extractCrownDelta(event)),
                nativeDispatch(event)
            )
        }
        return dispatchDefaultChain(event) || dispatchToFocused(event)
    }

    private fun nativeDispatch(event: MotionEvent): CrownNativeDispatch = NativeDispatch(event)

    /** 路由的原生下发适配器：三条需要真实事件与窗口引用的策略都收敛在这里 */
    private inner class NativeDispatch(private val event: MotionEvent) : CrownNativeDispatch {

        override fun dispatchDefault(): Boolean = dispatchDefaultChain(event)

        override fun dispatchToFocused(): Boolean = dispatchToFocused(event)

        override fun dispatchToActiveScrollableView(): Boolean {
            val root = rootView() ?: return false
            return dispatchRotaryToActiveScrollView(root, event)
        }
    }
}

/**
 * 第 5 条策略（活跃滚动容器兜底）的实现：Compose 悬浮组件夺走焦点后原生 View 失去焦点，
 * 此处直接寻址视图中第一个可见的 ScrollView / ListView 并下发。
 *
 * 顶层函数而非 Activity 成员：它只依赖 ViewGroup 与 MotionEvent，与窗口 / Activity 无关。
 */
internal fun dispatchRotaryToActiveScrollView(viewGroup: ViewGroup, event: MotionEvent): Boolean {
    for (i in 0 until viewGroup.childCount) {
        val child = viewGroup.getChildAt(i)
        if (child.isShown && (child is android.widget.ScrollView || child is android.widget.ListView)) {
            if (child.dispatchGenericMotionEvent(event)) {
                return true
            }
        }
        if (child is ViewGroup && child.isShown) {
            if (dispatchRotaryToActiveScrollView(child, event)) {
                return true
            }
        }
    }
    return false
}
