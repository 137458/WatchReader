package com.watchreader

import android.content.Context
import android.net.Uri
import android.util.Log
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
    val themeMode: Int = 0, // 0: 浅色, 1: 深色（历史值由 ThemeMode.fromValue 归一）
    val tapPageArea: Int = 0, // 0: 上下翻页, 1: 左右翻页, 2: 关闭点按
    val cleanTypography: Boolean = true,
    val fontType: Int = 0, // 0: 黑体, 1: 宋体/衬线
    val readDurationSec: Long = 0L,
    val readDays: Map<String, Long> = emptyMap(), // 每日阅读秒数（本地日期键）
    val readGoalMinutes: Int = 0, // 每日目标分钟，0=关闭
    val readGoalCelebrated: String = "", // 最近庆祝的日期键
    val lineSpacing: Int = 1, // LineSpacingMode.STANDARD.value
    val letterSpacing: Int = 0 // LetterSpacingMode.STANDARD.value
)

/**
 * DataStore 持久化管理器（协程与 Flow 响应式驱动）
 */
object DataStoreManager {

    private const val TAG = "DataStoreManager"

    /** 书签键名前缀：键名格式 bookmarks_<uriString.hashCode()> 是历史落盘格式，改动会让老用户书签全部失效 */
    internal const val BOOKMARK_KEY_PREFIX = "bookmarks_"

