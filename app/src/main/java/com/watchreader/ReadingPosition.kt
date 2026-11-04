package com.watchreader

import androidx.compose.runtime.mutableStateOf

/**
 * 当前阅读位置的唯一所有者。
 *
 * 同一份事实此前由两个字段各持一份（Compose 状态 + volatile 数组），
 * 写点分散在原生滚动回调里，漏写一处就会出现"进度指示停在旧位置"
 * 或"跳页回到本章开头"。这里把写入收成一个入口 [update]，读出分两种：
 * 组合读 [current]（变更触发进度指示重组）、事件路径读 [latestValue]（不建立快照订阅，
 * 避免 AndroidView.update 阶段读状态引发整页重组）。
 */
class ReadingPosition(initial: Int) {

    private val state = mutableStateOf(initial)

    @Volatile
    private var latest: Int = initial

    /** 组合读：值变化会触发读取方重组 */
    val current: Int get() = state.value

    /** 事件路径读：最近一次写入值，不订阅快照 */
    val latestValue: Int get() = latest

    /** 唯一写入点：先更新事件路径值再推给组合，保证两侧永不分叉 */
    fun update(offset: Int) {
        latest = offset
        if (state.value != offset) state.value = offset
    }
}
