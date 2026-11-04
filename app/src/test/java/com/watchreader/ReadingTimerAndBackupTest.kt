package com.watchreader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读计时循环与备份恢复三段状态机的行为断言。
 *
 * 两个被测对象都零 Android 依赖（时间源、落盘、配置读取全部构造注入），
 * 因此这里不用虚拟时间：直接驱动 [ReadingTimerController.settleOnce] /
 * [ReadingTimerController.flushRemainder] 与 [BackupRestoreController] 的三段迁移，
 * 断言的是攒批落盘秒数与提示条文案这些可观察结果。
 */
class ReadingTimerAndBackupTest {

    // ── 阅读计时循环 ──────────────────────────────────────────

    private class TimerHarness(
        var clock: ReadingClock = ReadingClock(0L, emptyMap(), 0, ""),
        var reading: Boolean = true,
        var nowUptime: Long = 0L,
        var lastActive: Long = 0L,
        var wallMs: Long = 1_700_000_000_000L
    ) {
        val persisted = mutableListOf<Pair<String, Long>>()
        val settled = mutableListOf<ReadingClock>()
        var celebrations = 0
        val celebrationKeys = mutableListOf<String>()

        private val controller = ReadingTimerController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            isReading = { reading },
            clock = { clock },
            settle = {
                settled += it
                clock = it
            },
            uptimeMs = { nowUptime },
            lastActiveUptimeMs = { lastActive },
            wallClockMs = { wallMs },
            persistSeconds = { key, seconds -> persisted += key to seconds },
            celebrate = { celebrations++ },
            saveCelebrated = { key -> celebrationKeys += key }
        )

        val todayKey: String get() = ReadingStats.dateKeyOf(wallMs)

        fun tick(times: Int = 1) = runBlocking { repeat(times) { controller.settleOnce() } }

