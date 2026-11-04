package com.watchreader

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * 备份与恢复服务：把四个领域 store 的快照拼成一个 JSON 文件，恢复时一次事务写回四个领域。
 *
 * 这里只依赖 store 的事务级核心与 [BackupCodec] 的格式定义，
 * 不碰任何 Preferences 键名——键名变更不会传导到备份格式，反之亦然。
 *
 * 跨域单事务是刚需：此前每本书各自一个事务，中途失败会留下
 * 「设置已恢复、书签只恢复一半」的半状态，且单本失败无人感知。
 */
object BackupService {

    /** 导出全部数据：书架 + 书签 + 阅读统计 + 显示偏好 */
    suspend fun exportJson(context: Context): String {
        val prefs = context.safePreferencesFlow().first()
        val shelf = ShelfStore.readShelf(prefs)
        val settings = ReadingSettingsStore.read(prefs)
        val stats = ReadingStatsStore.read(prefs)
        val bookmarks = LinkedHashMap<String, List<Bookmark>>()
        for (book in shelf) {
            // 空 URI 不会出现在正常书架里，跳过以免造出反查不到的键
            if (book.uriString.isEmpty()) continue
            val list = BookmarkStore.read(prefs, book.uriString)
            if (list.isNotEmpty()) bookmarks[book.uriString] = list
        }
        return BackupCodec.encode(
            BackupPayload(
                shelf = shelf,
                bookmarks = bookmarks,
                totalReadSec = stats.totalSec,
                readDays = stats.days,
                fontSize = settings.fontSize,
                themeMode = settings.themeMode,
                autoScrollSpeed = settings.autoScrollSpeed,
                appBrightness = settings.appBrightness,
                tapPageArea = settings.tapPageArea,
                cleanTypography = settings.cleanTypography,
                fontType = settings.fontType,
                lineSpacing = settings.lineSpacing,
                letterSpacing = settings.letterSpacing,
                readGoalMinutes = stats.goalMinutes
            ),
            System.currentTimeMillis()
        )
    }

    /**
     * 从备份恢复全部数据（四领域同一事务）。
     * @return 恢复的书籍数；JSON 非法 / 类型或版本不符返回 -1（不写入任何键）
     */
    suspend fun restore(context: Context, json: String): Int {
        val payload = BackupCodec.decode(json) ?: return -1
        context.dataStore.edit { prefs ->
            ShelfStore.replaceShelf(prefs, payload.shelf)
            ReadingStatsStore.writeRestore(
                prefs = prefs,
                totalSec = payload.totalReadSec,
                days = payload.readDays,
                goalMinutes = payload.readGoalMinutes
            )
            ReadingSettingsStore.writeRestore(
                prefs = prefs,
                settings = ReadingSettingsStore.Settings(
                    fontSize = payload.fontSize,
                    autoScrollSpeed = payload.autoScrollSpeed,
                    appBrightness = payload.appBrightness,
                    themeMode = payload.themeMode,
                    tapPageArea = payload.tapPageArea,
                    cleanTypography = payload.cleanTypography,
                    fontType = payload.fontType,
                    lineSpacing = payload.lineSpacing,
                    letterSpacing = payload.letterSpacing
                )
            )
            ShelfStore.prunePositionNotIn(prefs, payload.shelf.map { it.uriString }.toSet())

            val restoredBookmarkUris = LinkedHashSet<String>()
            for ((uri, list) in payload.bookmarks) {
                if (list.isEmpty()) continue
                restoredBookmarkUris.add(uri)
                BookmarkStore.applyReplace(prefs, uri, list)
            }
            // 整体恢复语义：快照之外的历史书签键一并清理，避免已删的书把书签永久留在键域里
            BookmarkStore.dropStaleKeys(prefs, restoredBookmarkUris)
        }
        return payload.shelf.size
    }
}
