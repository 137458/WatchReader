package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 阅读统计纯计算的 JVM 用例：今日分钟按整分钟截断、周摘要只计本周并复用
 * ReadingStats.weekBars 的逐日截断语义（不是先求和再除 60）。
 */
class ReadingStatsWeekTest {

    // 2026-11-04 为周三；本周 = 周一 2026-11-02 ~ 周日 2026-11-08
    private val today: LocalDate = LocalDate.of(2026, 11, 4)
    private val monday = "2026-11-02"
    private val wednesday = "2026-11-04"
    private val sunday = "2026-11-08"
    private val prevSunday = "2026-11-01"
    private val nextMonday = "2026-11-09"

    @Test
    fun emptyDaysYieldZeroMinutesToday() {
        assertEquals(0, ReadingStatsWeek.todayMinutes(emptyMap(), wednesday))
    }

    @Test
    fun todaySecondsTruncateToWholeMinutes() {
        assertEquals(0, ReadingStatsWeek.todayMinutes(mapOf(wednesday to 59L), wednesday))
        assertEquals(1, ReadingStatsWeek.todayMinutes(mapOf(wednesday to 119L), wednesday))
        assertEquals(2, ReadingStatsWeek.todayMinutes(mapOf(wednesday to 120L), wednesday))
    }

    @Test
    fun otherDaysNeverCountAsToday() {
        val days = mapOf(monday to 3600L, prevSunday to 7200L)
        assertEquals(0, ReadingStatsWeek.todayMinutes(days, wednesday))
    }

    @Test
    fun oversizedStoredSecondsDoNotWrap() {
        assertEquals(6000, ReadingStatsWeek.todayMinutes(mapOf(wednesday to 360_000L), wednesday))
    }

    @Test
    fun negativeStoredSecondsKeepTruncationSemantics() {
        assertEquals(-2, ReadingStatsWeek.todayMinutes(mapOf(wednesday to -120L), wednesday))
    }

    @Test
    fun emptyWeekSummaryReadsZeroMinutes() {
        assertEquals("本周 0 分钟", ReadingStatsWeek.summaryLabel(emptyMap(), 0, today))
    }

    @Test
    fun summaryCountsOnlyCurrentWeek() {
        val days = mapOf(monday to 600L, prevSunday to 3600L, nextMonday to 3600L)
        assertEquals("本周 10 分钟", ReadingStatsWeek.summaryLabel(days, 0, today))
    }

    @Test
    fun summaryAppendsFinishedBookCount() {
        val days = mapOf(monday to 600L)
        assertEquals("本周 10 分钟 · 已读完 3 本", ReadingStatsWeek.summaryLabel(days, 3, today))
    }

    @Test
    fun summaryOmitsSuffixWithoutFinishedBooks() {
        val days = mapOf(monday to 600L)
        assertEquals("本周 10 分钟", ReadingStatsWeek.summaryLabel(days, 0, today))
        assertEquals("本周 10 分钟", ReadingStatsWeek.summaryLabel(days, -1, today))
    }

    @Test
    fun summarySumsPerDayTruncationNotWholeWeek() {
        // 两天各 90s：逐日截断 1+1=2；若先求和再除 60 会得到 3
        val days = mapOf(monday to 90L, wednesday to 90L)
        assertEquals("本周 2 分钟", ReadingStatsWeek.summaryLabel(days, 0, today))
    }

    @Test
    fun weekBoundaryFollowsMondayFirst() {
        val days = mapOf(monday to 60L, sunday to 120L, prevSunday to 600L)
        // 周日当天：本周含周一与周日，不含上一个周日
        assertEquals("本周 3 分钟", ReadingStatsWeek.summaryLabel(days, 0, LocalDate.of(2026, 11, 8)))
        // 下周一：旧周日 11-08 落在上一周，被排除
        assertEquals("本周 0 分钟", ReadingStatsWeek.summaryLabel(days, 0, LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun summaryIgnoresDaysOutsideMondayToSundayRange() {
        val days = mapOf("2026-10-25" to 6000L, monday to 60L)
        assertEquals("本周 1 分钟", ReadingStatsWeek.summaryLabel(days, 0, today))
    }
}