        fun flush() = runBlocking { controller.flushRemainder() }
    }

    @Test
    fun fiveTicksAccumulateWithoutPersisting() {
        val h = TimerHarness()

        h.tick(5)

        assertTrue("攒批未满 60s 不应落盘", h.persisted.isEmpty())
        assertEquals(5, h.settled.size)
        assertEquals(50L, h.clock.readDurationSec)
        assertEquals(50L, h.clock.readDays.getValue(h.todayKey))
    }

    @Test
    fun sixthTickPersistsWholeBatchOnce() {
        val h = TimerHarness()

        h.tick(6)

        assertEquals(listOf(h.todayKey to 60L), h.persisted)
        assertEquals(60L, h.clock.readDurationSec)
    }

    @Test
    fun flushWritesPendingRemainderOnlyOnce() {
        val h = TimerHarness()
        h.tick(3)

        h.flush()
        assertEquals(listOf(h.todayKey to 30L), h.persisted)

        h.flush()
        assertEquals("余量冲刷后攒批器应清零，不得重复落盘", 1, h.persisted.size)
    }

    @Test
    fun flushAfterFullBatchWritesNothingExtra() {
        val h = TimerHarness()
        h.tick(6)

        h.flush()

        assertEquals(listOf(h.todayKey to 60L), h.persisted)
    }

    @Test
    fun idleUserAccruesNothing() {
        val h = TimerHarness(nowUptime = ReadingTimerController.IDLE_WINDOW_MS + 1L)

        h.tick(3)

        assertTrue("超过挂机窗口不应结算", h.settled.isEmpty())
        assertTrue(h.persisted.isEmpty())
        assertEquals(0L, h.clock.readDurationSec)
    }

    @Test
    fun leavingReaderAccruesNothing() {
        val h = TimerHarness(reading = false)

        h.tick(3)

        assertTrue(h.settled.isEmpty())
        assertTrue(h.persisted.isEmpty())
    }

    @Test
    fun goalCelebratesExactlyOnceAtThreshold() {
        val h = TimerHarness(clock = ReadingClock(0L, emptyMap(), 1, ""))

        h.tick(5)
        assertEquals("未满 1 分钟目标不应庆祝", 0, h.celebrations)

        h.tick(1)
        assertEquals(1, h.celebrations)
        assertEquals(listOf(h.todayKey), h.celebrationKeys)
        assertEquals(h.todayKey, h.clock.readGoalCelebrated)

        h.tick(5)
        assertEquals("同一日期只庆祝一次", 1, h.celebrations)
        assertEquals(110L, h.clock.readDurationSec)
    }

    @Test
    fun alreadyCelebratedDateSuppressesCelebration() {
        val todayKey = ReadingStats.dateKeyOf(1_700_000_000_000L)
        val h = TimerHarness(
            clock = ReadingClock(0L, mapOf(todayKey to 120L), 1, todayKey)
        )

        h.tick(2)

        assertEquals(0, h.celebrations)
        assertEquals(140L, h.clock.readDays.getValue(todayKey))
    }

    // ── 备份恢复三段状态机 ────────────────────────────────────

    private class BackupHarness(
        initialState: ReaderUiState = ReaderUiState(),
        var restoreResult: Int = 2,
        var config: AppInitialConfig = restoredConfig()
    ) {
        val state = MutableStateFlow(initialState)
        val restoreCalls = mutableListOf<String>()

        val controller = BackupRestoreController(
            uiState = state,
            exportJson = { EXPORT_JSON },
            restoreFromJson = { json ->
                restoreCalls += json
                restoreResult
            },
            loadConfig = { config }
        )
    }

    @Test
    fun previewValidBackupReportsShelfCount() {
        val h = BackupHarness()

        val count = h.controller.preview(backupJson(2))

        assertEquals(2, count ?: -1)
        assertEquals(2, h.state.value.pendingRestoreCount)
        assertNull(h.state.value.errorMessage)
    }

    @Test
    fun previewInvalidBackupReportsFailureAndRestoresNothing() = runBlocking {
        val h = BackupHarness()

        val count = h.controller.preview("not json at all")

        assertNull(count)
        assertNull(h.state.value.pendingRestoreCount)
        assertEquals("恢复失败：备份不是合法 JSON", h.state.value.errorMessage)

        h.controller.confirm()
        assertTrue("预览失败后确认不应触发覆盖", h.restoreCalls.isEmpty())
    }

    @Test
    fun cancelDropsPendingBackup() = runBlocking {
        val h = BackupHarness()
        h.controller.preview(backupJson(2))

        h.controller.cancel()
        assertNull(h.state.value.pendingRestoreCount)

        h.controller.confirm()
        assertTrue("取消后确认不应触发覆盖", h.restoreCalls.isEmpty())
        assertNull(h.state.value.infoMessage)
    }

    @Test
    fun confirmAppliesRestoredConfig() = runBlocking {
        val h = BackupHarness()
        val json = backupJson(2)
        h.controller.preview(json)

        h.controller.confirm()

        assertEquals(listOf(json), h.restoreCalls)
        val s = h.state.value
        assertEquals(22, s.fontSize)
        assertEquals(88f, s.autoScrollSpeed, 0.001f)
        assertEquals(0.4f, s.appBrightness, 0.001f)
        assertEquals(1, s.themeMode)
        assertEquals(1, s.tapPageArea)
        assertFalse(s.cleanTypography)
        assertEquals(1, s.fontType)
        assertEquals(7200L, s.readDurationSec)
        assertEquals(mapOf("2026-01-01" to 60L), s.readDays)
        assertEquals(30, s.readGoalMinutes)
        assertEquals("2026-01-01", s.readGoalCelebrated)
        assertEquals(2, s.lineSpacing)
        assertEquals(2, s.letterSpacing)
        assertEquals(1, s.bookshelf.size)
        assertEquals("已恢复 2 本书与全部设置", s.infoMessage)
        assertNull(s.pendingRestoreCount)
    }

    @Test
    fun failedRestoreLeavesStateUntouched() = runBlocking {
        val h = BackupHarness(restoreResult = -1)
        val before = h.state.value

        val ok = h.controller.restore(backupJson(1))

        assertFalse(ok)
        assertEquals(before, h.state.value)
        assertNull(h.state.value.infoMessage)
        assertEquals(14, h.state.value.fontSize)
    }

    @Test
    fun exportAndNotificationMessages() = runBlocking {
        val h = BackupHarness()

        assertEquals(EXPORT_JSON, h.controller.export())

        h.controller.notifyExported(true)
        assertEquals("备份已导出", h.state.value.infoMessage)

        h.controller.clearInfoMessage()
        assertNull(h.state.value.infoMessage)

        h.controller.notifyExported(false)
        assertEquals("备份导出失败：无法写入所选文件", h.state.value.errorMessage)

        h.controller.notifyRestoreFailed()
        assertEquals("恢复失败：不是有效的备份文件", h.state.value.errorMessage)
    }

    private companion object {
        const val EXPORT_JSON = """{"type":"watchreader_backup","version":1}"""

        fun book(index: Int): BookItem = BookItem(
            uriString = "content://book/$index",
            title = "测试书$index",
            charOffset = 0,
            totalChars = 100,
            lastChapterTitle = "第一章",
            lastReadTime = 0L
        )

        fun backupJson(shelfSize: Int): String =
            BackupCodec.encode(BackupPayload(shelf = List(shelfSize) { book(it) }), 1696400009999L)

        fun restoredConfig(): AppInitialConfig = AppInitialConfig(
            fontSize = 22,
            autoScrollSpeed = 88f,
            appBrightness = 0.4f,
            bookshelf = listOf(book(9)),
            lastScreen = "reader",
            lastUri = null,
            lastCharOffset = 1234,
            themeMode = 1,
            tapPageArea = 1,
            cleanTypography = false,
            fontType = 1,
            readDurationSec = 7200L,
            readDays = mapOf("2026-01-01" to 60L),
            readGoalMinutes = 30,
            readGoalCelebrated = "2026-01-01",
            lineSpacing = 2,
            letterSpacing = 2
        )
    }
}
