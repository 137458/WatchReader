package com.watchreader

/**
 * 全文搜索单条命中：全局原始字符偏移可直接用于 goToChapter 精准定位
 */
data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
    val snippet: String
)

/**
 * 书内全文搜索引擎（纯函数）：大小写不敏感逐位匹配 + 居中上下文摘要
 *
 * 坐标换算约定：命中的正文坐标 idx 属于「本章原文切片」坐标域——
 * 全局偏移 = baseCharOffset + bodyStartInRaw + idx。排版净化关闭态的正文
 * 是原文切片去掉标题首行后的后缀（bodyStartInRaw = 被剥除的首行长度），
 * EPUB 路径无映射数组（bodyStartInRaw = 0），同一公式统一两种来源。
 */
object BookSearchEngine {

    /** 全书命中总数上限：防止超长书结果爆炸拖垮列表与内存 */
    const val MAX_RESULTS = 200

    private const val CONTEXT_BEFORE = 14
    private const val CONTEXT_AFTER = 30

    /**
     * 在单章正文中查找全部命中（大小写不敏感，命中按出现顺序排列）
     * @param maxHits 本次最多返回的命中数（全书扫描时递减传入以截断总量）
     */
    fun findMatches(
        body: String,
        query: String,
        bodyStartInRaw: Int,
        baseCharOffset: Int,
        chapterIndex: Int,
        chapterTitle: String,
        maxHits: Int = MAX_RESULTS
    ): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty() || body.isEmpty() || maxHits <= 0) return emptyList()
        val hits = ArrayList<SearchHit>()
        var from = 0
        while (hits.size < maxHits) {
            val idx = indexOfIgnoreCase(body, q, from)
            if (idx < 0) break
            hits.add(
                SearchHit(
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    charOffset = baseCharOffset + bodyStartInRaw + idx,
                    snippet = buildSnippet(body, idx, idx + q.length)
                )
            )
            from = idx + q.length
        }
        return hits
    }

    /** 大小写不敏感的子串查找（regionMatches 逐位比较，不做整串小写副本） */
    fun indexOfIgnoreCase(text: String, pattern: String, from: Int = 0): Int {
        if (pattern.isEmpty()) return -1
        val lastStart = text.length - pattern.length
        var i = maxOf(from, 0)
        while (i <= lastStart) {
            if (text.regionMatches(i, pattern, 0, pattern.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    /** 居中上下文摘要：命中前后各取若干字符，换行折叠为空格，非贴边补省略号 */
    private fun buildSnippet(body: String, start: Int, end: Int): String {
        val s = maxOf(0, start - CONTEXT_BEFORE)
        val e = minOf(body.length, end + CONTEXT_AFTER)
        val sb = StringBuilder(e - s + 2)
        if (s > 0) sb.append('…')
        for (i in s until e) {
            val c = body[i]
            sb.append(if (c == '\n' || c == '\r') ' ' else c)
        }
        if (e < body.length) sb.append('…')
        return sb.toString().trim()
    }
}

/**
 * 流式章节切片装配器（纯类）：全书单趟解码时按字符偏移边界切出各章原文切片
 *
 * 搜索扫描 O(n²)→O(n) 的核心改造件：旧路径每章单独打开文件并从头 skip 解码到
 * 目标偏移（Reader skip = 解码后丢弃），全书扫描代价是字符量 × 章节数；本装配器
 * 配合顺序解码器，把全书解码压缩为单趟，切片语义与逐章装载完全一致
 * （章节 i 占据半开区间 [前一边界, 本边界)，末章右端为全书总字符长）。
 *
 * 用法：顺序 feed 解码块 → 每次喂入后 drain 取走已完成切片 → 流结束后 finish()
 * 冲刷末章；finish 与 drain 均可重复调用且幂等。
 */
class ChapterSliceAssembler(chapterEndOffsets: IntArray) {

    private val ends = chapterEndOffsets.copyOf()
    private var nextChapter = 0
    private var currentStart = 0
    private var buffer = StringBuilder()
    private var drained: MutableList<Pair<Int, String>> = mutableListOf()

    /** 喂入一段顺序解码的字符块（[offset, offset + length) 为块内窗口） */
    fun feed(chunk: CharArray, offset: Int, length: Int) {
        if (nextChapter >= ends.size || length <= 0) return
        var pos = offset
        val end = offset + length
        while (pos < end && nextChapter < ends.size) {
            val boundary = ends[nextChapter]
            val remainingToBoundary = boundary - (currentStart + buffer.length)
            if (remainingToBoundary <= 0) {
                // 当前章已到边界：封板并推进下一章（空章节在此产出空切片）
                completed(Pair(nextChapter, buffer.toString()))
                nextChapter++
                currentStart = boundary
                buffer = StringBuilder()
                continue
            }
            val take = minOf(remainingToBoundary, end - pos)
            buffer.append(chunk, pos, take)
            pos += take
        }
    }

    /** 流结束：把残段冲刷为当前章，其余未触达章节按空切片补齐（实际解码截短时不丢章序） */
    fun finish() {
        if (nextChapter < ends.size) {
            completed(Pair(nextChapter, buffer.toString()))
            nextChapter++
            buffer = StringBuilder()
            while (nextChapter < ends.size) {
                completed(Pair(nextChapter, ""))
                nextChapter++
            }
        }
    }

    private fun completed(slice: Pair<Int, String>) {
        drained.add(slice)
    }

    /** 取走已完成切片（取走即清空，可安全重复调用） */
    fun drain(): List<Pair<Int, String>> {
        val out: List<Pair<Int, String>> = drained
        drained = mutableListOf()
        return out
    }
}

/**
 * 章节偏移精确映射引擎：正文排版坐标 ↔ 原文切片坐标
 *
 * 背景约束：formattedBody 为每段追加了全角缩进并重排换行，正文字符索引
 * 不再等于原文偏移。持久化的阅读位置必须落在原文坐标域，否则全局进度、
 * 跨章节定位与 DataStore 保存值全部失真。
 *
 * 映射模型：段落 i 的正文起点 bodyParaStarts[i] 对应原文 rawParaStarts[i]；
 * 段内前 indentChars 个字符为格式化时补入的缩进（映射回段首原文位置），其后与原文逐字符 1:1。
 * 空映射数组时退化为恒等映射（正文坐标 = 原文坐标）。
 */
object ChapterOffsetMapper {

    /** 正文坐标 → 原文切片坐标（结果钳制在 [0, rawLength]） */
    fun bodyToRaw(
        bodyIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int,
        rawLength: Int,
        indentChars: Int = 2
    ): Int {
        val safeBody = bodyIndex.coerceIn(0, bodyLength)
        // 空映射数组退化为恒等映射（EPUB 等未提供映射的路径，正文坐标即原文坐标）
        if (bodyParaStarts.isEmpty() || rawParaStarts.size != bodyParaStarts.size) {
            return safeBody
        }
        if (safeBody < bodyParaStarts[0]) {
            return rawParaStarts[0].coerceIn(0, rawLength)
        }

        // 二分定位：最后一个 bodyParagraphStarts[i] <= safeBody
        var lo = 0
        var hi = bodyParaStarts.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bodyParaStarts[mid] <= safeBody) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val inPara = safeBody - bodyParaStarts[found]
        // 段内前 indentChars 字符为补入缩进，映射回段首原文位置；其后逐字符 1:1
        val raw = rawParaStarts[found] + maxOf(0, inPara - indentChars)
        val upper = if (found < rawParaStarts.lastIndex) rawParaStarts[found + 1] - 1 else rawLength
        return raw.coerceIn(0, minOf(rawLength, upper))
    }

    /** 原文切片坐标 → 正文坐标（结果钳制在 [0, bodyLength]） */
    fun rawToBody(
        rawIndex: Int,
        bodyParaStarts: IntArray,
        rawParaStarts: IntArray,
        bodyLength: Int,
        indentChars: Int = 2
    ): Int {
        // 入参为原文坐标域，不做正文长度预钳制（结果统一收敛到 [0, bodyLength]）
        val safeRaw = rawIndex
        if (bodyParaStarts.isEmpty() || rawParaStarts.size != bodyParaStarts.size) {
            return rawIndex.coerceIn(0, bodyLength)
        }
        if (safeRaw < rawParaStarts[0]) {
            return 0
        }

        var lo = 0
        var hi = rawParaStarts.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rawParaStarts[mid] <= safeRaw) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val inRaw = safeRaw - rawParaStarts[found]
        // 段落正文内容区终点（不含段落间换行与补入缩进）；原文行尾空白/空行吸附到段末字符
        val contentEnd = if (found < bodyParaStarts.lastIndex) bodyParaStarts[found + 1] - indentChars else bodyLength
        val body = bodyParaStarts[found] + indentChars + inRaw
        return body.coerceIn(bodyParaStarts[found], (contentEnd - 1).coerceAtLeast(bodyParaStarts[found]))
            .coerceIn(0, bodyLength)
    }
}
