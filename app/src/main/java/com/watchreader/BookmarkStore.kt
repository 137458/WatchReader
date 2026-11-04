package com.watchreader

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书签领域 store：单本书的书签列表。
 *
 * 对外 interface 只回答「这本书有哪些书签、加一个、删一个」，
 * 键名格式（bookmarks_<hash>）与 JSON 结构都是实现细节，调用方拿不到也不需要拿。
 *
 * internal 的事务级核心接收 DataStore 的事务对象，供两类消费者复用同一份序列化实现：
 * 1) 本 store 的 suspend 方法；2) ShelfStore.remove（删书时同事务删书签键）与
 * BackupService.restore（一次 edit 里跨领域原子写）。这是真实接缝：两个 adapter 消费同一份实现。
 */
object BookmarkStore {

    private const val TAG = "BookmarkStore"

    /** 书签键名前缀：键名格式 bookmarks_<uriString.hashCode()> 是历史落盘格式，改格式等于让老用户书签全部失效 */
    internal const val KEY_PREFIX = "bookmarks_"

    // ── 对外 interface ──

    suspend fun load(context: Context, uriString: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        return read(context.safePreferencesFlow().first(), uriString)
    }

    suspend fun add(context: Context, uriString: String, bookmark: Bookmark): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var result: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            result = applyAdd(prefs, uriString, bookmark)
        }
        return result
    }

    suspend fun remove(context: Context, uriString: String, bookmarkId: String): List<Bookmark> {
        if (uriString.isEmpty()) return emptyList()
        var result: List<Bookmark> = emptyList()
        context.dataStore.edit { prefs ->
            result = applyRemove(prefs, uriString, bookmarkId)
        }
        return result
    }

    // ── 事务级核心（internal：只给同模块需要原子跨域写入的调用方） ──

    internal fun keyOf(uriString: String): Preferences.Key<String> = stringPreferencesKey(keyName(uriString))

    /** 书签键名：保持历史落盘格式，改格式等于让老用户书签全部失效 */
    internal fun keyName(uriString: String): String = "$KEY_PREFIX${uriString.hashCode()}"

    internal fun read(prefs: Preferences, uriString: String): List<Bookmark> = decode(prefs[keyOf(uriString)])

    internal fun applyAdd(prefs: MutablePreferences, uriString: String, bookmark: Bookmark): List<Bookmark> =
        apply(prefs, uriString) { current ->
            val next = current.filter { !isSamePosition(it, bookmark) }.toMutableList()
            next.add(0, bookmark)
            next
        }

    internal fun applyRemove(prefs: MutablePreferences, uriString: String, bookmarkId: String): List<Bookmark> =
        apply(prefs, uriString) { current -> current.filter { it.id != bookmarkId } }

    /** 读-改-写核心：解码一次、编码一次，增删书签与备份路径共用 */
    private fun apply(
        prefs: MutablePreferences,
        uriString: String,
        transform: (List<Bookmark>) -> List<Bookmark>
    ): List<Bookmark> {
        val key = keyOf(uriString)
        var result: List<Bookmark> = emptyList()
        prefs[key] = rewrite(prefs[key]) { current ->
            val next = transform(current)
            result = next
            next
        }
        return result
    }

    /** 整段替换（备份恢复语义）：快照里没有的书签不再保留 */
    internal fun applyReplace(prefs: MutablePreferences, uriString: String, list: List<Bookmark>) {
        prefs[keyOf(uriString)] = encode(list)
    }

    internal fun clear(prefs: MutablePreferences, uriString: String) {
        prefs.remove(keyOf(uriString))
    }

    /** 现存键名中属于「已不在保留集合里的书」的书签键（删书与整体恢复共用同一判定） */
    internal fun staleKeyNames(
        existingKeyNames: Collection<String>,
        keepUris: Collection<String>
    ): List<String> {
        val keep = keepUris.mapTo(HashSet()) { keyName(it) }
        return existingKeyNames.filter { it.startsWith(KEY_PREFIX) && it !in keep }
    }

    /** 把快照之外的历史书签键清掉：hashCode 键域无法从别处反查，只能整体比对 */
    internal fun dropStaleKeys(prefs: MutablePreferences, keepUris: Collection<String>) {
        for (name in staleKeyNames(prefs.asMap().keys.map { it.name }, keepUris)) {
            prefs.remove(stringPreferencesKey(name))
        }
    }

    // ── JSON 编解码（单一实现，读写两端共用） ──

    internal fun encode(list: List<Bookmark>): String {
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

    internal fun decode(jsonStr: String?): List<Bookmark> {
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
                    id = obj.optString("id", "").ifEmpty { stableId(chapterIndex, charOffset) },
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

    /** 书签「读-改-写」唯一入口 */
    internal fun rewrite(currentJson: String?, transform: (List<Bookmark>) -> List<Bookmark>): String =
        encode(transform(decode(currentJson)))

    /** 缺失 id 的书签按位置派生稳定标识，保证同一书签每次读回都是同一个 id */
    internal fun stableId(chapterIndex: Int, charOffset: Int): String = "bm-$chapterIndex-$charOffset"

    /** 同章 10 字符内视为同一位置，避免同一处反复加书签 */
    internal fun isSamePosition(a: Bookmark, b: Bookmark): Boolean =
        a.chapterIndex == b.chapterIndex && Math.abs(a.charOffset - b.charOffset) < 10
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
