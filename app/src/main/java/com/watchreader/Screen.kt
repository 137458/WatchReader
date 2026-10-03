package com.watchreader

/**
 * 页面路由状态
 */
sealed class Screen {
    object Home : Screen()
    object Loading : Screen()
    data class Reader(val charOffset: Int = 0, val chapterIndex: Int = 0) : Screen()
    object ChapterList : Screen()
    object Menu : Screen()
    object Rsvp : Screen()
    object Search : Screen()
    object WifiTransfer : Screen()
}
