package com.watchreader

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val DATASTORE_NAME = "watch_reader_prefs"
private const val OLD_PREFS_NAME = "watch_reader"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, OLD_PREFS_NAME))
    }
)

/**
 * 冷启动初始快照模型
 */
data class AppInitialConfig(
    val fontSize: Int,
    val autoScrollSpeed: Float,
    val appBrightness: Float,
    val bookshelf: List<BookItem>,
    val lastScreen: String,
    val lastUri: Uri?,
    val lastCharOffset: Int,
    val themeMode: Int = 0, // 0: 羊皮纸, 1: 极光黑, 2: 纯黑深红
    val tapPageArea: Int = 0, // 0: 上下翻页, 1: 左右翻页, 2: 关闭点按
    val cleanTypography: Boolean = true,
    val fontType: Int = 0, // 0: 黑体, 1: 宋体/衬线
    val readDurationSec: Long = 0L,
    val readDays: Map<String, Long> = emptyMap(), // 每日阅读秒数（本地日期键）
    val readGoalMinutes: Int = 0, // 每日目标分钟，0=关闭
    val readGoalCelebrated: String = "" // 最近庆祝的日期键
)

/**
 * DataStore 持久化管理器（协程与 Flow 响应式驱动）
 */
object DataStoreManager {

    val KEY_LAST_URI = stringPreferencesKey("last_uri")
    val KEY_LAST_CHAR_OFFSET = intPreferencesKey("last_char_offset")
    val KEY_LAST_SCREEN = stringPreferencesKey("last_screen") // "home" 或 "reader"
    val KEY_FONT_SIZE = intPreferencesKey("font_size")
    val KEY_BOOK_SHELF = stringPreferencesKey("book_shelf_json")
    val KEY_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    val KEY_AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
    val KEY_APP_BRIGHTNESS = floatPreferencesKey("app_brightness") // -1.0f: 跟随系统, 0.01f ~ 1.0f: 自定义亮度
    val KEY_THEME_MODE = intPreferencesKey("theme_mode") // 0: 羊皮纸, 1: 极光黑, 2: 纯黑深红
    val KEY_TAP_PAGE_AREA = intPreferencesKey("tap_page_area") // 0: 上下翻页, 1: 左右翻页, 2: 关闭点按
    val KEY_CLEAN_TYPOGRAPHY = booleanPreferencesKey("clean_typography") // 智能排版净化
    val KEY_FONT_TYPE = intPreferencesKey("font_type") // 0: 黑体, 1: 宋体/衬线
    val KEY_READ_DURATION_SEC = longPreferencesKey("read_duration_sec") // 累计阅读时长
    val KEY_READ_DAYS_JSON = stringPreferencesKey("read_days_json") // 每日阅读秒数 {"yyyy-MM-dd":sec}
    val KEY_READ_GOAL_MINUTES = intPreferencesKey("read_goal_minutes") // 每日阅读目标（分钟），0=关闭
    val KEY_READ_GOAL_CELEBRATED = stringPreferencesKey("read_goal_celebrated") // 最近一次目标达成庆祝的日期键

    const val DEFAULT_FONT_SIZE = 14
    const val DEFAULT_AUTO_SCROLL_SPEED = 45f // 默认 45 像素/秒 (约 2~3 行/秒)
    const val DEFAULT_BRIGHTNESS = -1.0f // 默认跟随系统

