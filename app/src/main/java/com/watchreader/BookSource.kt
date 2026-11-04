package com.watchreader

import android.content.Context
import android.net.Uri
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap

/**
 * 「把一本书从 URI 读成正文」的唯一入口。
 *
 * 格式判定、读取源选择（原文件 / 转换文本缓存）、编码探测与 BOM 处理、章节索引装载
 * 与缓存、单章排版，全部收在本接口后面。调用方只拿着一个实例按 [chapters] 定位、
 * 按 [readChapter] 取正文，不再持有「编码」「转换缓存 URI」「是不是 EPUB」这类跨调用
 * 哨兵——这些状态一旦漏到调用方，换书或重入装载时就会互相污染（上一本的转换缓存
 * 串到下一本、编码与读取源错配）。
 *
 * 线程约定：[open] 是阻塞 I/O，调用方必须在 IO 线程执行；创建完成后
 * [readChapter]、[readRawChunk]、[openCharStream] 可被多个 IO 线程并发调用
 * （相邻章节预取与书内搜索会同时读同一本书），每次读取各自打开独立流，
 * 不共享可变缓冲。
 */
interface BookSource {

    /** 书名：TXT / 转换类取文件名，EPUB 取 OPF 标题 */
    val title: String

    /** 章节索引；[Chapter.charOffset] 与 [readRawChunk] 使用同一字符偏移域 */
    val chapters: List<Chapter>

    /** 全书总字符数，与 [chapters] 同一偏移域 */
    val totalChars: Int

    /**
     * 读取并排版单章。
     *
     * 返回 null 只表示读取失败（流打不开、EPUB 条目缺失、偏移越界到读不动），
     * 空章（正文确实为空，如插图页）返回 formattedBody 为空的合法 [ChapterContent]；
     * 调用方据此把失败呈现为可重试错误，而不是「本章没有内容」。
     */
    fun readChapter(index: Int, cleanTypography: Boolean = true): ChapterContent?

    /**
     * 按字符偏移读取原文切片（未排版）。
     *
     * null = 读取失败，"" = 合法空区间（end <= start）。供搜索等需要原文而非排版结果的场景。
     */
    fun readRawChunk(startCharOffset: Int, endCharOffset: Int): String?

    /**
     * 全书单趟顺序字符流；null 表示该格式没有连续文本（EPUB 正文按条目压缩存储）。
     * 调用方负责 close（同时释放底层文件描述符）。
     *
     * 这是接口里的第五个成员，而不是让调用方自己拿 URI + 编码去开流：书内搜索的
     * 单趟扫描无法用 [readRawChunk] 表达（逐章重开会退化成每章从头 skip 解码的 O(n²)），
     * 而把读取源与编码暴露给调用方，等于把本接口刚封上的接缝重新打穿。
     */
    fun openCharStream(): Reader? = null

