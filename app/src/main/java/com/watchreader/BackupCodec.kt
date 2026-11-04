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
 *
 * 失败一律返回带错误码的 [DecodeResult.Failure]：结构损坏的备份绝不能被解成空数据，
 * 否则恢复流程会把用户书架与书签整片洗掉（旧实现只看 type/version，shelf 结构坏时静默得到空列表）。
 */
object BackupCodec {
    const val TYPE = "watchreader_backup"
    const val VERSION = 1

    const val ERROR_EMPTY = "empty"
    const val ERROR_MALFORMED_JSON = "malformed_json"
    const val ERROR_WRONG_TYPE = "wrong_type"
    const val ERROR_UNSUPPORTED_VERSION = "unsupported_version"
    const val ERROR_CORRUPT_SHELF = "corrupt_shelf"
    const val ERROR_CORRUPT_BOOKMARKS = "corrupt_bookmarks"

    sealed class DecodeResult {
        data class Success(val payload: BackupPayload) : DecodeResult()
        data class Failure(val code: String, val message: String) : DecodeResult()
    }

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
     * 解码备份并区分失败原因；失败时调用方必须保持现有数据不变
     */
    fun decodeResult(json: String): DecodeResult {
        if (json.isBlank()) return DecodeResult.Failure(ERROR_EMPTY, "备份内容为空")
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return DecodeResult.Failure(ERROR_MALFORMED_JSON, "备份不是合法 JSON")
        }
        if (root.optString("type") != TYPE) {
            return DecodeResult.Failure(ERROR_WRONG_TYPE, "不是 WatchReader 备份文件")
        }
        // 版本只向后兼容：更高版本的结构无法安全解读
        if (root.optInt("version", -1) != VERSION) {
            return DecodeResult.Failure(ERROR_UNSUPPORTED_VERSION, "备份版本不受支持")
        }

        // 书架：缺省视为空书架（历史备份兼容）；存在时必须是对象数组，且不能「一条都解析不出来」
        val rawShelf = root.opt("shelf")
        if (rawShelf != null && rawShelf !== JSONObject.NULL && rawShelf !is JSONArray) {
            return DecodeResult.Failure(ERROR_CORRUPT_SHELF, "书架字段不是数组")
        }
        val shelfArray = rawShelf as? JSONArray
        val shelfSnapshot = DataStoreManager.parseBookShelfSnapshot(shelfArray?.toString())
        if (shelfArray != null && shelfArray.length() > 0 && shelfSnapshot.items.isEmpty()) {
            return DecodeResult.Failure(ERROR_CORRUPT_SHELF, "书架条目全部无法解析")
        }

        // 书签：缺省视为无书签；存在时必须是 [{uri, list:[...]}]，列表损坏即拒绝（不再静默丢弃整本书签）
        val bookmarks = LinkedHashMap<String, List<Bookmark>>()
        val rawBookmarks = root.opt("bookmarks")
        if (rawBookmarks != null && rawBookmarks !== JSONObject.NULL) {
            val bookmarkArray = rawBookmarks as? JSONArray
                ?: return DecodeResult.Failure(ERROR_CORRUPT_BOOKMARKS, "书签字段不是数组")
            for (i in 0 until bookmarkArray.length()) {
                val entry = bookmarkArray.optJSONObject(i)
                    ?: return DecodeResult.Failure(ERROR_CORRUPT_BOOKMARKS, "书签条目不是对象")
                val uri = entry.optString("uri")
                if (uri.isEmpty()) continue
                val rawList = entry.opt("list")
                if (rawList != null && rawList !== JSONObject.NULL && rawList !is JSONArray) {
                    return DecodeResult.Failure(ERROR_CORRUPT_BOOKMARKS, "书签列表不是数组")
                }
                bookmarks[uri] = DataStoreManager.parseBookmarks((rawList as? JSONArray)?.toString())
            }
        }

        val stats = root.optJSONObject("stats") ?: JSONObject()
        val daysObj = stats.optJSONObject("days")
        val settings = root.optJSONObject("settings") ?: JSONObject()

        return DecodeResult.Success(
            BackupPayload(
                shelf = shelfSnapshot.items,
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
        )
    }

    /**
     * 解码备份；失败返回 null。错误原因请用 [decodeResult] 获取
     */
    fun decode(json: String): BackupPayload? = (decodeResult(json) as? DecodeResult.Success)?.payload
}
