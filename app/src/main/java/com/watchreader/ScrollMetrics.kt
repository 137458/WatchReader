package com.watchreader

import android.widget.ScrollView

/**
 * 滚动容器「最大滚动量」的唯一口径（阅读页原生 ScrollView）
 *
 * 阅读页内容滚动范围延伸进上下 padding（clipToPadding=false），可滚动距离既不是
 * child.height - viewportHeight，也不是 scrollY 的 0..child.height；等价于 scrollTo
 * 内部 clamp 的 child.height - (viewportHeight - padTop - padBottom)，即
 * child.bottom + paddingBottom - viewportHeight。child.bottom 已含 child.top，
 * 内容顶部有偏移时无需额外加 top。
 *
 * 单一实现的意义：表冠滚动与自动滚屏曾各自量一套「到底」，同一次滚动出现两种边界判定。
 */
object ScrollMetrics {

    /** 纯函数：contentBottom 为内容子视图 bottom（= top + height），paddingBottom 为容器底 padding */
    fun maxScrollY(contentBottom: Int, paddingBottom: Int, viewportHeight: Int): Int =
        maxOf(0, contentBottom + paddingBottom - viewportHeight)

    /** 薄适配器：布局未完成（无内容子视图）时为 0，调用方无需判空 */
    fun maxScrollY(scrollView: ScrollView): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return maxScrollY(child.bottom, scrollView.paddingBottom, scrollView.height)
    }

    /**
     * 纯函数：是否已到末端、该推进到下一章
     *
     * maxScrollY > 0 时按位置判定；maxScrollY == 0 有两种含义，必须靠 [contentLaidOut] 区分：
     * 内容已布局但不足一屏 → 该翻章；内容尚未布局（子视图为空 / 高度为 0）→ 本帧跳过，
     * 否则首次组合阶段就会误触翻章。
     */
    fun shouldAdvance(currentY: Int, maxScrollY: Int, contentLaidOut: Boolean): Boolean =
        currentY >= maxScrollY && (maxScrollY > 0 || contentLaidOut)

    /** 薄适配器：内容子视图已布局且高度非零 */
    fun hasLaidOutContent(scrollView: ScrollView): Boolean {
        val child = scrollView.getChildAt(0) ?: return false
        return child.height > 0
    }

    /** 薄适配器：自动滚屏是否该推进到下一章（一次调用只暴露一件事） */
    fun shouldAdvance(scrollView: ScrollView): Boolean =
        shouldAdvance(scrollView.scrollY, maxScrollY(scrollView), hasLaidOutContent(scrollView))
}
