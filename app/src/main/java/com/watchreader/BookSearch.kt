package com.watchreader

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Reader

/**
 * 书内全文搜索对「一本可搜的书」的最小依赖面。
 *
 * 搜索编排只关心四件事：章节边界在哪、全书有多长、有没有连续字符流、某章正文怎么取。
 * 把 BookSource / ReadingSession / Context / 文件句柄全部挡在接口外，搜索编排才能在
 * 纯 JVM 里用内存假书测——本接口存在的全部理由就是这条接缝（删除它，编排逻辑只能
 * 退回 ViewModel 并用真实文件系统测试）。
 *
 * 线程约定：[readChapterForSearch] 是 suspend，由实现方决定 I/O 线程；
 * [openCharStream] 与 [chapterEndOffsets] 是纯内存操作，可在任意线程调用。
 */
internal interface SearchableBook {

    val chapters: List<Chapter>

    val totalChars: Int

    /** 各章右端偏移（半开区间），语义见 [BookSource.chapterEndOffsets] */
    fun chapterEndOffsets(): IntArray

    /**
     * 全书单趟顺序字符流；null 表示该格式没有连续文本（EPUB 正文按条目压缩存储），
     * 调用方退回逐章装载。调用方负责 close。
     */
    fun openCharStream(): Reader?

    /** 单章原样态正文（正文 = 原文切片，坐标可直接换算），失败返回 null */
    suspend fun readChapterForSearch(index: Int): ChapterContent?
}

/**
 * 搜索状态：UI 只消费这一份快照，编排内部不再向 ViewModel 逐个字段回写。
 *
 * [hits] 是「已扫到的命中」增量快照，[scannedChapters] 与 [totalChapters] 供进度条使用；
 * 搜索结束由 [isSearching] 翻转表达，命中的最终值就是最后一次快照（取消时保留已扫到的部分）。
 */
@Immutable
data class SearchState(
    val isSearching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val scannedChapters: Int = 0,
    val totalChapters: Int = 0
)

/**
 * 书内全文搜索编排（深模块）：把「有没有连续流」「按章还是按切片」「进度怎么上报」
 * 「取消时保留什么」四类决策收在一处，调用方只做三件事：search / cancel / reset。
 *
 * 两条扫描路径的坐标语义必须一致：流式路径按 [BookSource.chapterBounds] 切片后以原样态
 * 排版（标题首行剥除的偏移由 rawParagraphStarts 携带），逐章路径直接用原样态正文的
 * startCharOffset；两条路径的命中偏移都落在「全书原始字符偏移域」，可直接用于跳章。
 *
 * @param scope 搜索协程的作用域（由调用方持有生命周期，如 viewModelScope）；
 * 取消 [BookSearchController] 持有的 job 不影响该作用域本身
 * @param ioDispatcher 扫描体所在调度器，默认为 [Dispatchers.IO]；
 * 测试可注入同一调度器以获得确定性
 */
