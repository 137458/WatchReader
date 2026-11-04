package com.watchreader

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架领域 store：书架条目、当前阅读位置、首页导航状态。
 *
 * 对外 interface 只回答「书架有什么、进度存一下、置顶翻一下、删一本书」；
 * 键名（book_shelf_json / last_uri ...）与书架 JSON 结构藏在实现内。
 *
 * 事务级核心（internal）接收 DataStore 事务对象，只给两个消费者：
 * 1) 本 store 的 suspend 方法；2) BackupService.restore 的「一次 edit 跨领域原子恢复」。
 * 这是真实接缝——同一份书架序列化实现被「偏好存储」与「备份文件」两个 adapter 消费。
 */
object ShelfStore {

    private const val TAG = "ShelfStore"

    private const val SCREEN_HOME = "home"
    private const val SCREEN_READER = "reader"

    private val KEY_BOOK_SHELF = stringPreferencesKey("book_shelf_json")
    private val KEY_LAST_URI = stringPreferencesKey("last_uri")
    private val KEY_LAST_CHAR_OFFSET = intPreferencesKey("last_char_offset")
    private val KEY_LAST_SCREEN = stringPreferencesKey("last_screen")

    /** 阅读位置与首页导航状态 */
    data class Position(val uriString: String?, val charOffset: Int, val lastScreen: String)

    /** 书架解析结果：可识别条目 + 未识别条目的原始 JSON（写回时原样保留，不再静默丢失） */
    internal data class Snapshot(val items: List<BookItem>, val unparsedRaw: List<String>)

    // ── 对外 interface ──

    suspend fun load(context: Context): List<BookItem> = readShelf(context.safePreferencesFlow().first())

    /**
     * 保存阅读进度：位置键与书架条目同事务落盘。
     * fallbackTitle 只在「这本书首次进书架」时求值，ContentResolver 查询成本不该每次存进度都付。
     */
    suspend fun saveProgress(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ) {
        val uriStr = uri.toString()
        context.dataStore.edit { prefs ->
            val merged = mergeBookEntry(
                currentList = readShelf(prefs),
                uriStr = uriStr,
                charOffset = charOffset,
                totalChars = totalChars,
                chapterTitle = chapterTitle,
                fallbackTitle = { getFileName(context, uri) },
                nowMs = System.currentTimeMillis()
            )
            writeShelf(prefs, merged)
            writePosition(prefs, uriStr, charOffset, SCREEN_READER)
        }
    }

    /** 只更新书架条目（WiFi 上传后重建条目等场景），不动阅读位置 */
    suspend fun updateEntry(
        context: Context,
        uri: Uri,
        charOffset: Int,
        totalChars: Int = 0,
        chapterTitle: String = ""
    ): List<BookItem> {
        val uriStr = uri.toString()
        var result: List<BookItem> = emptyList()
        context.dataStore.edit { prefs ->
            val merged = mergeBookEntry(
                currentList = readShelf(prefs),
                uriStr = uriStr,
                charOffset = charOffset,
                totalChars = totalChars,
                chapterTitle = chapterTitle,
                fallbackTitle = { getFileName(context, uri) },
                nowMs = System.currentTimeMillis()
            )
            writeShelf(prefs, merged)
            result = sortBooks(merged)
        }
        return result
    }

    suspend fun togglePin(context: Context, uriString: String): List<BookItem> {
        var result: List<BookItem> = emptyList()
        context.dataStore.edit { prefs ->
            val updated = readShelf(prefs).map {
                if (it.uriString == uriString) it.copy(isPinned = !it.isPinned) else it
            }
            writeShelf(prefs, updated)
            result = sortBooks(updated)
        }
        return result
    }

    /** 删书是跨领域操作：书架条目、该书书签、指向它的阅读位置必须同事务一起清 */
    suspend fun remove(context: Context, uriString: String) {
        context.dataStore.edit { prefs ->
            writeShelf(prefs, readShelf(prefs).filter { it.uriString != uriString })
            BookmarkStore.clear(prefs, uriString)
            clearPositionIfOn(prefs, uriString)
        }
    }