    private fun getSafePreferencesFlow(context: Context): Flow<Preferences> =
        context.dataStore.data.catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    /**
     * 单次 I/O 批量读取全部核心配置（用于 Application / ViewModel 冷启动智能恢复）
     */
    suspend fun loadInitialConfig(context: Context): AppInitialConfig {
        val prefs = getSafePreferencesFlow(context).first()
        val fontSize = prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE
        val autoScrollSpeed = prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED
        val appBrightness = prefs[KEY_APP_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS
        val bookshelf = parseBookShelf(prefs[KEY_BOOK_SHELF])
        val lastScreen = prefs[KEY_LAST_SCREEN] ?: "home"
        val lastUriStr = prefs[KEY_LAST_URI]
        val lastUri = if (!lastUriStr.isNullOrEmpty()) {
            try {
                Uri.parse(lastUriStr)
            } catch (_: Exception) {
                null
            }
        } else null
        val lastCharOffset = prefs[KEY_LAST_CHAR_OFFSET] ?: 0
        // 主题是配色的唯一状态源；KEY_DARK_MODE 仅作旧版遗留数据的迁移输入（老版本只写这个键）
        val themeMode = prefs[KEY_THEME_MODE] ?: (if (prefs[KEY_DARK_MODE] == true) 1 else 0)
        val tapPageArea = prefs[KEY_TAP_PAGE_AREA] ?: 0
        val cleanTypography = prefs[KEY_CLEAN_TYPOGRAPHY] ?: true
        val fontType = prefs[KEY_FONT_TYPE] ?: 0
        val readDurationSec = prefs[KEY_READ_DURATION_SEC] ?: 0L
        val readDays = parseReadDays(prefs[KEY_READ_DAYS_JSON])
        val readGoalMinutes = prefs[KEY_READ_GOAL_MINUTES] ?: 0
        val readGoalCelebrated = prefs[KEY_READ_GOAL_CELEBRATED] ?: ""

        return AppInitialConfig(
            fontSize = fontSize,
            autoScrollSpeed = autoScrollSpeed,
            appBrightness = appBrightness,
            bookshelf = bookshelf,
            lastScreen = lastScreen,
            lastUri = lastUri,
            lastCharOffset = lastCharOffset,
            themeMode = themeMode,
            tapPageArea = tapPageArea,
            cleanTypography = cleanTypography,
            fontType = fontType,
            readDurationSec = readDurationSec,
            readDays = readDays,
            readGoalMinutes = readGoalMinutes,
            readGoalCelebrated = readGoalCelebrated
        )
    }

    suspend fun saveThemeMode(context: Context, mode: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
            prefs[KEY_DARK_MODE] = (mode != 0)
        }
    }

