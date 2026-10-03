package com.watchreader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 阅读统计纯函数引擎：每日时长累积、周视图、完读判定与每日目标达成
 *
 * 日期键统一为本地时区 ISO "yyyy-MM-dd"（字符串序即时间序，可直接排序/比较）。
 * 全部函数无副作用，时钟经入参注入以便测试。
 */
object ReadingStats {

    /** 距全书结尾不足该字符数且总长有效时，视为读完 */
    const val FINISH_THRESHOLD_CHARS = 800

    /** 每日时长表最多保留的天数（防 DataStore JSON 无界增长） */
    const val KEEP_DAYS = 30

    /** 每日目标可选档位（分钟），0 = 关闭 */
    val GOAL_OPTIONS_MINUTES = intArrayOf(0, 15, 30, 60)

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 周一至周日的单字符标签（与 weekBars 下标一一对应） */
    private val DAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")

    /** 周视图单日条形 */
    data class DayBar(
        val dateKey: String,
        val label: String,
        val minutes: Int,
        val isToday: Boolean
    )

    /** epoch 毫秒 → 本地日期键 */
    fun dateKeyOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)

    /** 完读判定：总长有效且进度贴近结尾 */
    fun isFinished(charOffset: Int, totalChars: Int): Boolean =
        totalChars > 0 && charOffset >= totalChars - FINISH_THRESHOLD_CHARS

    /** 累积某日阅读秒数（不可变更新，非正数增量原样返回） */
    fun addSeconds(days: Map<String, Long>, dateKey: String, seconds: Long): Map<String, Long> {
        if (seconds <= 0L) return days
        val merged = days.toMutableMap()
        merged[dateKey] = (merged[dateKey] ?: 0L) + seconds
        return merged
    }

    /** 裁剪历史：按日期键降序保留最近 [keepDays] 天（今天必在保留集内） */
    fun pruneDays(days: Map<String, Long>, keepDays: Int = KEEP_DAYS): Map<String, Long> {
        if (days.size <= keepDays) return days
        return days.entries.sortedByDescending { it.key }.take(keepDays).associate { it.key to it.value }
    }

    /** 周一为一周之首的 7 日条形数据（缺失日补零，分钟取整） */
    fun weekBars(today: LocalDate, days: Map<String, Long>): List<DayBar> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            val key = date.format(DATE_FORMAT)
            DayBar(
                dateKey = key,
                label = DAY_LABELS[offset],
                minutes = ((days[key] ?: 0L) / 60L).toInt(),
                isToday = date == today
            )
        }
    }

    /** 每日目标达成判定：目标开启、当日尚未庆祝过、今日已读时长达目标 */
    fun shouldCelebrate(todaySeconds: Long, goalMinutes: Int, celebratedDateKey: String, todayKey: String): Boolean {
        if (goalMinutes <= 0) return false
        if (celebratedDateKey == todayKey) return false
        return todaySeconds >= goalMinutes * 60L
    }
}
