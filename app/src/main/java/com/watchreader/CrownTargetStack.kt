package com.watchreader

/**
 * 表冠页面目标的持有栈：后注册者优先，任意一项注销后自动回退到剩余项中最晚注册者
 *
 * 取代早先的单槽覆盖语义：菜单页注册滚动目标后，主题选择浮层再注册会覆盖菜单，
 * 浮层退出时旧实现只在"仍是自己"时清空，菜单的注册已丢失，表现为回到菜单后表冠失效。
 * 栈把"谁在前台"变成注册顺序的事实，而不是各页面各自猜测并补 if。
 *
 * 纯 JVM 结构（不依赖 Compose/Android），注册/注销/回退顺序可直接单测。
 */
internal class CrownTargetStack<T : Any> {

    private data class Entry<T>(val token: Long, val target: T)

    private val entries = ArrayList<Entry<T>>()
    private var nextToken = 1L

    val size: Int get() = entries.size

    /** 当前持有者：最后注册且尚未注销的目标 */
    val active: T? get() = entries.lastOrNull()?.target

    /**
     * 注册（或重新置顶）一个目标：同一目标重复注册会先移除旧条目，避免注销时残留重复项。
     *
     * @return 本次注册的 token，供需要精确注销的调用方使用
     */
    fun push(target: T): Long {
        entries.removeAll { it.target === target }
        val token = nextToken++
        entries.add(Entry(token, target))
        return token
    }

    /** 按目标身份注销（移除全部同目标条目） */
    fun remove(target: T): Boolean = entries.removeAll { it.target === target }

    /** 按 token 注销（只移除对应条目） */
    fun removeToken(token: Long): Boolean = entries.removeAll { it.token == token }

    fun clear() {
        entries.clear()
    }
}