    suspend fun saveLastScreen(context: Context, screenName: String) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_SCREEN] = screenName }
    }

    suspend fun clearPosition(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_URI)
            prefs.remove(KEY_LAST_CHAR_OFFSET)
            prefs[KEY_LAST_SCREEN] = SCREEN_HOME
        }
    }

    // ── 事务级核心（internal） ──

    internal fun readShelf(prefs: Preferences): List<BookItem> = decodeShelf(prefs[KEY_BOOK_SHELF]).items

    /** 书架写回唯一入口：整表替换条目，未识别的历史条目经 rewriteShelf 原样回填 */
    internal fun writeShelf(prefs: MutablePreferences, items: List<BookItem>) {
        prefs[KEY_BOOK_SHELF] = rewriteShelf(prefs[KEY_BOOK_SHELF]) { items }
    }

    /** 整体替换（备份恢复语义）：快照之外的历史条目不再保留 */
    internal fun replaceShelf(prefs: MutablePreferences, items: List<BookItem>) {
        prefs[KEY_BOOK_SHELF] = encodeShelf(items)
    }

    internal fun readPosition(prefs: Preferences): Position = Position(
        uriString = prefs[KEY_LAST_URI],
        charOffset = prefs[KEY_LAST_CHAR_OFFSET] ?: 0,
        lastScreen = prefs[KEY_LAST_SCREEN] ?: SCREEN_HOME
    )

    internal fun writePosition(prefs: MutablePreferences, uriString: String, charOffset: Int, screen: String) {
        prefs[KEY_LAST_URI] = uriString
        prefs[KEY_LAST_CHAR_OFFSET] = charOffset
        prefs[KEY_LAST_SCREEN] = screen
    }

    /** 恢复后位置若指向备份里不存在的书，清掉避免跳进未知书 */
    internal fun prunePositionNotIn(prefs: MutablePreferences, shelfUris: Set<String>) {
        val lastUri = prefs[KEY_LAST_URI] ?: return
        if (lastUri in shelfUris) return
        prefs.remove(KEY_LAST_URI)
        prefs.remove(KEY_LAST_CHAR_OFFSET)
        prefs[KEY_LAST_SCREEN] = SCREEN_HOME
    }

    internal fun clearPositionIfOn(prefs: MutablePreferences, uriString: String) {
        if (prefs[KEY_LAST_URI] != uriString) return
        prefs.remove(KEY_LAST_URI)
        prefs.remove(KEY_LAST_CHAR_OFFSET)
        prefs[KEY_LAST_SCREEN] = SCREEN_HOME
    }

    // ── 书架 JSON 编解码（单一实现，读、写、备份共用） ──

    internal fun encodeShelf(list: List<BookItem>): String {
        val array = JSONArray()
        for (item in list) array.put(bookItemJson(item))
        return array.toString()
    }

    private fun encodePreserving(list: List<BookItem>, unparsedRaw: List<String>): String {
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

    internal fun decodeShelf(jsonStr: String?): Snapshot {
        if (jsonStr.isNullOrEmpty()) return Snapshot(emptyList(), emptyList())
        val array = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "书架 JSON 整体损坏，按空书架处理", e)
            return Snapshot(emptyList(), emptyList())
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
        return Snapshot(sortBooks(items), unparsed)
    }

    /** 书架「读-改-写」唯一入口 */
    internal fun rewriteShelf(currentJson: String?, transform: (List<BookItem>) -> List<BookItem>): String {
        val snapshot = decodeShelf(currentJson)
        return encodePreserving(transform(snapshot.items), snapshot.unparsedRaw)
    }

    internal fun sortBooks(list: List<BookItem>): List<BookItem> =
        list.sortedWith(compareByDescending<BookItem> { it.isPinned }.thenByDescending { it.lastReadTime })

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
}

/**
 * 书架条目合并核心（纯函数）：保存进度与更新条目共用的唯一实现。
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
