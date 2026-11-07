package com.watchreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统界面时间戳的 locale-sensitive 格式化：每次调用都按当前的 [Locale.getDefault] 新建格式化器。
 *
 * 为什么不能缓存：SimpleDateFormat 在构造时捕获 locale，一旦系统切换语言（尤其数字系统
 * 不同的 locale，如英文 <-> 阿拉伯语/泰语），缓存的实例仍按旧 locale 渲染，直到进程重建。
 * 手表侧表盘时钟是广播驱动（ACTION_TIME_TICK 一分钟一次），并非热点路径，
 * 直接每次新建比维护 locale 失效逻辑简单且可靠。
 */
internal object LocaleFormats {

    /** 表盘右侧弧形时间戳：HH:mm，跟随当前默认 locale 的数字系统 */
    fun clockTickLabel(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    /** 书签行时间戳：MM-dd HH:mm，跟随当前默认 locale 的数字系统 */
    fun bookmarkDateLabel(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
