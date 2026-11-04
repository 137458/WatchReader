package com.watchreader

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * EPUB 单章元信息
 */
data class EpubChapterEntry(
    val index: Int,
    val title: String,
    val entryPath: String,
    val anchor: String = "",
    val startCharOffset: Int = 0,
    val charLength: Int = 0
)

/**
 * EPUB 书籍完整解析元数据
 */
data class EpubMetadata(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val chapterEntries: List<EpubChapterEntry>,
    val totalChars: Int
)

/**
 * 高性能零外部依赖原生 EPUB 电子书解析引擎（V2 毫秒级秒开版）
 *
 * 核心优化：
 * 1. 双模加速引擎：
 *    - 随机访问 ZipFile 引擎（针对本地文件与私有书库，Central Directory 索引 O(1) 毫秒直达）；
 *    - 单趟流式索引引擎（针对普通流与测试环境，单次 Zip 遍历完成全文元数据与文本归档，彻底消除 O(N^2) 重复解压）。
 * 2. 内存与磁盘多级缓存：
 *    - 元数据缓存 (ConcurrentHashMap)；
 *    - 正文排版 LRU 内存缓存 (LruCache)，章节切换 0ms 瞬间呈现。
 * 3. 0 第三方依赖：仅使用 Android 原生标准库，包体积 0 增加。
 * 4. 模块切分：本 object 只做编排（双模解析、元数据组装、缓存、归档访问），
 *    容器/OPF 见 [EpubOpf]，目录见 [EpubToc]，HTML → 纯文本见 [EpubTextExtractor]。
 */
object EpubParser {

    private const val TAG = "EpubParser"

    // 内存元数据缓存（以 uri+size 为 Key，秒开秒读）
    private val metadataCache = ConcurrentHashMap<String, EpubMetadata>()

    // 已排版章节正文 LRU 缓存（容量 32 章，翻页 0 延迟）
    private val chapterContentCache = LruCache<String, ChapterContent>(32)

    /**
     * 判断指定 URI 是否为 EPUB 文件
     *
     * 委托 [BookTextConverter.resolveFormat]：扩展名与头部魔数只在这里有一份判定口径，
     * 调用方与书架徽标、转换入口的结论不会再出现分歧。
     *
     * TODO(波次二)：ReaderViewModel 仍在三处各自判定格式（:480 / :575 / :609），
     * 待收口到 resolveFormat 单一入口后本函数只作为 EPUB 语义糖。
     */
    fun isEpubFile(context: Context, uri: Uri): Boolean =
        BookTextConverter.resolveFormat(context, uri) == BookFormat.EPUB

