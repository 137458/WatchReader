package com.watchreader

/**
 * 章节跳转代际守卫。
 *
 * goToChapter 的正文装载是挂起调用：连续点两章时，先发起的协程可能后返回，
 * 把屏幕状态写回旧章节。每次跳转领取一个代际号，返回后只有仍是当前代际的
 * 协程才能写 UI —— 旧协程直接丢弃结果，既不改屏也不报错。
 */
internal class NavigationGuard {

    private var generation = 0L

    /** 开启新一代导航，返回本次的令牌 */
    fun begin(): Long = ++generation

    /** 令牌仍是当前代际才允许落地 */
    fun isCurrent(token: Long): Boolean = token == generation

    /** 作废所有在途导航（例如退出阅读页） */
    fun invalidate() {
        generation++
    }
}
