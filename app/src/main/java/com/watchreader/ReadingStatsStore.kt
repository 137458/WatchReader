package com.watchreader

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONObject

/**
 * 阅读统计 store：累计时长、每日时长、每日目标、目标庆祝日期。
 *
 * 跨天裁剪与「目标档位只能取枚举值」两条规则在这里收口，
 * 调用方只表达「加了 N 秒」「设成 M 分钟」，不再各自校验。
 */
object ReadingStatsStore {

    private val KEY_READ_DURATION_SEC = longPreferencesKey("read_duration_sec")
    private val KEY_READ_DAYS_JSON = stringPreferencesKey("read_days_json")
    private val KEY_READ_GOAL_MINUTES = intPreferencesKey("read_goal_minutes")
    private val KEY_READ_GOAL_CELEBRATED = stringPreferencesKey("read_goal_celebrated")

    data class Stats(
        val totalSec: Long = 0L,
        val days: Map<String, Long> = emptyMap(),
        val goalMinutes: Int = 0,
        val celebrated: String = ""
    )

    // ── 对外 interface：写入入口（整域读取由门面 loadInitialConfig 与备份的单次 I/O 承担） ──

    /** 单事务累积一次阅读计时：总时长与当日秒数同事务落盘，并裁剪 30 天前历史 */
    suspend fun addSeconds(context: Context, seconds: Long, todayKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_DURATION_SEC] = (prefs[KEY_READ_DURATION_SEC] ?: 0L) + seconds
            val days = ReadingStats.addSeconds(decodeDays(prefs[KEY_READ_DAYS_JSON]), todayKey, seconds)
            prefs[KEY_READ_DAYS_JSON] = encodeDays(ReadingStats.pruneDays(days))
        }
    }

    /** 目标档位只接受 [ReadingStats.GOAL_OPTIONS_MINUTES] 里的值，非法值落 0（关闭） */
    suspend fun setGoalMinutes(context: Context, minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_GOAL_MINUTES] = if (ReadingStats.GOAL_OPTIONS_MINUTES.contains(minutes)) minutes else 0
        }
    }

    suspend fun saveGoalCelebrated(context: Context, dateKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_GOAL_CELEBRATED] = dateKey
        }
    }

    suspend fun saveTotalSec(context: Context, seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_DURATION_SEC] = seconds
        }
    }

    // ── 事务级核心（internal） ──

    internal fun read(prefs: Preferences): Stats = Stats(
        totalSec = prefs[KEY_READ_DURATION_SEC] ?: 0L,
        days = decodeDays(prefs[KEY_READ_DAYS_JSON]),
        goalMinutes = prefs[KEY_READ_GOAL_MINUTES] ?: 0,
        celebrated = prefs[KEY_READ_GOAL_CELEBRATED] ?: ""
    )

    /**
     * 恢复备份时写统计三键。
     * 刻意不写 celebrated：历史恢复行为就不覆盖它，波次二只做搬移，不改行为。
     */
    internal fun writeRestore(prefs: MutablePreferences, totalSec: Long, days: Map<String, Long>, goalMinutes: Int) {
        prefs[KEY_READ_DURATION_SEC] = totalSec
        prefs[KEY_READ_DAYS_JSON] = encodeDays(days)
        prefs[KEY_READ_GOAL_MINUTES] = goalMinutes
    }

    // ── 每日时长 JSON 编解码（单一实现，读、写、恢复共用） ──

    internal fun encodeDays(days: Map<String, Long>): String {
        val obj = JSONObject()
        for ((key, sec) in days) {
            obj.put(key, sec)
        }
        return obj.toString()
    }

    internal fun decodeDays(jsonStr: String?): Map<String, Long> {
        if (jsonStr.isNullOrEmpty()) return emptyMap()
        val map = mutableMapOf<String, Long>()
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optLong(key, 0L)
            }
        } catch (_: Exception) {}
        return map
    }
}
