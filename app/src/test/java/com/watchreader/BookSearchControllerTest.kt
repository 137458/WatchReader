package com.watchreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/**
 * 书内搜索编排的纯 JVM 测试：用内存假书替换文件系统，验证两条扫描路径的命中偏移一致、
 * 封顶、取消与进度语义。这些行为原本内联在 ViewModel 里，只能靠真实文件 + Robolectric 触摸。
 */
class BookSearchControllerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: BookSearchController

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = BookSearchController(scope, Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `流式路径命中偏移落在全书原始偏移域`() {
        val book = FakeBook(TEXT, CHAPTERS, stream = StringReader(TEXT))

        controller.search(book, "TARGET")

        val finished = awaitFinished()
        assertFalse(finished.isSearching)
        assertEquals(CHAPTERS.size, finished.totalChapters)
        assertEquals(1, finished.hits.size)
        val hit = finished.hits.single()
        assertEquals(1, hit.chapterIndex)
        assertEquals("章二", hit.chapterTitle)
        // 第二章起始偏移 10 + 正文内 TARGET 起始 4
        assertEquals(14, hit.charOffset)
        assertTrue(hit.snippet.contains("TARGET"))
    }

    @Test
    fun `逐章装载路径命中偏移与流式一致`() {
        val book = FakeBook(
            TEXT,
            CHAPTERS,
            stream = null,
            contents = mapOf(
                0 to ChapterContent(0, "章一", "aaaaaaaaaa", 0, 10, false, "", true, "章二"),
                1 to ChapterContent(1, "章二", "bbbbTARGETbbbb", 10, 24, true, "章一", false, "")
            )
        )

        controller.search(book, "TARGET")

        val finished = awaitFinished()
        assertEquals(1, finished.hits.size)
        val hit = finished.hits.single()
        assertEquals(1, hit.chapterIndex)
        assertEquals(14, hit.charOffset)
    }

    @Test
    fun `命中数量封顶为 MAX_RESULTS`() {
        val text = "x".repeat(500)
        val book = FakeBook(text, listOf(Chapter(title = "章一", charOffset = 0)), stream = StringReader(text))

        controller.search(book, "x")

        val finished = awaitFinished()
        assertEquals(BookSearchEngine.MAX_RESULTS, finished.hits.size)
    }

    @Test
    fun `取消后立即复位搜索态且命中不再增长`() {
        val text = "QQQQQQQQQQ".repeat(30)
        val chapters = (0 until 30).map { Chapter(title = "章" + it, charOffset = it * 10) }
        val reader = SlowStringReader(text, chunkSize = 10, delayMs = 4)
        val book = FakeBook(text, chapters, stream = reader)

        controller.search(book, "Q")
        val firstProgress = runBlocking {
            withTimeout(3_000) { controller.state.first { it.hits.isNotEmpty() } }
        }
        assertTrue(firstProgress.isSearching)

        controller.cancel()
        assertFalse(controller.state.value.isSearching)

        // 取消瞬间已经进入匹配的切片仍可能补一两条命中，等待其收敛后再比较
        Thread.sleep(300)
        val settledCount = controller.state.value.hits.size
        Thread.sleep(200)
        assertEquals("取消收敛后命中数不应继续变化", settledCount, controller.state.value.hits.size)
        assertFalse(controller.state.value.isSearching)
    }

    @Test
    fun `空查询与空书都不启动扫描`() {
        controller.search(null, "TARGET")
        assertEquals(SearchState(), controller.state.value)

        controller.search(FakeBook(TEXT, CHAPTERS, StringReader(TEXT)), "   ")
        assertEquals(SearchState(), controller.state.value)

        controller.search(FakeBook(TEXT, emptyList(), StringReader(TEXT)), "TARGET")
        assertEquals(SearchState(), controller.state.value)
    }

    @Test
    fun `进度单调递增且最终等于章节数`() {
        val text = "aaTARGETaa".repeat(4)
        val chapters = (0 until 4).map { Chapter(title = "章" + (it + 1), charOffset = it * 10) }
        val book = FakeBook(text, chapters, stream = StringReader(text))
        val scanned = mutableListOf<Int>()

        runBlocking {
            val collector = launch(Dispatchers.Default) {
                controller.state.collect { scanned += it.scannedChapters }
            }
            controller.search(book, "TARGET")
            assertEquals(chapters.size, controller.state.value.totalChapters)
            withTimeout(3_000) { controller.state.first { !it.isSearching } }
            collector.cancel()
            collector.join()
        }

        assertEquals("进度序列应单调不减", scanned.sorted(), scanned)
        assertEquals(chapters.size, controller.state.value.scannedChapters)
        assertEquals("四章各命中一次", 4, controller.state.value.hits.size)
    }

    @Test
    fun `reset 回到初始状态并清空命中`() {
        controller.search(FakeBook(TEXT, CHAPTERS, StringReader(TEXT)), "TARGET")
        val finished = awaitFinished()
        assertTrue(finished.hits.isNotEmpty())

        controller.reset()

        assertEquals(SearchState(), controller.state.value)
    }

    /** 等待一次完整扫描结束：开搜后 totalChapters 会被置为章节数，据此区分"未开始"与"已完成" */
    private fun awaitFinished(): SearchState = runBlocking {
        withTimeout(3_000) {
            controller.state.first { it.totalChapters > 0 && !it.isSearching }
        }
    }

    private companion object {
        /** 两章：0..9 为章一正文，10..23 为章二正文，TARGET 位于章二正文内偏移 4 */
        const val TEXT = "aaaaaaaaaabbbbTARGETbbbb"

        val CHAPTERS = listOf(
            Chapter(title = "章一", charOffset = 0),
            Chapter(title = "章二", charOffset = 10)
        )
    }
}

/** 内存假书：只提供搜索编排需要的最小表面，把文件系统与解码器挡在测试之外 */
private class FakeBook(
    private val text: String,
    override val chapters: List<Chapter>,
    private val stream: Reader? = null,
    private val contents: Map<Int, ChapterContent> = emptyMap()
) : SearchableBook {

    override val totalChars: Int = text.length

    override fun chapterEndOffsets(): IntArray = BookSource.chapterEndOffsets(chapters, totalChars)

    override fun openCharStream(): Reader? = stream

    override suspend fun readChapterForSearch(index: Int): ChapterContent? = contents[index]
}

/** 慢速字符流：按块吐字并小睡，用于在扫描进行中注入取消 */
private class SlowStringReader(
    private val text: String,
    private val chunkSize: Int,
    private val delayMs: Long
) : Reader() {

    private var position = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        Thread.sleep(delayMs)
        if (position >= text.length) return -1
        val count = minOf(len, chunkSize, text.length - position)
        text.toCharArray(cbuf, off, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