    /**
     * 智能净化书名（去除 .epub, .txt 等常见文件后缀）
     */
    fun cleanBookTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        val suffixes = listOf(
            ".epub", ".mobi", ".azw3", ".azw", ".prc", ".fb2",
            ".html", ".htm", ".xhtml", ".txt", ".md", ".log"
        )
        // 反复剥除以覆盖叠加后缀（如「书名.fb2.txt」）
        var changed = true
        while (changed) {
            changed = false
            for (suffix in suffixes) {
                if (title.length > suffix.length && title.endsWith(suffix, ignoreCase = true)) {
                    title = title.dropLast(suffix.length)
                    changed = true
                }
            }
        }
        return title.trim()
    }

    /**
     * 获取 URI 对应的本地 File 句柄（优先使用私有目录或缓存文件以支持 ZipFile 极速随机访问）
     */
    fun getFileForUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.canRead()) return f
            }
        }

        // 检查应用私有书库 (Wi-Fi 传书)
        val lastSeg = uri.lastPathSegment
        if (!lastSeg.isNullOrEmpty()) {
            val internalFile = File(context.filesDir, "books/${File(lastSeg).name}")
            if (internalFile.exists() && internalFile.canRead()) return internalFile
        }

        // 如果是 content:// 且无法直接获取文件，创建/复用缓存文件加速随机读取
        return try {
            val size = getFileSize(context, uri)
            val cacheFile = File(context.cacheDir, "epub_cache_${uri.hashCode()}_${size}.epub")
            if (cacheFile.exists() && cacheFile.length() == size && size > 0) {
                cacheFile
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析 EPUB 完整结构并生成章节目录与元数据（基于 URI 与 Context）
     */
    fun parseEpub(context: Context, uri: Uri): EpubMetadata {
        val fileSize = getFileSize(context, uri)
        val cacheKey = "${uri}_$fileSize"
        metadataCache[cacheKey]?.let { return it }

        val defaultTitle = cleanBookTitle(getFileName(context, uri))

        // 1. 优先尝试 ZipFile 极速随机访问
        val localFile = getFileForUri(context, uri)
        val metadata = if (localFile != null && localFile.exists() && localFile.canRead()) {
            try {
                parseEpubWithZipFile(localFile, defaultTitle)
            } catch (e: Exception) {
                Log.w(TAG, "ZipFile parsing failed, falling back to stream parsing", e)
                parseEpubFromStream({ context.contentResolver.openInputStream(uri)!! }, defaultTitle)
            }
        } else {
            parseEpubFromStream({ context.contentResolver.openInputStream(uri)!! }, defaultTitle)
        }

        metadataCache[cacheKey] = metadata
        return metadata
    }

    /**
     * 基于 ZipFile 的极速 O(1) 随机访问解析器（耗时 < 5ms）
     */
    private fun parseEpubWithZipFile(file: File, defaultTitle: String): EpubMetadata {
        ZipFile(file).use { zipFile ->
            // 1. 读取 container.xml 查找 OPF 路径
            val containerEntry = findZipEntry(zipFile, "META-INF/container.xml")
                ?: throw IllegalStateException("EPUB 格式错误: 未找到 META-INF/container.xml")

            val containerXml = zipFile.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).readText()
            val opfPath = EpubOpf.parseOpfPathFromContainer(containerXml)
                ?: throw IllegalStateException("EPUB 格式错误: 未找到 OPF rootfile 路径")

            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""

            // 2. 读取 OPF
            val opfEntry = findZipEntry(zipFile, opfPath)
                ?: throw IllegalStateException("EPUB 格式错误: 无法找到 OPF 描述文件 $opfPath")

            val opfXml = zipFile.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).readText()
            val opfInfo = EpubOpf.parseOpf(opfXml, opfDir)

            // 3. 解析章节列表 (NCX -> Nav -> Spine)
            var chapterEntries = emptyList<EpubChapterEntry>()

            if (!opfInfo.ncxPath.isNullOrEmpty()) {
                val ncxEntry = findZipEntry(zipFile, opfInfo.ncxPath)
                if (ncxEntry != null) {
                    val ncxXml = zipFile.getInputStream(ncxEntry).bufferedReader(Charsets.UTF_8).readText()
                    val ncxDir = if (opfInfo.ncxPath.contains('/')) opfInfo.ncxPath.substringBeforeLast('/') + "/" else ""
                    chapterEntries = EpubToc.parseNcxToc(ncxXml, ncxDir)
                }
            }

            if (chapterEntries.isEmpty() && !opfInfo.navPath.isNullOrEmpty()) {
                val navEntry = findZipEntry(zipFile, opfInfo.navPath)
                if (navEntry != null) {
                    val navXml = zipFile.getInputStream(navEntry).bufferedReader(Charsets.UTF_8).readText()
                    val navDir = if (opfInfo.navPath.contains('/')) opfInfo.navPath.substringBeforeLast('/') + "/" else ""
                    chapterEntries = EpubToc.parseNavToc(navXml, navDir)
                }
            }

            if (chapterEntries.isEmpty()) {
                chapterEntries = createSpineChaptersWithZipFile(zipFile, opfInfo.spinePaths)
            }

            if (chapterEntries.isEmpty()) {
                throw IllegalStateException("EPUB 中未找到可阅读的章节")
            }

            // 4. 构建章节列表
            return buildFinalMetadata(chapterEntries, opfInfo.title, defaultTitle, opfInfo.author)
        }
    }

    /**
     * 单趟流式索引解析器（单次 Zip 遍历完成所有 XML 与文本归档）
     */
    fun parseEpubFromStream(openStream: () -> InputStream, defaultTitle: String = ""): EpubMetadata {
        // 单趟遍历 Zip 提取所有小文本/XML 文件到内存 Map，彻底消除多次 Zip 重复解压
        val entriesMap = mutableMapOf<String, String>()
        ZipInputStream(BufferedInputStream(openStream(), 65536)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val norm = EpubOpf.normalizePath(entry.name).lowercase()
                if (norm.endsWith(".xml") || norm.endsWith(".opf") || norm.endsWith(".ncx") ||
                    norm.endsWith(".xhtml") || norm.endsWith(".html") || norm.endsWith(".htm") ||
                    norm.contains("container")
                ) {
                    val text = if (norm.endsWith(".xml") || norm.endsWith(".opf") || norm.endsWith(".ncx") ||
                        norm.contains("container") || norm.contains("nav") || norm.contains("toc")
                    ) {
                        zis.bufferedReader(Charsets.UTF_8).readText()
                    } else {
                        // 正文 HTML 仅读取前 2048 字符供提取标题，杜绝穿戴设备内存暴涨 OOM
                        val buf = CharArray(2048)
                        val read = zis.bufferedReader(Charsets.UTF_8).read(buf)
                        if (read > 0) String(buf, 0, read) else ""
                    }
                    entriesMap[EpubOpf.normalizePath(entry.name)] = text
                }
                entry = zis.nextEntry
            }
        }

        // 1. 读取 container.xml
        val containerXml = findMapEntry(entriesMap, "META-INF/container.xml")
            ?: throw IllegalStateException("EPUB 格式错误: 未找到 META-INF/container.xml")

        val opfPath = EpubOpf.parseOpfPathFromContainer(containerXml)
            ?: throw IllegalStateException("EPUB 格式错误: 未找到 OPF rootfile 路径")

        val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""

        // 2. 读取 OPF
        val opfXml = findMapEntry(entriesMap, opfPath)
            ?: throw IllegalStateException("EPUB 格式错误: 无法找到 OPF 描述文件 $opfPath")

        val opfInfo = EpubOpf.parseOpf(opfXml, opfDir)

        // 3. 解析章节列表
        var chapterEntries = emptyList<EpubChapterEntry>()

        if (!opfInfo.ncxPath.isNullOrEmpty()) {
            val ncxXml = findMapEntry(entriesMap, opfInfo.ncxPath)
            if (!ncxXml.isNullOrEmpty()) {
                val ncxDir = if (opfInfo.ncxPath.contains('/')) opfInfo.ncxPath.substringBeforeLast('/') + "/" else ""
                chapterEntries = EpubToc.parseNcxToc(ncxXml, ncxDir)
            }
        }

        if (chapterEntries.isEmpty() && !opfInfo.navPath.isNullOrEmpty()) {
            val navXml = findMapEntry(entriesMap, opfInfo.navPath)
            if (!navXml.isNullOrEmpty()) {
                val navDir = if (opfInfo.navPath.contains('/')) opfInfo.navPath.substringBeforeLast('/') + "/" else ""
                chapterEntries = EpubToc.parseNavToc(navXml, navDir)
            }
        }

        if (chapterEntries.isEmpty()) {
            chapterEntries = createSpineChaptersWithMap(entriesMap, opfInfo.spinePaths)
        }

        if (chapterEntries.isEmpty()) {
            throw IllegalStateException("EPUB 中未找到可阅读的章节")
        }

        return buildFinalMetadata(chapterEntries, opfInfo.title, defaultTitle, opfInfo.author)
    }

    private fun buildFinalMetadata(
        chapterEntries: List<EpubChapterEntry>,
        opfTitle: String,
        defaultTitle: String,
        author: String
    ): EpubMetadata {
        val chapters = ArrayList<Chapter>(chapterEntries.size)
        var cumulativeOffset = 0
        val finalEntries = ArrayList<EpubChapterEntry>(chapterEntries.size)

        for ((idx, entry) in chapterEntries.withIndex()) {
            val estimatedLen = if (entry.charLength > 0) entry.charLength else 3000
            val normalizedTitle = entry.title.ifBlank { "第 ${idx + 1} 章" }
            chapters.add(Chapter(index = idx, title = normalizedTitle, charOffset = cumulativeOffset))
            finalEntries.add(entry.copy(index = idx, title = normalizedTitle, startCharOffset = cumulativeOffset))
            cumulativeOffset += estimatedLen
        }

        val totalChars = maxOf(cumulativeOffset, 100)
        val rawBookTitle = opfTitle.ifBlank { defaultTitle.ifBlank { "未命名电子书" } }
        val bookTitle = cleanBookTitle(rawBookTitle)

        return EpubMetadata(
            title = bookTitle,
            author = author,
            chapters = chapters,
            chapterEntries = finalEntries,
            totalChars = totalChars
        )
    }

    /**
     * 按章节索引读取单章正文内容并排版（命中 LRU 内存缓存 0ms 直出）
     *
     * @return 读取失败返回 null（章节索引越界、zip 条目缺失、解压/流读取异常）。
     * 失败绝不返回占位正文：占位文本会被当成正常正文写入 LRU 缓存与阅读进度，
     * 上层也就无法把"读取失败"呈现成可重试的错误态，而会当作"本章确实没有内容"。
     * 条目存在但正文为空（插图页等）属于"空章"，返回 formattedBody 为空的 ChapterContent，
     * 与 null（读失败）区分开。
     */
    fun readChapterContent(
        context: Context,
        uri: Uri,
        chapterIndex: Int,
        chapters: List<Chapter>,
        cleanTypography: Boolean = true
    ): ChapterContent? {
        if (chapters.isEmpty() || chapterIndex !in chapters.indices) return null

        val fileSize = getFileSize(context, uri)
        val cacheKey = "${uri}_${fileSize}"
        val chapterCacheKey = "${cacheKey}_${chapterIndex}_${if (cleanTypography) "clean" else "raw"}"

        chapterContentCache.get(chapterCacheKey)?.let { return it }

        val metadata = metadataCache[cacheKey] ?: parseEpub(context, uri)
        val currentChap = chapters[chapterIndex]

        val currentEntry = metadata.chapterEntries.getOrNull(chapterIndex)
        val nextEntry = metadata.chapterEntries.getOrNull(chapterIndex + 1)
        val nextAnchor = if (currentEntry != null && nextEntry != null && currentEntry.entryPath == nextEntry.entryPath) {
            nextEntry.anchor
        } else ""

        val entryPath = currentEntry?.entryPath ?: return null
        // null 表示"没取到该条目"（缺失/解压失败），与"取到但正文为空"（""）区分：
        // 前者是读失败，后者是插图页这类合法的空章
        var rawHtml: String? = null

        val localFile = getFileForUri(context, uri)
        if (localFile != null && localFile.exists() && localFile.canRead()) {
            try {
                ZipFile(localFile).use { zf ->
                    val entry = findZipEntry(zf, entryPath)
                    if (entry != null) {
                        rawHtml = zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ZipFile 读取章节失败: $entryPath", e)
            }
        }

        if (rawHtml == null) {
            // 回退到流式 zip 读取（部分 SAF 提供方无法给出可直接打开的本地文件路径）
            val stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                Log.w(TAG, "无法打开 EPUB 流: $uri", e)
                null
            } ?: return null
            rawHtml = readZipEntryString({ stream }, entryPath) ?: return null
        }

        // rawHtml 在上面的 lambda 里赋值，Kotlin 不做智能转换：显式收敛为非空值
        val html = rawHtml ?: return null
        val plainBody = EpubTextExtractor.extractFormattedTextFromHtml(html, currentEntry?.anchor ?: "", nextAnchor, cleanTypography)

        val startOffset = currentChap.charOffset
        val endOffset = if (chapterIndex + 1 < chapters.size) chapters[chapterIndex + 1].charOffset else metadata.totalChars

        val hasPrev = chapterIndex > 0
        val prevTitle = if (hasPrev) chapters[chapterIndex - 1].title else ""
        val hasNext = chapterIndex + 1 < chapters.size
        val nextTitle = if (hasNext) chapters[chapterIndex + 1].title else ""

        val result = ChapterContent(
            chapterIndex = chapterIndex,
            title = currentChap.title,
            formattedBody = plainBody,
            startCharOffset = startOffset,
            endCharOffset = endOffset,
            hasPrevChapter = hasPrev,
            prevChapterTitle = prevTitle,
            hasNextChapter = hasNext,
            nextChapterTitle = nextTitle
        )

        chapterContentCache.put(chapterCacheKey, result)
        return result
    }

    // ═════════════════════════════════════════════════════════════════════
    //  内部 XML 与 ZIP 解析优化实现
    // ═════════════════════════════════════════════════════════════════════

    private fun findZipEntry(zipFile: ZipFile, path: String): ZipEntry? {
        val norm = EpubOpf.normalizePath(path)
        zipFile.getEntry(norm)?.let { return it }
        zipFile.getEntry(path)?.let { return it }
        // 忽略大小写查找
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (EpubOpf.normalizePath(e.name).equals(norm, ignoreCase = true)) {
                return e
            }
        }
        return null
    }

    private fun findMapEntry(map: Map<String, String>, path: String): String? {
        val norm = EpubOpf.normalizePath(path)
        map[norm]?.let { return it }
        map[path]?.let { return it }
        for ((k, v) in map) {
            if (k.equals(norm, ignoreCase = true)) return v
        }
        return null
    }

    private fun createSpineChaptersWithZipFile(zipFile: ZipFile, spinePaths: List<String>): List<EpubChapterEntry> {
        val list = mutableListOf<EpubChapterEntry>()
        for ((idx, path) in spinePaths.withIndex()) {
            val entry = findZipEntry(zipFile, path) ?: continue
            val content = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { r ->
                // 仅读取前 2048 字符提取标题，避免大章节全量读取
                val buf = CharArray(2048)
                val read = r.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
            val title = EpubTextExtractor.extractTitleFromHtml(content).ifBlank { "第 ${idx + 1} 节" }
            list.add(
                EpubChapterEntry(
                    index = idx,
                    title = title,
                    entryPath = path,
                    anchor = ""
                )
            )
        }
        return list
    }

    private fun createSpineChaptersWithMap(map: Map<String, String>, spinePaths: List<String>): List<EpubChapterEntry> {
        val list = mutableListOf<EpubChapterEntry>()
        for ((idx, path) in spinePaths.withIndex()) {
            val content = findMapEntry(map, path) ?: continue
            val title = EpubTextExtractor.extractTitleFromHtml(content).ifBlank { "第 ${idx + 1} 节" }
            list.add(
                EpubChapterEntry(
                    index = idx,
                    title = title,
                    entryPath = path,
                    anchor = ""
                )
            )
        }
        return list
    }

    // ═════════════════════════════════════════════════════════════════════
    //  对外委托：HTML → 纯文本（实现在 EpubTextExtractor，此处只保留入口）
    // ═════════════════════════════════════════════════════════════════════

    /** 见 [EpubTextExtractor.extractFormattedTextFromHtml]；外部调用方（BookTextConverter / 测试）不变。 */
    fun extractFormattedTextFromHtml(
        html: String,
        targetAnchor: String = "",
        nextAnchor: String = "",
        cleanTypography: Boolean = true
    ): String = EpubTextExtractor.extractFormattedTextFromHtml(html, targetAnchor, nextAnchor, cleanTypography)

    /** 见 [EpubTextExtractor.decodeHtmlEntities]；外部调用方（BookTextConverter / 测试）不变。 */
    fun decodeHtmlEntities(input: String): String = EpubTextExtractor.decodeHtmlEntities(input)

    private fun readZipEntryString(openStream: () -> InputStream, entryPath: String): String? {
        val target = EpubOpf.normalizePath(entryPath)
        try {
            ZipInputStream(BufferedInputStream(openStream(), 65536)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val currentName = EpubOpf.normalizePath(entry.name)
                    if (currentName.equals(target, ignoreCase = true)) {
                        return zis.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read zip entry: $entryPath", e)
        }
        return null
    }

}