    companion object {

        /**
         * 会话级章节索引内存缓存（键 = 原书 URI + 原书字节数）。
         *
         * 这是派生数据的幂等缓存：同一本书重复打开得到同一索引，条目数受书架规模约束；
         * 进程重启即丢，磁盘侧还有 [ChapterDiskCache] 兜底。
         */
        private val indexMemory = ConcurrentHashMap<String, BookIndex>()

        /**
         * 打开一本书：一次完成格式分流、章节索引装载与读取源准备。
         *
         * @param format 调用方已解析的格式；省略时内部按 URI 重新解析（多一次头部采样 I/O）
         * @throws IllegalStateException 文件为空或无法读取
         * @throws BookFormatException 转换类格式转换失败（DRM / HUFF 压缩 / 容器损坏）
         */
        fun open(
            context: Context,
            uri: Uri,
            format: BookFormat = BookTextConverter.resolveFormat(context, uri)
        ): BookSource {
            return when (format) {
                BookFormat.EPUB -> {
                    // EPUB 章节是 zip 条目而非连续字符流，索引只能来自 OPF/NCX/Nav 解析；
                    // 其解析结果由 EpubParser 的元数据缓存持有，章节索引磁盘缓存对 EPUB 无消费方
                    EpubBookSource(context, uri, EpubParser.parseEpub(context, uri))
                }

                BookFormat.TXT -> {
                    val encoding = detectFileEncoding(context, uri)
                    val fileSize = getFileSize(context, uri)
                    val index = loadIndex(context, uri, encoding, uri, fileSize)
                    TxtBookSource(
                        context = context,
                        uri = uri,
                        encoding = encoding,
                        title = getFileName(context, uri),
                        chapters = index.chapters,
                        totalChars = index.totalChars
                    )
                }

                BookFormat.MOBI, BookFormat.FB2, BookFormat.HTML -> {
                    // 缓存键锚定原书（URI + 原始字节数）而不是转换产物：转换算法升级会改变
                    // 产物大小，用产物大小做键会让既有索引缓存整体失效
                    val cacheFile = BookTextConverter.convertToCacheFile(context, uri, format)
                    val fileSize = getFileSize(context, uri)
                    val index = loadIndex(context, Uri.fromFile(cacheFile), "UTF-8", uri, fileSize)
                    ConvertedBookSource(
                        context = context,
                        originalUri = uri,
                        format = format,
                        cacheFile = cacheFile,
                        title = getFileName(context, uri),
                        chapters = index.chapters,
                        totalChars = index.totalChars
                    )
                }
            }
        }

        /**
         * 各章右端字符偏移（半开区间用）：章节 i 占据 [chapters[i].charOffset, ends[i])。
         *
         * 末章右端取全书总长；索引倒序或越界（章节起点大于下一章起点、大于总长）时夹紧为单调不减，
         * 否则切片装配器会把倒序边界当成「本章已结束」，静默产出空章。判定在 [BookSourceIndex]。
         */
        fun chapterEndOffsets(chapters: List<Chapter>, totalChars: Int): IntArray =
            BookSourceIndex.chapterEndOffsets(chapters, totalChars)

        /**
         * 单章切片边界：起点与终点都夹进 [0, totalChars]，终点不小于起点；
         * 索引越界返回 null（调用方按「无此章」处理，不是读取失败）。
         */
        fun chapterBounds(chapters: List<Chapter>, index: Int, totalChars: Int): CharBounds? =
            BookSourceIndex.chapterBounds(chapters, index, totalChars)

        /** 旧版磁盘缓存只留下章节表时的总长估算，见 [BookSourceIndex.estimateTotalChars]。 */
        internal fun estimateTotalChars(
            chapters: List<Chapter>,
            fileSizeHint: Long,
            encoding: String
        ): Int = BookSourceIndex.estimateTotalChars(chapters, fileSizeHint, encoding)

        /**
         * 装载章节索引：内存缓存 → 磁盘缓存 → 全量流式扫描并落盘。
         *
         * [cacheKeyUri] 恒为书架原始 URI：转换类格式的读取源是缓存文件，但索引缓存键
         * 必须跟着原书走，否则缓存被清理重建后索引全部失效。
         */
        private fun loadIndex(
            context: Context,
            readUri: Uri,
            encoding: String,
            cacheKeyUri: Uri,
            fileSizeHint: Long
        ): BookIndex {
            val cacheKey = "${cacheKeyUri}_$fileSizeHint"
            indexMemory[cacheKey]?.let { return it }

            val cached = ChapterDiskCache.load(context, cacheKey)
            if (cached != null) {
                val totalChars = if (cached.totalChars > 0) {
                    cached.totalChars
                } else {
                    estimateTotalChars(cached.chapters, fileSizeHint, encoding)
                }
                return BookIndex(cached.chapters, totalChars).also { indexMemory[cacheKey] = it }
            }

            val (chapters, totalChars) = detectChaptersStream(context, readUri, encoding)
            if (chapters.isEmpty() && totalChars == 0) {
                throw IllegalStateException("文件为空或无法读取")
            }
            ChapterDiskCache.save(context, cacheKey, chapters, totalChars)
            return BookIndex(chapters, totalChars).also { indexMemory[cacheKey] = it }
        }
    }
}
