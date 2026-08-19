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
import kotlinx.coroutines.flow.map
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
    val isDarkMode: Boolean,
    val autoScrollSpeed: Float,
    val appBrightness: Float,
    val bookshelf: List<BookItem>,
    val lastScreen: String,
    val lastUri: Uri?,
    val lastCharOffset: Int
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
     * 单次 I/O 批量读取冷启动所需的所有配置（消除多 Flow 串行阻塞，将冷启动耗时降至极致）
     */
    suspend fun loadInitialConfig(context: Context): AppInitialConfig {
        val prefs = getSafePreferencesFlow(context).first()
        val fontSize = prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE
        val isDarkMode = prefs[KEY_DARK_MODE] ?: false
        val autoScrollSpeed = prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED
        val appBrightness = prefs[KEY_APP_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS
        val bookshelf = parseBookShelf(prefs[KEY_BOOK_SHELF])
        val lastScreen = prefs[KEY_LAST_SCREEN] ?: if (prefs[KEY_LAST_URI] != null) "reader" else "home"
        val uriStr = prefs[KEY_LAST_URI]
        val lastUri = if (!uriStr.isNullOrEmpty()) {
            try {
                Uri.parse(uriStr)
            } catch (_: Exception) {
                null
            }
        } else null
        val lastCharOffset = prefs[KEY_LAST_CHAR_OFFSET] ?: 0

        return AppInitialConfig(
            fontSize = fontSize,
            isDarkMode = isDarkMode,
            autoScrollSpeed = autoScrollSpeed,
            appBrightness = appBrightness,
            bookshelf = bookshelf,
            lastScreen = lastScreen,
            lastUri = lastUri,
            lastCharOffset = lastCharOffset
        )
    }

    /** 保存最后活跃页面 */
    suspend fun saveLastScreen(context: Context, screenName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SCREEN] = screenName
        }
    }

    // ── 字号设置 ──
    fun getFontSizeFlow(context: Context): Flow<Int> =
        getSafePreferencesFlow(context).map { prefs ->
            prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE
        }

    suspend fun saveFontSize(context: Context, size: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FONT_SIZE] = size
        }
    }

    // ── 深色模式设置 ──
    fun getDarkModeFlow(context: Context): Flow<Boolean> =
        getSafePreferencesFlow(context).map { prefs ->
            prefs[KEY_DARK_MODE] ?: false
        }

    suspend fun saveDarkMode(context: Context, isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = isDark
        }
    }

    // ── 自动滚屏速度设置 ──
    fun getAutoScrollSpeedFlow(context: Context): Flow<Float> =
        getSafePreferencesFlow(context).map { prefs ->
            prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED
        }

    suspend fun saveAutoScrollSpeed(context: Context, speed: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_SCROLL_SPEED] = speed.coerceIn(10f, 200f)
        }
    }

    // ── 独立亮度设置 ──
    fun getAppBrightnessFlow(context: Context): Flow<Float> =
        getSafePreferencesFlow(context).map { prefs ->
            prefs[KEY_APP_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS
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
            val currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]).toMutableList()
            val existingIdx = currentList.indexOfFirst { it.uriString == uriStr }
            val title = if (existingIdx >= 0) currentList[existingIdx].title else getFileName(context, uri)
            val total = if (totalChars > 0) totalChars else (if (existingIdx >= 0) currentList[existingIdx].totalChars else 0)
            val chapter = if (chapterTitle.isNotEmpty()) chapterTitle else (if (existingIdx >= 0) currentList[existingIdx].lastChapterTitle else "")

            val isPinned = if (existingIdx >= 0) currentList[existingIdx].isPinned else false

            val updatedItem = BookItem(
                uriString = uriStr,
                title = title,
                charOffset = charOffset,
                totalChars = total,
                lastChapterTitle = chapter,
                lastReadTime = System.currentTimeMillis(),
                isPinned = isPinned
            )

            if (existingIdx >= 0) {
                currentList[existingIdx] = updatedItem
            } else {
                currentList.add(0, updatedItem)
            }
            prefs[KEY_BOOK_SHELF] = serializeBookShelf(currentList)
        }
    }

    suspend fun loadReadingPosition(context: Context): Pair<Uri, Int>? {
        val prefs = getSafePreferencesFlow(context).first()
        val uriStr = prefs[KEY_LAST_URI] ?: return null
        val offset = prefs[KEY_LAST_CHAR_OFFSET] ?: 0
        return try {
            Uri.parse(uriStr) to offset
        } catch (_: Exception) {
            null
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
    fun getBookShelfFlow(context: Context): Flow<List<BookItem>> =
        getSafePreferencesFlow(context).map { prefs ->
            parseBookShelf(prefs[KEY_BOOK_SHELF])
        }

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
            val currentList = parseBookShelf(prefs[KEY_BOOK_SHELF]).toMutableList()
            val existingIdx = currentList.indexOfFirst { it.uriString == uriStr }
            val title = if (existingIdx >= 0) currentList[existingIdx].title else getFileName(context, uri)
            val total = if (totalChars > 0) totalChars else (if (existingIdx >= 0) currentList[existingIdx].totalChars else 0)
            val chapter = if (chapterTitle.isNotEmpty()) chapterTitle else (if (existingIdx >= 0) currentList[existingIdx].lastChapterTitle else "")
            val isPinned = if (existingIdx >= 0) currentList[existingIdx].isPinned else false

            val updatedItem = BookItem(
                uriString = uriStr,
                title = title,
                charOffset = charOffset,
                totalChars = total,
                lastChapterTitle = chapter,
                lastReadTime = System.currentTimeMillis(),
                isPinned = isPinned
            )

            if (existingIdx >= 0) {
                currentList[existingIdx] = updatedItem
            } else {
                currentList.add(0, updatedItem)
            }

            prefs[KEY_BOOK_SHELF] = serializeBookShelf(currentList)
            resultList = currentList.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })
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
                        isPinned = obj.optBoolean("pinned", false)
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
