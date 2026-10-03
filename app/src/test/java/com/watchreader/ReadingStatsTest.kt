package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 阅读统计纯函数测试：完读判定、每日累积、周视图与每日目标达成
 */
class ReadingStatsTest {

    @Test
    fun testIsFinishedBoundary() {
        assertFalse(ReadingStats.isFinished(0, 0))
        assertFalse(ReadingStats.isFinished(500, 0))
        // 距结尾恰好 800 字符内算读完
        assertTrue(ReadingStats.isFinished(9200, 10000))
        assertTrue(ReadingStats.isFinished(10000, 10000))
        // 801 字符之差不算读完
        assertFalse(ReadingStats.isFinished(9199, 10000))
    }

    @Test
    fun testAddSecondsAccumulatesAndIgnoresNonPositive() {
        val days = mapOf("2026-10-03" to 60L)
        val added = ReadingStats.addSeconds(days, "2026-10-04", 120L)
        assertEquals(60L, added["2026-10-03"])
        assertEquals(120L, added["2026-10-04"])

        val accumulated = ReadingStats.addSeconds(added, "2026-10-04", 30L)
        assertEquals(150L, accumulated["2026-10-04"])

        // 非正增量不产生新键、不改原值
        assertEquals(added, ReadingStats.addSeconds(added, "2026-10-05", 0L))
        assertEquals(added, ReadingStats.addSeconds(added, "2026-10-05", -5L))
    }

    @Test
    fun testPruneKeepsLatestDays() {
        // 同前缀两位数日期键：字符串序即时间序（键未必是真实日历日，仅验证保留策略）
        val days = (1..40).associate { i ->
            String.format("2026-09-%02d", i) to (i * 10L)
        }
        val pruned = ReadingStats.pruneDays(days, keepDays = 30)
        assertEquals(30, pruned.size)
        // 最新的 30 天保留，最旧的 10 天裁剪
        assertTrue(pruned.containsKey("2026-09-40"))
        assertEquals(400L, pruned["2026-09-40"])
        assertFalse(pruned.containsKey("2026-09-10"))
        // 未超限时不裁剪、原样返回
        val small = mapOf("2026-09-01" to 10L, "2026-09-02" to 20L)
        assertEquals(small, ReadingStats.pruneDays(small, keepDays = 30))
    }

    @Test
    fun testWeekBarsMondayStartAndTodayFlag() {
        // 2026-10-07 是周三
        val today = LocalDate.of(2026, 10, 7)
        val days = mapOf(
            "2026-10-05" to 5400L,  // 周一 90 分钟
            "2026-10-07" to 600L    // 周三 10 分钟
        )
        val bars = ReadingStats.weekBars(today, days)

        assertEquals(7, bars.size)
        assertEquals("2026-10-05", bars[0].dateKey)
        assertEquals("2026-10-11", bars[6].dateKey)
        assertEquals("一", bars[0].label)
        assertEquals("日", bars[6].label)
        assertEquals(90, bars[0].minutes)
        assertEquals(10, bars[2].minutes)
        assertTrue(bars[2].isToday)
        assertFalse(bars[0].isToday)
        // 缺失日补零
        assertEquals(0, bars[1].minutes)
        assertEquals(0, bars[6].minutes)
    }

    @Test
    fun testShouldCelebrateGoalReachedOncePerDay() {
        val today = "2026-10-04"
        // 目标关闭不庆祝
        assertFalse(ReadingStats.shouldCelebrate(3600L, 0, "", today))
        // 未达目标不庆祝
        assertFalse(ReadingStats.shouldCelebrate(899L, 15, "", today))
        // 达标且今日未庆祝
        assertTrue(ReadingStats.shouldCelebrate(900L, 15, "", today))
        // 当日已庆祝不再庆祝
        assertFalse(ReadingStats.shouldCelebrate(1800L, 15, today, today))
        // 昨日庆祝过、今日达标仍庆祝
        assertTrue(ReadingStats.shouldCelebrate(900L, 15, "2026-10-03", today))
    }

    @Test
    fun testReadDaysJsonRoundtrip() {
        val days = mapOf("2026-10-03" to 5400L, "2026-10-04" to 600L)
        val json = DataStoreManager.serializeReadDays(days)
        assertEquals(days, DataStoreManager.parseReadDays(json))
        // 脏数据容错：返回空表而非抛异常
        assertEquals(emptyMap<String, Long>(), DataStoreManager.parseReadDays(null))
        assertEquals(emptyMap<String, Long>(), DataStoreManager.parseReadDays(""))
        assertEquals(emptyMap<String, Long>(), DataStoreManager.parseReadDays("not-json"))
    }

    @Test
    fun testGoalOptionsContainOffAndCommonTiers() {
        assertTrue(ReadingStats.GOAL_OPTIONS_MINUTES.contentEquals(intArrayOf(0, 15, 30, 60)))
    }
}
