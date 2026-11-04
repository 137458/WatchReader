package com.watchreader

/**
 * 阅读时长的落盘节流器。
 *
 * 计时循环每 10s 结算一次，但每次结算都写一次 DataStore 等于每 10s 重写整份
 * preferences（含书架 JSON）。这里只负责"攒够阈值再交出去"，把写盘频率从
 * 6 次/分钟降到 1 次/分钟，同时保证 Job 取消时余量能被 [drain] 冲刷。
 */
class ReadingSecondsBuffer(private val flushEverySeconds: Long = 60L) {

    private var pendingSeconds = 0L

    /** 记入一段阅读时长，返回本次应落盘的秒数（未达阈值返回 0） */
    fun record(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        pendingSeconds += seconds
        if (pendingSeconds < flushEverySeconds) return 0L
        val out = pendingSeconds
        pendingSeconds = 0L
        return out
    }

    /** 取走尚未落盘的余量（协程取消前冲刷） */
    fun drain(): Long {
        val out = pendingSeconds
        pendingSeconds = 0L
        return out
    }
}