    suspend fun saveTapPageArea(context: Context, area: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TAP_PAGE_AREA] = area
        }
    }

    suspend fun saveCleanTypography(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLEAN_TYPOGRAPHY] = enabled
        }
    }

    suspend fun saveFontType(context: Context, type: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FONT_TYPE] = type
        }
    }

    suspend fun saveReadDurationSec(context: Context, seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_DURATION_SEC] = seconds
        }
    }

    /**
     * 单事务累积一次阅读计时：总时长与当日秒数同事务落盘，并裁剪 30 天前历史
     */
    suspend fun addReadingSeconds(context: Context, seconds: Long, todayKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_DURATION_SEC] = (prefs[KEY_READ_DURATION_SEC] ?: 0L) + seconds
            val days = ReadingStats.addSeconds(parseReadDays(prefs[KEY_READ_DAYS_JSON]), todayKey, seconds)
            prefs[KEY_READ_DAYS_JSON] = serializeReadDays(ReadingStats.pruneDays(days))
        }
    }

    suspend fun setReadGoalMinutes(context: Context, minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_GOAL_MINUTES] = if (ReadingStats.GOAL_OPTIONS_MINUTES.contains(minutes)) minutes else 0
        }
    }

    suspend fun saveReadGoalCelebrated(context: Context, dateKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READ_GOAL_CELEBRATED] = dateKey
        }
    }

    // ── 每日阅读时长 JSON 编解码（internal 供单测） ──
    internal fun serializeReadDays(days: Map<String, Long>): String {
        val obj = JSONObject()
        for ((key, sec) in days) {
            obj.put(key, sec)
        }
        return obj.toString()
    }

    internal fun parseReadDays(jsonStr: String?): Map<String, Long> {
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

    /** 保存最后活跃页面 */
    suspend fun saveLastScreen(context: Context, screenName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SCREEN] = screenName
        }
    }

    suspend fun saveFontSize(context: Context, size: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FONT_SIZE] = size
        }
    }

    // ── 深色模式不再单独存储：深色只是主题（themeMode）的一种取值，
    //    读写一律走 saveThemeMode / loadInitialConfig，避免两套状态各说各话 ──

    suspend fun saveAutoScrollSpeed(context: Context, speed: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_SCROLL_SPEED] = speed.coerceIn(10f, 200f)
        }
    }

    suspend fun saveAppBrightness(context: Context, brightness: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_BRIGHTNESS] = if (brightness < 0f) -1.0f else brightness.coerceIn(0.01f, 1.0f)
        }
    }

    // ── 阅读位置设置 ──
    suspend fun saveReadingPosition(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ) {
        val uriStr = uri.toString()
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_URI] = uriStr
            prefs[KEY_LAST_CHAR_OFFSET] = charOffset
            prefs[KEY_LAST_SCREEN] = "reader"

            // 单次原子事务同步更新书架记录，消除二次磁盘 I/O 写入
            val merged = mergeBookEntry(
                currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]),
                uriStr = uriStr,
                charOffset = charOffset,
                totalChars = totalChars,
                chapterTitle = chapterTitle,
                fallbackTitle = { getFileName(context, uri) },
                nowMs = System.currentTimeMillis()
            )
            prefs[KEY_BOOK_SHELF] = serializeBookShelf(merged)
        }
    }

    suspend fun clearReadingPosition(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_URI)
            prefs.remove(KEY_LAST_CHAR_OFFSET)
            prefs[KEY_LAST_SCREEN] = "home"
        }
    }

    // ── 书架管理 ──
    suspend fun loadBookShelf(context: Context): List<BookItem> {
        val prefs = getSafePreferencesFlow(context).first()
        return parseBookShelf(prefs[KEY_BOOK_SHELF])
    }

    suspend fun updateBookInShelf(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ): List<BookItem> {
        val uriStr = uri.toString()
        var resultList: List<BookItem> = emptyList()
        context.dataStore.edit { prefs ->
            val merged = mergeBookEntry(
                currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]),
                uriStr = uriStr,
                charOffset = charOffset,
                totalChars = totalChars,
                chapterTitle = chapterTitle,
                fallbackTitle = { getFileName(context, uri) },
                nowMs = System.currentTimeMillis()
            )
            prefs[KEY_BOOK_SHELF] = serializeBookShelf(merged)
            resultList = merged.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })
        }
        return resultList
    }

    suspend fun toggleBookPin(context: Context, uriString: String): List<BookItem> {
        var resultList: List<BookItem> = emptyList()
        context.dataStore.edit { prefs ->
            val currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]).toMutableList()
            val idx = currentList.indexOfFirst { it.uriString == uriString }
            if (idx >= 0) {
                val item = currentList[idx]
                currentList[idx] = item.copy(isPinned = !item.isPinned)
                prefs[KEY_BOOK_SHELF] = serializeBookShelf(currentList)
            }
            resultList = currentList.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })
        }
        return resultList
    }

    suspend fun removeBookFromShelf(context: Context, uriString: String) {
        context.dataStore.edit { prefs ->
            val currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]).filter { it.uriString != uriString }
            prefs[KEY_BOOK_SHELF] = serializeBookShelf(currentList)
            if (prefs[KEY_LAST_URI] == uriString) {
                prefs.remove(KEY_LAST_URI)
                prefs.remove(KEY_LAST_CHAR_OFFSET)
                prefs[KEY_LAST_SCREEN] = "home"
            }
        }
    }

    fun serializeBookShelf(list: List<BookItem>): String {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("uri", item.uriString)
                put("title", item.title)
                put("offset", item.charOffset)
                put("total", item.totalChars)
                put("chapter", item.lastChapterTitle)
                put("time", item.lastReadTime)
                put("pinned", item.isPinned)
                put("finished", item.finished)
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun parseBookShelf(jsonStr: String?): List<BookItem> {
        if (jsonStr.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<BookItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BookItem(
                        uriString = obj.getString("uri"),
                        title = obj.optString("title", "未命名小说"),
                        charOffset = obj.optInt("offset", 0),
                        totalChars = obj.optInt("total", 0),
                        lastChapterTitle = obj.optString("chapter", ""),
                        lastReadTime = obj.optLong("time", 0L),
                        isPinned = obj.optBoolean("pinned", false),
                        finished = obj.optBoolean("finished", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })
    }

    // ── 书签管理 ──
    private fun getBookmarkKey(uriString: String): Preferences.Key<String> {
        return stringPreferencesKey("bookmarks_${uriString.hashCode()}")
    }

    suspend fun loadBookmarks(context: Context, uriString: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        val prefs = getSafePreferencesFlow(context).first()
        val jsonStr = prefs[getBookmarkKey(uriString)] ?: return emptyList()
        val list = mutableListOf<Bookmark>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        chapterIndex = obj.optInt("chapterIndex", 0),
                        chapterTitle = obj.optString("chapterTitle", ""),
                        charOffset = obj.optInt("charOffset", 0),
                        snippet = obj.optString("snippet", ""),
                        time = obj.optLong("time", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.time }
    }

    suspend fun saveBookmark(context: Context, uriString: String, bookmark: Bookmark): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var resultList: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            val key = getBookmarkKey(uriString)
            val currentList = parseBookmarks(prefs[key]).toMutableList()
            currentList.removeAll { it.chapterIndex == bookmark.chapterIndex && Math.abs(it.charOffset - bookmark.charOffset) < 10 }
            currentList.add(0, bookmark)
            prefs[key] = serializeBookmarks(currentList)
            resultList = currentList
        }
        return resultList
    }

    suspend fun removeBookmark(context: Context, uriString: String, bookmarkId: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var resultList: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            val key = getBookmarkKey(uriString)
            val currentList = parseBookmarks(prefs[key]).filter { it.id != bookmarkId }
            prefs[key] = serializeBookmarks(currentList)
            resultList = currentList
        }
        return resultList
    }

    private fun serializeBookmarks(list: List<Bookmark>): String {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("chapterIndex", item.chapterIndex)
                put("chapterTitle", item.chapterTitle)
                put("charOffset", item.charOffset)
                put("snippet", item.snippet)
                put("time", item.time)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseBookmarks(jsonStr: String?): List<Bookmark> {
        if (jsonStr.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<Bookmark>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        chapterIndex = obj.optInt("chapterIndex", 0),
                        chapterTitle = obj.optString("chapterTitle", ""),
                        charOffset = obj.optInt("charOffset", 0),
                        snippet = obj.optString("snippet", ""),
                        time = obj.optLong("time", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.time }
    }
}

/**
 * 书架条目合并核心（纯函数）：saveReadingPosition 与 updateBookInShelf 的共享实现。
 *
 * 语义：已有条目原位更新并保留标题/置顶/旧总长等字段，新条目插入首位。
 * fallbackTitle 惰性求值——仅新条目触发（ContentResolver 查询有 IO 成本，
 * 已有条目的每次进度保存都不应付出该成本）。
 */
internal fun mergeBookEntry(
    currentList: List<BookItem>,
    uriStr: String,
    charOffset: Int,
    totalChars: Int,
    chapterTitle: String,
    fallbackTitle: () -> String,
    nowMs: Long
): MutableList<BookItem> {
    val list = currentList.toMutableList()
    val existingIdx = list.indexOfFirst { it.uriString == uriStr }
    val existing = if (existingIdx >= 0) list[existingIdx] else null
    // 完读一次性置位不回退：本次进度贴近结尾，或既有条目已标记
    val resolvedTotal = if (totalChars > 0) totalChars else existing?.totalChars ?: 0
    val updated = BookItem(
        uriString = uriStr,
        title = existing?.title ?: fallbackTitle(),
        charOffset = charOffset,
        totalChars = resolvedTotal,
        lastChapterTitle = chapterTitle.ifEmpty { existing?.lastChapterTitle ?: "" },
        lastReadTime = nowMs,
        isPinned = existing?.isPinned ?: false,
        finished = (existing?.finished ?: false) || ReadingStats.isFinished(charOffset, resolvedTotal)
    )
    if (existingIdx >= 0) {
        list[existingIdx] = updated
    } else {
        list.add(0, updated)
    }
    return list
}

/**
 * 书签数据模型
 */
@androidx.compose.runtime.Immutable
data class Bookmark(
    val id: String = java.util.UUID.randomUUID().toString(),
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val charOffset: Int = 0,
    val snippet: String = "",
    val time: Long = System.currentTimeMillis()
)
