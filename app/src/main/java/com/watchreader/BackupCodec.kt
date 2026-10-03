package com.watchreader

import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架备份载荷：与 DataStore 键域一一对应，书签按书籍 URI 分组
 */
data class BackupPayload(
    val shelf: List<BookItem> = emptyList(),
    val bookmarks: Map<String, List<Bookmark>> = emptyMap(),
    val totalReadSec: Long = 0L,
    val readDays: Map<String, Long> = emptyMap(),
    val fontSize: Int = 14,
    val themeMode: Int = 0,
    val autoScrollSpeed: Float = 45f,
    val appBrightness: Float = -1f,
    val tapPageArea: Int = 0,
    val cleanTypography: Boolean = true,
    val fontType: Int = 0,
    val lineSpacing: Int = 1,
    val letterSpacing: Int = 0,
    val readGoalMinutes: Int = 0
)

/**
 * 备份编解码器（纯函数）：书架 + 书签 + 阅读统计 + 显示偏好的单文件 JSON 快照
 *
 * 结构版本化（version），非本应用类型或更高版本的备份拒绝导入；
 * 缺失字段按默认值容错，历史备份可继续导入。
 */
object BackupCodec {
    const val TYPE = "watchreader_backup"
    const val VERSION = 1

    /** 编码为单文件 JSON（[exportedAtMs] 仅记录时间，不参与恢复） */
    fun encode(payload: BackupPayload, exportedAtMs: Long): String {
        val root = JSONObject()
        root.put("type", TYPE)
        root.put("version", VERSION)
        root.put("exportedAt", exportedAtMs)
        // 书架条目复用书架持久化的同一套键名，解码与恢复单一路径
        root.put("shelf", JSONArray(DataStoreManager.serializeBookShelf(payload.shelf)))
        val bookmarkArray = JSONArray()
        for ((uri, list) in payload.bookmarks) {
            if (list.isEmpty()) continue
            bookmarkArray.put(
                JSONObject()
                    .put("uri", uri)
                    .put("list", JSONArray(DataStoreManager.serializeBookmarks(list)))
            )
        }
        root.put("bookmarks", bookmarkArray)
        root.put("stats", JSONObject()
            .put("totalSec", payload.totalReadSec)
            .put("days", JSONObject(DataStoreManager.serializeReadDays(payload.readDays)))
        )
        root.put("settings", JSONObject()
            .put("fontSize", payload.fontSize)
            .put("themeMode", payload.themeMode)
            .put("autoScrollSpeed", payload.autoScrollSpeed.toDouble())
            .put("appBrightness", payload.appBrightness.toDouble())
            .put("tapPageArea", payload.tapPageArea)
            .put("cleanTypography", payload.cleanTypography)
            .put("fontType", payload.fontType)
            .put("lineSpacing", payload.lineSpacing)
            .put("letterSpacing", payload.letterSpacing)
            .put("readGoalMinutes", payload.readGoalMinutes)
        )
        return root.toString()
    }

    /**
     * 解码备份；类型不符 / 版本过高 / 结构损坏返回 null
     */
    fun decode(json: String): BackupPayload? {
        if (json.isBlank()) return null
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (root.optString("type") != TYPE) return null
        // 版本只向后兼容：更高版本的结构无法安全解读
        if (root.optInt("version", -1) != VERSION) return null

        val shelf = DataStoreManager.parseBookShelf(root.optJSONArray("shelf")?.toString())
        val bookmarks = LinkedHashMap<String, List<Bookmark>>()
        val bookmarkArray = root.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bookmarkArray.length()) {
            val entry = bookmarkArray.optJSONObject(i) ?: continue
            val uri = entry.optString("uri")
            if (uri.isEmpty()) continue
            bookmarks[uri] = DataStoreManager.parseBookmarks(entry.optJSONArray("list")?.toString())
        }
        val stats = root.optJSONObject("stats") ?: JSONObject()
        val daysObj = stats.optJSONObject("days")
        val settings = root.optJSONObject("settings") ?: JSONObject()

        return BackupPayload(
            shelf = shelf,
            bookmarks = bookmarks,
            totalReadSec = stats.optLong("totalSec", 0L),
            readDays = daysObj?.let {
                val map = LinkedHashMap<String, Long>()
                val keys = it.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = it.optLong(key, 0L)
                }
                map
            } ?: emptyMap(),
            fontSize = settings.optInt("fontSize", 14),
            themeMode = settings.optInt("themeMode", 0),
            autoScrollSpeed = settings.optDouble("autoScrollSpeed", 45.0).toFloat(),
            appBrightness = settings.optDouble("appBrightness", -1.0).toFloat(),
            tapPageArea = settings.optInt("tapPageArea", 0),
            cleanTypography = settings.optBoolean("cleanTypography", true),
            fontType = settings.optInt("fontType", 0),
            lineSpacing = settings.optInt("lineSpacing", 1),
            letterSpacing = settings.optInt("letterSpacing", 0),
            readGoalMinutes = settings.optInt("readGoalMinutes", 0)
        )
    }
}