internal class BookSearchController(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * 开始全书搜索。
     *
     * [book] 为 null（未开书）、查询词 trim 后为空、章节表为空时直接返回，状态不动——
     * 这是"什么都没发生"，与"搜了但零命中"是两种不同结果，后者会走完整扫描并上报进度。
     */
    fun search(book: SearchableBook?, query: String) {
        val target = book ?: return
        val keyword = query.trim()
        if (keyword.isEmpty() || target.chapters.isEmpty()) return

        searchJob?.cancel()
        _state.update {
            it.copy(
                isSearching = true,
                hits = emptyList(),
                scannedChapters = 0,
                totalChapters = target.chapters.size
            )
        }
        searchJob = scope.launch {
            val hits = mutableListOf<SearchHit>()
            try {
                withContext(ioDispatcher) {
                    val stream = target.openCharStream()
                    if (stream == null) {
                        scanByLoading(this, target, keyword, hits)
                    } else {
                        stream.use { reader -> scanStream(this, reader, target, keyword, hits) }
                    }
                }
            } finally {
                // 取消时不覆盖状态：cancel() 已经把 isSearching 置 false 并保留已扫到的命中，
                // 这里若再写一次会把「取消瞬间之后才完成的切片」当成最终结果盖掉进度
                if (isActive) {
                    _state.update { it.copy(isSearching = false, hits = hits.toList()) }
                }
            }
        }
    }

    /** 停止全书扫描：已扫到的命中保留，转圈态立即复位 */
    fun cancel() {
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(isSearching = false) }
    }

    /** 回到初始态（关书时调用）：取消扫描并清空命中与进度 */
    fun reset() {
        searchJob?.cancel()
        searchJob = null
        _state.value = SearchState()
    }

    /**
     * 无连续流的回退路径：逐章装载原样态正文后匹配。
     *
     * 每章都上报进度（哪怕该章读取失败），否则失败章会让进度条停住不动。
     */
    private suspend fun scanByLoading(
        scanScope: CoroutineScope,
        book: SearchableBook,
        keyword: String,
        hits: MutableList<SearchHit>
    ) {
        for (index in book.chapters.indices) {
            if (!scanScope.isActive || hits.size >= BookSearchEngine.MAX_RESULTS) break
            val content = book.readChapterForSearch(index)
            if (content != null) {
                appendHits(
                    book = book,
                    body = content.formattedBody,
                    bodyStartInRaw = content.rawParagraphStarts.firstOrNull() ?: 0,
                    baseCharOffset = content.startCharOffset,
                    chapterIndex = index,
                    keyword = keyword,
                    hits = hits
                )
            }
            reportProgress(index + 1, hits)
        }
    }

    /**
     * 有连续流的主路径：单趟顺序解码 + [ChapterSliceAssembler] 按章节边界切片。
     *
     * 旧路径每章各自打开文件并从头 skip 解码，全书扫描代价是字符量 × 章节数；单趟解码
     * 把代价压回字符量本身（每个字符只解码一次）。
     */
    private fun scanStream(
        scanScope: CoroutineScope,
        reader: Reader,
        book: SearchableBook,
        keyword: String,
        hits: MutableList<SearchHit>
    ) {
        val assembler = ChapterSliceAssembler(book.chapterEndOffsets())
        val buffer = CharArray(READ_BUFFER_CHARS)
        while (scanScope.isActive && hits.size < BookSearchEngine.MAX_RESULTS) {
            val readCount = reader.read(buffer)
            if (readCount < 0) break
            if (readCount > 0) assembler.feed(buffer, 0, readCount)
            drainSlices(book, assembler.drain(), keyword, hits)
        }
        // 流结束冲刷末章：提前 break（命中封顶）时也要走这里，保证未触达章节不丢章序
        assembler.finish()
        drainSlices(book, assembler.drain(), keyword, hits)
        reportProgress(book.chapters.size, hits)
    }

    /** 取走装配好的整章切片逐章匹配，并按最后完成的章节上报进度 */
    private fun drainSlices(
        book: SearchableBook,
        slices: List<Pair<Int, String>>,
        keyword: String,
        hits: MutableList<SearchHit>
    ) {
        if (slices.isEmpty()) return
        for ((index, slice) in slices) {
            if (hits.size >= BookSearchEngine.MAX_RESULTS) break
            scanSlice(book, slice, index, keyword, hits)
        }
        reportProgress(slices.last().first + 1, hits)
    }

    /** 切片 → 与逐章装载同语义的原样态排版 → 匹配（单趟扫描核心步） */
    private fun scanSlice(
        book: SearchableBook,
        slice: String,
        chapterIndex: Int,
        keyword: String,
        hits: MutableList<SearchHit>
    ) {
        val chapters = book.chapters
        if (chapterIndex !in chapters.indices || slice.isEmpty()) return
        val bounds = BookSource.chapterBounds(chapters, chapterIndex, book.totalChars) ?: return
        val content = formatChapterRawText(
            slice, chapters, chapterIndex, bounds.start, bounds.end, cleanTypography = false
        )
        appendHits(
            book = book,
            body = content.formattedBody,
            bodyStartInRaw = content.rawParagraphStarts.firstOrNull() ?: 0,
            baseCharOffset = bounds.start,
            chapterIndex = chapterIndex,
            keyword = keyword,
            hits = hits
        )
    }

    private fun appendHits(
        book: SearchableBook,
        body: String,
        bodyStartInRaw: Int,
        baseCharOffset: Int,
        chapterIndex: Int,
        keyword: String,
        hits: MutableList<SearchHit>
    ) {
        if (body.isEmpty()) return
        val chapters = book.chapters
        if (chapterIndex !in chapters.indices) return
        hits += BookSearchEngine.findMatches(
            body = body,
            query = keyword,
            bodyStartInRaw = bodyStartInRaw,
            baseCharOffset = baseCharOffset,
            chapterIndex = chapterIndex,
            chapterTitle = chapters[chapterIndex].title,
            maxHits = BookSearchEngine.MAX_RESULTS - hits.size
        )
    }

    private fun reportProgress(scanned: Int, hits: List<SearchHit>) {
        _state.update { it.copy(scannedChapters = scanned, hits = hits.toList()) }
    }

    private companion object {
        /** 与旧版 ViewModel 内联扫描一致的单趟解码块大小：权衡 GC 压力与切片粒度 */
        const val READ_BUFFER_CHARS = 24576
    }
}

/**
 * 把阅读会话适配成可搜对象：搜索编排只依赖 [SearchableBook]，不依赖文件系统，
 * 因而可在纯 JVM 用内存假书测试（删掉这个适配，接线处就要写匿名对象并重复五个成员）。
 */
internal fun ReadingSession.asSearchableBook(): SearchableBook = object : SearchableBook {
    override val chapters: List<Chapter> get() = this@asSearchableBook.chapters
    override val totalChars: Int get() = this@asSearchableBook.totalChars
    override fun chapterEndOffsets(): IntArray = this@asSearchableBook.chapterEndOffsets()
    override fun openCharStream(): Reader? = this@asSearchableBook.openCharStream()
    override suspend fun readChapterForSearch(index: Int): ChapterContent? =
        this@asSearchableBook.readChapterForSearch(index)
}