    val KEY_LAST_URI = stringPreferencesKey("last_uri")
    val KEY_LAST_CHAR_OFFSET = intPreferencesKey("last_char_offset")
    val KEY_LAST_SCREEN = stringPreferencesKey("last_screen") // "home" 或 "reader"
    val KEY_FONT_SIZE = intPreferencesKey("font_size")
    val KEY_BOOK_SHELF = stringPreferencesKey("book_shelf_json")
    val KEY_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    val KEY_AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
    val KEY_APP_BRIGHTNESS = floatPreferencesKey("app_brightness") // -1.0f: 跟随系统, 0.01f ~ 1.0f: 自定义亮度
    val KEY_THEME_MODE = intPreferencesKey("theme_mode") // 0: 浅色, 1: 深色（历史值归一）
    val KEY_TAP_PAGE_AREA = intPreferencesKey("tap_page_area") // 0: 上下翻页, 1: 左右翻页, 2: 关闭点按
    val KEY_CLEAN_TYPOGRAPHY = booleanPreferencesKey("clean_typography") // 智能排版净化
    val KEY_FONT_TYPE = intPreferencesKey("font_type") // 0: 黑体, 1: 宋体/衬线
    val KEY_READ_DURATION_SEC = longPreferencesKey("read_duration_sec") // 累计阅读时长
    val KEY_READ_DAYS_JSON = stringPreferencesKey("read_days_json") // 每日阅读秒数 {"yyyy-MM-dd":sec}
    val KEY_READ_GOAL_MINUTES = intPreferencesKey("read_goal_minutes") // 每日阅读目标（分钟），0=关闭
    val KEY_READ_GOAL_CELEBRATED = stringPreferencesKey("read_goal_celebrated") // 最近一次目标达成庆祝的日期键
    val KEY_LINE_SPACING = intPreferencesKey("line_spacing") // 行距档位（LineSpacingMode）
    val KEY_LETTER_SPACING = intPreferencesKey("letter_spacing") // 字距档位（LetterSpacingMode）

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
        val autoScrollSpeed = AutoScrollSpeed.fromStored(prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED)
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
        // 主题是配色的唯一状态源；KEY_DARK_MODE 仅作旧版遗留数据的迁移输入（老版本只写这个键）。
        // 历史五档值在此统一归一为 Material 3 亮 / 暗双档（ADR-018）
        val themeMode = ThemeMode.fromValue(
            prefs[KEY_THEME_MODE] ?: (if (prefs[KEY_DARK_MODE] == true) 1 else 0)
        ).value
        val tapPageArea = prefs[KEY_TAP_PAGE_AREA] ?: 0
        val cleanTypography = prefs[KEY_CLEAN_TYPOGRAPHY] ?: true
        val fontType = prefs[KEY_FONT_TYPE] ?: 0
        val readDurationSec = prefs[KEY_READ_DURATION_SEC] ?: 0L
        val readDays = parseReadDays(prefs[KEY_READ_DAYS_JSON])
        val readGoalMinutes = prefs[KEY_READ_GOAL_MINUTES] ?: 0
        val readGoalCelebrated = prefs[KEY_READ_GOAL_CELEBRATED] ?: ""
        val lineSpacing = prefs[KEY_LINE_SPACING] ?: LineSpacingMode.STANDARD.value
        val letterSpacing = prefs[KEY_LETTER_SPACING] ?: LetterSpacingMode.STANDARD.value

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
            readGoalCelebrated = readGoalCelebrated,
            lineSpacing = lineSpacing,
            letterSpacing = letterSpacing
        )
    }

    suspend fun saveThemeMode(context: Context, mode: Int) {
        val normalized = ThemeMode.fromValue(mode).value
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = normalized
            prefs[KEY_DARK_MODE] = (normalized != ThemeMode.LIGHT.value)
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

    suspend fun saveLineSpacing(context: Context, mode: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LINE_SPACING] = LineSpacingMode.fromValue(mode).value
        }
    }

    suspend fun saveLetterSpacing(context: Context, mode: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LETTER_SPACING] = LetterSpacingMode.fromValue(mode).value
        }
    }

    // ── 备份与恢复（单文件 JSON 全量快照，键域见 BackupPayload） ──

    /** 导出全部数据：书架 + 书签 + 阅读统计 + 显示偏好 */
    suspend fun exportBackupJson(context: Context): String {
        val prefs = getSafePreferencesFlow(context).first()
        val shelf = parseBookShelf(prefs[KEY_BOOK_SHELF])
        val bookmarks = shelf.associate { it.uriString to loadBookmarks(context, it.uriString) }
            .filterValues { it.isNotEmpty() }
        return BackupCodec.encode(
            BackupPayload(
                shelf = shelf,
                bookmarks = bookmarks,
                totalReadSec = prefs[KEY_READ_DURATION_SEC] ?: 0L,
                readDays = parseReadDays(prefs[KEY_READ_DAYS_JSON]),
                fontSize = prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE,
                themeMode = ThemeMode.fromValue(
                    prefs[KEY_THEME_MODE] ?: (if (prefs[KEY_DARK_MODE] == true) 1 else 0)
                ).value,
                autoScrollSpeed = prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED,
                appBrightness = prefs[KEY_APP_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS,
                tapPageArea = prefs[KEY_TAP_PAGE_AREA] ?: 0,
                cleanTypography = prefs[KEY_CLEAN_TYPOGRAPHY] ?: true,
                fontType = prefs[KEY_FONT_TYPE] ?: 0,
                lineSpacing = prefs[KEY_LINE_SPACING] ?: LineSpacingMode.STANDARD.value,
                letterSpacing = prefs[KEY_LETTER_SPACING] ?: LetterSpacingMode.STANDARD.value,
                readGoalMinutes = prefs[KEY_READ_GOAL_MINUTES] ?: 0
            ),
            System.currentTimeMillis()
        )
    }

    /**
     * 从备份恢复全部数据
     * @return 恢复的书籍数；JSON 非法 / 类型或版本不符返回 -1（不写入任何键）
     */
    suspend fun restoreBackup(context: Context, json: String): Int {
        val payload = BackupCodec.decode(json) ?: return -1
        context.dataStore.edit { prefs ->
            prefs[KEY_BOOK_SHELF] = serializeBookShelf(payload.shelf)
            prefs[KEY_READ_DURATION_SEC] = payload.totalReadSec
            prefs[KEY_READ_DAYS_JSON] = serializeReadDays(payload.readDays)
            prefs[KEY_FONT_SIZE] = payload.fontSize
            val restoredTheme = ThemeMode.fromValue(payload.themeMode).value
            prefs[KEY_THEME_MODE] = restoredTheme
            prefs[KEY_DARK_MODE] = restoredTheme != ThemeMode.LIGHT.value
            prefs[KEY_AUTO_SCROLL_SPEED] = AutoScrollSpeed.fromStored(payload.autoScrollSpeed)
            prefs[KEY_APP_BRIGHTNESS] = payload.appBrightness
            prefs[KEY_TAP_PAGE_AREA] = payload.tapPageArea
            prefs[KEY_CLEAN_TYPOGRAPHY] = payload.cleanTypography
            prefs[KEY_FONT_TYPE] = payload.fontType
            prefs[KEY_LINE_SPACING] = payload.lineSpacing
            prefs[KEY_LETTER_SPACING] = payload.letterSpacing
            prefs[KEY_READ_GOAL_MINUTES] = payload.readGoalMinutes
            // 当前阅读位置若指向备份中不存在的书，一并清除避免恢复后跳进未知书
            val shelfUris = payload.shelf.map { it.uriString }.toSet()
            val lastUri = prefs[KEY_LAST_URI]
            if (lastUri != null && lastUri !in shelfUris) {
                prefs.remove(KEY_LAST_URI)
                prefs.remove(KEY_LAST_CHAR_OFFSET)
                prefs[KEY_LAST_SCREEN] = "home"
            }
            // 书签键与上述设置同事务写回：此前每本书各自一个事务，中途失败会留下
            // 「设置已恢复、书签只恢复一半」的半状态，且单本失败无人感知
            val restoredBookmarkUris = HashSet<String>()
            for ((uri, list) in payload.bookmarks) {
                if (list.isEmpty()) continue
                restoredBookmarkUris.add(uri)
                prefs[getBookmarkKey(uri)] = serializeBookmarks(list)
            }
            // 整体恢复语义：快照之外的历史书签键一并清理，避免已删的书把书签永久留在键域里
            for (name in staleBookmarkKeyNames(prefs.asMap().keys.map { it.name }, restoredBookmarkUris)) {
                prefs.remove(stringPreferencesKey(name))
            }
        }
        return payload.shelf.size
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
            // 取值域收敛到 AutoScrollSpeed：此前持久层 10..200、阅读页 15..200、自动滚屏 15..250 三处各钳一套
            prefs[KEY_AUTO_SCROLL_SPEED] = AutoScrollSpeed.clamp(speed)
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

            // 单次原子事务同步更新书架记录，消除二次磁盘 I/O 写入。
            // 统一走 rewriteShelf：写回时保留无法识别的历史条目，避免一次进度保存就把整段数据洗掉
            prefs[KEY_BOOK_SHELF] = rewriteShelf(prefs[KEY_BOOK_SHELF]) { current ->
                mergeBookEntry(
                    currentList = current,
                    uriStr = uriStr,
                    charOffset = charOffset,
                    totalChars = totalChars,
                    chapterTitle = chapterTitle,
                    fallbackTitle = { getFileName(context, uri) },
                    nowMs = System.currentTimeMillis()
                )
            }
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
            var merged: List<BookItem> = emptyList()
            prefs[KEY_BOOK_SHELF] = rewriteShelf(prefs[KEY_BOOK_SHELF]) { current ->
                mergeBookEntry(
                    currentList = current,
                    uriStr = uriStr,
                    charOffset = charOffset,
                    totalChars = totalChars,
                    chapterTitle = chapterTitle,
                    fallbackTitle = { getFileName(context, uri) },
                    nowMs = System.currentTimeMillis()
                ).also { merged = it }
            }
            resultList = sortBooks(merged)
        }
        return resultList
    }

    suspend fun toggleBookPin(context: Context, uriString: String): List<BookItem> {
        var resultList: List<BookItem> = emptyList()
        context.dataStore.edit { prefs ->
            var updated: List<BookItem> = emptyList()
            prefs[KEY_BOOK_SHELF] = rewriteShelf(prefs[KEY_BOOK_SHELF]) { current ->
                updated = current.map { if (it.uriString == uriString) it.copy(isPinned = !it.isPinned) else it }
                updated
            }
            resultList = sortBooks(updated)
        }
        return resultList
    }

    suspend fun removeBookFromShelf(context: Context, uriString: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BOOK_SHELF] = rewriteShelf(prefs[KEY_BOOK_SHELF]) { current ->
                current.filter { it.uriString != uriString }
            }
            // 书签键随书删除：此前只删书架条目与 last_uri，书签键永久残留在动态键域里
            prefs.remove(stringPreferencesKey(bookmarkKeyName(uriString)))
            if (prefs[KEY_LAST_URI] == uriString) {
                prefs.remove(KEY_LAST_URI)
                prefs.remove(KEY_LAST_CHAR_OFFSET)
                prefs[KEY_LAST_SCREEN] = "home"
            }
        }
    }

    fun serializeBookShelf(list: List<BookItem>): String {
        val array = JSONArray()
        for (item in list) array.put(bookItemJson(item))
        return array.toString()
    }

    /** 写回时保留未识别条目：坏条目的原始 JSON 原样回填，避免一次写盘把用户书架洗成空 */
    internal fun serializeBookShelfPreserving(list: List<BookItem>, unparsedRaw: List<String>): String {
        val array = JSONArray()
        for (item in list) array.put(bookItemJson(item))
        for (raw in unparsedRaw) {
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
            if (obj == null) continue
            array.put(obj)
        }
        return array.toString()
    }

    private fun bookItemJson(item: BookItem): JSONObject = JSONObject().apply {
        put("uri", item.uriString)
        put("title", item.title)
        put("offset", item.charOffset)
        put("total", item.totalChars)
        put("chapter", item.lastChapterTitle)
        put("time", item.lastReadTime)
        put("pinned", item.isPinned)
        put("finished", item.finished)
    }

    private fun bookItemOf(obj: JSONObject): BookItem = BookItem(
        uriString = obj.getString("uri"),
        title = obj.optString("title", "未命名小说"),
        charOffset = obj.optInt("offset", 0),
        totalChars = obj.optInt("total", 0),
        lastChapterTitle = obj.optString("chapter", ""),
        lastReadTime = obj.optLong("time", 0L),
        isPinned = obj.optBoolean("pinned", false),
        finished = obj.optBoolean("finished", false)
    )

    /** 书架解析结果：可识别条目 + 未识别条目的原始 JSON（写回时原样保留，不再静默丢失） */
    internal data class ShelfSnapshot(val items: List<BookItem>, val unparsedRaw: List<String>)

    internal fun parseBookShelfSnapshot(jsonStr: String?): ShelfSnapshot {
        if (jsonStr.isNullOrEmpty()) return ShelfSnapshot(emptyList(), emptyList())
        val array = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "书架 JSON 整体损坏，按空书架处理", e)
            return ShelfSnapshot(emptyList(), emptyList())
        }
        val items = ArrayList<BookItem>(array.length())
        val unparsed = ArrayList<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                Log.w(TAG, "书架第 $i 条不是 JSON 对象，已跳过")
                continue
            }
            try {
                items.add(bookItemOf(obj))
            } catch (e: Exception) {
                // 单条坏数据只丢自己，不再截断后续条目；原文留给写回时回填
                unparsed.add(obj.toString())
                Log.w(TAG, "书架第 $i 条损坏，已保留原文待回填", e)
            }
        }
        return ShelfSnapshot(sortBooks(items), unparsed)
    }

    fun parseBookShelf(jsonStr: String?): List<BookItem> = parseBookShelfSnapshot(jsonStr).items

    /** 书架「读-改-写」唯一入口：四个写方共用，写回时保留未识别条目 */
    internal fun rewriteShelf(currentJson: String?, transform: (List<BookItem>) -> List<BookItem>): String {
        val snapshot = parseBookShelfSnapshot(currentJson)
        return serializeBookShelfPreserving(transform(snapshot.items), snapshot.unparsedRaw)
    }

    internal fun sortBooks(list: List<BookItem>): List<BookItem> =
        list.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })

    // ── 书签管理 ──

    /** 书签键名：保持历史落盘格式 bookmarks_<uriString.hashCode()>，改格式等于让老用户书签全部失效 */
    internal fun bookmarkKeyName(uriString: String): String = "$BOOKMARK_KEY_PREFIX${uriString.hashCode()}"

    private fun getBookmarkKey(uriString: String): Preferences.Key<String> =
        stringPreferencesKey(bookmarkKeyName(uriString))

    /** 现存键名中属于「已不在保留集合里的书」的书签键（删书与整体恢复共用） */
    internal fun staleBookmarkKeyNames(
        existingKeyNames: Collection<String>,
        keepUris: Collection<String>
    ): List<String> {
        val keep = keepUris.mapTo(HashSet()) { bookmarkKeyName(it) }
        return existingKeyNames.filter { it.startsWith(BOOKMARK_KEY_PREFIX) && it !in keep }
    }

    suspend fun loadBookmarks(context: Context, uriString: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        val prefs = getSafePreferencesFlow(context).first()
        // 解析只有一份实现：此前 load 与 parse 各写一套（id 随机 UUID vs 时间默认 0L），读回值随入口漂移
        return parseBookmarks(prefs[getBookmarkKey(uriString)])
    }

    /** 同章 10 字符内视为同一位置，避免同一处反复加书签 */
    private fun isSameBookmarkPosition(a: Bookmark, b: Bookmark): Boolean =
        a.chapterIndex == b.chapterIndex && Math.abs(a.charOffset - b.charOffset) < 10

    suspend fun saveBookmark(context: Context, uriString: String, bookmark: Bookmark): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var resultList: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            val key = getBookmarkKey(uriString)
            prefs[key] = rewriteBookmarks(prefs[key]) { current ->
                val list = current.filter { !isSameBookmarkPosition(it, bookmark) }.toMutableList()
                list.add(0, bookmark)
                list.also { resultList = it }
            }
        }
        return resultList
    }

    suspend fun removeBookmark(context: Context, uriString: String, bookmarkId: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var resultList: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            val key = getBookmarkKey(uriString)
            prefs[key] = rewriteBookmarks(prefs[key]) { current ->
                current.filter { it.id != bookmarkId }.also { resultList = it }
            }
        }
        return resultList
    }

    /** 书签「读-改-写」唯一入口 */
    internal fun rewriteBookmarks(currentJson: String?, transform: (List<Bookmark>) -> List<Bookmark>): String =
        serializeBookmarks(transform(parseBookmarks(currentJson)))

    internal fun serializeBookmarks(list: List<Bookmark>): String {
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

    internal fun parseBookmarks(jsonStr: String?): List<Bookmark> {
        if (jsonStr.isNullOrEmpty()) return emptyList()
        val array = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "书签 JSON 整体损坏，按空书签处理", e)
            return emptyList()
        }
        val list = ArrayList<Bookmark>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val chapterIndex = obj.optInt("chapterIndex", 0)
            val charOffset = obj.optInt("charOffset", 0)
            list.add(
                Bookmark(
                    // 缺失 id 时按内容稳定派生：旧实现每次读回随机 UUID，删除指定书签会在两次读取之间指向不存在的 id
                    id = obj.optString("id", "").ifEmpty { stableBookmarkId(chapterIndex, charOffset) },
                    chapterIndex = chapterIndex,
                    chapterTitle = obj.optString("chapterTitle", ""),
                    charOffset = charOffset,
                    snippet = obj.optString("snippet", ""),
                    time = obj.optLong("time", 0L)
                )
            )
        }
        return list.sortedByDescending { it.time }
    }

    /** 缺失 id 的书签按位置派生稳定标识，保证同一书签每次读回都是同一个 id */
    internal fun stableBookmarkId(chapterIndex: Int, charOffset: Int): String = "bm-$chapterIndex-$charOffset"
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
