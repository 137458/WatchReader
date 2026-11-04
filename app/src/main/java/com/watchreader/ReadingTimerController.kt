package com.watchreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读时长一次结算的状态切片：只带计时循环需要读写的四个字段。
 *
 * 计时器因此不必认识整份 ReaderUiState —— 测试也不用先搭出一整套屏幕状态。
 */
internal data class ReadingClock(
    val readDurationSec: Long,
    val readDays: Map<String, Long>,
    val readGoalMinutes: Int,
    val readGoalCelebrated: String
)

/**
 * 阅读时长后台统计循环。
 *
 * 每 [tickIntervalMs] 结算一次：总时长与当日秒数同事务落盘；每日目标首次达成时轻振庆祝。
 * 落盘按 [ReadingSecondsBuffer] 攒批（DataStore 每次 edit 都会重写整份 preferences，
 * 不能按秒写），循环取消时冲刷余量。
 *
 * 时间源、状态读写、落盘与微振全部经构造注入，因此本类零 Android 依赖：
 * 测试直接调 [settleOnce] / [flushRemainder] 断言结算与冲刷，无需真实时钟或调度器。
 */
internal class ReadingTimerController(
    private val scope: CoroutineScope,
    private val isReading: () -> Boolean,
    private val clock: () -> ReadingClock,
    private val settle: (ReadingClock) -> Unit,
    private val uptimeMs: () -> Long,
    private val lastActiveUptimeMs: () -> Long,
    private val wallClockMs: () -> Long,
    private val persistSeconds: suspend (todayKey: String, seconds: Long) -> Unit,
    private val celebrate: () -> Unit,
    private val saveCelebrated: suspend (todayKey: String) -> Unit,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val idleWindowMs: Long = IDLE_WINDOW_MS,
    private val tickSeconds: Long = TICK_SECONDS,
    private val buffer: ReadingSecondsBuffer = ReadingSecondsBuffer()
) {
    private var job: Job? = null

    /** 启动循环（重复调用先取消旧循环），返回 Job 供调用方在销毁时取消 */
    fun start(): Job {
        job?.cancel()
        val started = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    delay(tickIntervalMs)
                    settleOnce()
                }
            } finally {
                flushRemainder()
            }
        }
        job = started
        return started
    }

    /** 一次结算：不在阅读页、或已离开活跃窗口时原样返回 */
    internal suspend fun settleOnce() {
        val snapshot = clock()
        if (!isReading()) return
        val now = uptimeMs()
        if (now - lastActiveUptimeMs() > idleWindowMs) return
        val todayKey = ReadingStats.dateKeyOf(wallClockMs())
        val flushSeconds = buffer.record(tickSeconds)
        if (flushSeconds > 0L) persistSeconds(todayKey, flushSeconds)

        val newDays = ReadingStats.addSeconds(snapshot.readDays, todayKey, tickSeconds)
        var celebrated = snapshot.readGoalCelebrated
        if (ReadingStats.shouldCelebrate(newDays[todayKey] ?: 0L, snapshot.readGoalMinutes, celebrated, todayKey)) {
            celebrate()
            saveCelebrated(todayKey)
            celebrated = todayKey
        }
        settle(
            ReadingClock(
                readDurationSec = snapshot.readDurationSec + tickSeconds,
                readDays = newDays,
                readGoalMinutes = snapshot.readGoalMinutes,
                readGoalCelebrated = celebrated
            )
        )
    }

    /** 冲刷未达攒批阈值的余量（循环取消前调用；不可取消，避免碎片丢失） */
    internal suspend fun flushRemainder() {
        val remainder = buffer.drain()
        if (remainder <= 0L) return
        withContext(NonCancellable) {
            persistSeconds(ReadingStats.dateKeyOf(wallClockMs()), remainder)
        }
    }

    internal companion object {
        /** 结算间隔：10s 一次（落盘频率由攒批器压到 1 次/分钟） */
        const val TICK_INTERVAL_MS = 10_000L

        /** 距最后一次用户操作超过该时长即视为挂机，不计时长 */
        const val IDLE_WINDOW_MS = 60_000L

        /** 每次结算计入的阅读秒数 */
        const val TICK_SECONDS = 10L
    }
}
