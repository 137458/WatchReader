package com.watchreader

import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * 中文宋体字面解析器
 *
 * 本 ROM 的 <family name="serif"> 只登记了拉丁字体（NotoSerif-*.ttf），且 OPPO 在
 * fonts.xml 的 WEAROS_EDIT 段把 <family lang="zh-Hans"> 的中文回退整体换成 OPlusSans3
 * 黑体，并删除了 AOSP 原有的 NotoSerifCJK fallbackFor="serif" 条目（仅 ja/ko 保留）。
 * 因此 Typeface.SERIF 对汉字与黑体完全同形，必须显式加载中文字形齐备的宋体面。
 */
object CjkSerifFont {

    private const val TAG = "CjkSerifFont"

    /**
     * 候选字面，按简中可用性排序：
     * 具名简中宋体优先；NotoSerifCJK-Regular.ttc 在 API 30 上无法指定 face 索引
     * （setTtcIndex 需 API 34），其 face 0 为日文形体，只作末位兜底。
     */
    private val CANDIDATES = arrayOf(
        "/system/fonts/NotoSerifCJKsc-Regular.otf",
        "/system/fonts/NotoSerifSC-Regular.otf",
        "/system/fonts/SourceHanSerifCN-Regular.otf",
        "/system/fonts/SourceHanSerifSC-Regular.otf",
        "/system/fonts/NotoSerifCJK-Regular.ttc"
    )

    @Volatile
    private var resolved: Typeface? = null

    @Volatile
    private var attempted = false

    /** 已解析到的中文宋体字面；预热尚未完成或设备上不可用时返回 null，绝不在调用线程同步加载 */
    fun resolvedOrNull(): Typeface? = resolved

    /** 供后台线程预热，避免首次切换字体时把 20MB 级字面的加载开销压给主线程 */
    fun preload() {
        ensureResolved()
    }

    @Synchronized
    private fun ensureResolved() {
        if (attempted) return
        attempted = true

        val present = ArrayList<String>(CANDIDATES.size)
        for (path in CANDIDATES) {
            val file = File(path)
            if (!file.isFile) continue
            present.add(path)
            val face = loadOrNull(file)
            if (face != null) {
                resolved = face
                Log.i(TAG, "中文宋体已加载: $path (在场候选=$present)")
                return
            }
            Log.w(TAG, "字面在场但不可加载 (canRead=${file.canRead()}): $path")
        }
        // 在场候选为空即说明 app 域无法 stat/open /system/fonts，属回退路径
        Log.w(TAG, "无可用中文宋体字面，衬线退回 Typeface.SERIF（汉字与黑体同形）; 在场候选=$present")
    }

    private fun loadOrNull(file: File): Typeface? = try {
        if (!file.canRead()) null else Typeface.createFromFile(file)
    } catch (_: Exception) {
        null
    }
}
