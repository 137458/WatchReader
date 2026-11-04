package com.watchreader

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
 * 磁盘损坏（IOException）时退化为空快照：读失败不应让整个冷启动崩溃。
 * 各领域 store 与门面共用这一份读取口径，避免每处各写一套 catch。
 */
internal fun Context.safePreferencesFlow(): Flow<Preferences> =
    dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

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
 * 持久化门面：UI 与 ViewModel 只认这个 object，领域实现拆在
 * [ShelfStore] / [BookmarkStore] / [ReadingSettingsStore] / [ReadingStatsStore] / [BackupService]。
 *
 * 门面存在的理由不是转发本身，而是让调用方不必知道「一条数据属于哪个领域、
 * 哪个键、事务边界在哪」——拆领域换实现时调用方零改动。
 */
object DataStoreManager {

    /**
     * 单次 I/O 批量读取全部核心配置（用于 Application / ViewModel 冷启动智能恢复）
     */
    suspend fun loadInitialConfig(context: Context): AppInitialConfig {
        val prefs = context.safePreferencesFlow().first()
        val settings = ReadingSettingsStore.read(prefs)
        val stats = ReadingStatsStore.read(prefs)
        val position = ShelfStore.readPosition(prefs)
        return AppInitialConfig(
            fontSize = settings.fontSize,
            autoScrollSpeed = settings.autoScrollSpeed,
            appBrightness = settings.appBrightness,
            bookshelf = ShelfStore.readShelf(prefs),
            lastScreen = position.lastScreen,
            lastUri = parseUriOrNull(position.uriString),
            lastCharOffset = position.charOffset,
            themeMode = settings.themeMode,
            tapPageArea = settings.tapPageArea,
            cleanTypography = settings.cleanTypography,
            fontType = settings.fontType,
            readDurationSec = stats.totalSec,
            readDays = stats.days,
            readGoalMinutes = stats.goalMinutes,
            readGoalCelebrated = stats.celebrated,
            lineSpacing = settings.lineSpacing,
            letterSpacing = settings.letterSpacing
        )
    }

    private fun parseUriOrNull(value: String?): Uri? {
        if (value.isNullOrEmpty()) return null
        return try {
            Uri.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    // ── 显示与阅读偏好 ──

    suspend fun saveThemeMode(context: Context, mode: Int) = ReadingSettingsStore.saveThemeMode(context, mode)

    suspend fun saveTapPageArea(context: Context, area: Int) = ReadingSettingsStore.saveTapPageArea(context, area)

    suspend fun saveCleanTypography(context: Context, enabled: Boolean) =
        ReadingSettingsStore.saveCleanTypography(context, enabled)

    suspend fun saveFontType(context: Context, type: Int) = ReadingSettingsStore.saveFontType(context, type)

    suspend fun saveFontSize(context: Context, size: Int) = ReadingSettingsStore.saveFontSize(context, size)

    suspend fun saveLineSpacing(context: Context, mode: Int) = ReadingSettingsStore.saveLineSpacing(context, mode)

    suspend fun saveLetterSpacing(context: Context, mode: Int) = ReadingSettingsStore.saveLetterSpacing(context, mode)

    suspend fun saveAutoScrollSpeed(context: Context, speed: Float) =
        ReadingSettingsStore.saveAutoScrollSpeed(context, speed)

    suspend fun saveAppBrightness(context: Context, brightness: Float) =
        ReadingSettingsStore.saveAppBrightness(context, brightness)

    // ── 阅读统计 ──

    suspend fun saveReadDurationSec(context: Context, seconds: Long) =
        ReadingStatsStore.saveTotalSec(context, seconds)

    suspend fun addReadingSeconds(context: Context, seconds: Long, todayKey: String) =
        ReadingStatsStore.addSeconds(context, seconds, todayKey)

    suspend fun setReadGoalMinutes(context: Context, minutes: Int) = ReadingStatsStore.setGoalMinutes(context, minutes)

    suspend fun saveReadGoalCelebrated(context: Context, dateKey: String) =
        ReadingStatsStore.saveGoalCelebrated(context, dateKey)

    // ── 阅读位置与书架 ──

    suspend fun saveReadingPosition(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ) = ShelfStore.saveProgress(context, uri, charOffset, totalChars, chapterTitle)

    suspend fun clearReadingPosition(context: Context) = ShelfStore.clearPosition(context)

    suspend fun saveLastScreen(context: Context, screenName: String) = ShelfStore.saveLastScreen(context, screenName)

    suspend fun loadBookShelf(context: Context): List<BookItem> = ShelfStore.load(context)

    suspend fun updateBookInShelf(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ): List<BookItem> = ShelfStore.updateEntry(context, uri, charOffset, totalChars, chapterTitle)

    suspend fun toggleBookPin(context: Context, uriString: String): List<BookItem> =
        ShelfStore.togglePin(context, uriString)

    suspend fun removeBookFromShelf(context: Context, uriString: String) = ShelfStore.remove(context, uriString)

    // ── 书签 ──

    suspend fun loadBookmarks(context: Context, uriString: String): List<Bookmark> =
        BookmarkStore.load(context, uriString)

    suspend fun saveBookmark(context: Context, uriString: String, bookmark: Bookmark): List<Bookmark> =
        BookmarkStore.add(context, uriString, bookmark)

    suspend fun removeBookmark(context: Context, uriString: String, bookmarkId: String): List<Bookmark> =
        BookmarkStore.remove(context, uriString, bookmarkId)

    // ── 备份与恢复 ──

    suspend fun exportBackupJson(context: Context): String = BackupService.exportJson(context)

    suspend fun restoreBackup(context: Context, json: String): Int = BackupService.restore(context, json)
}
