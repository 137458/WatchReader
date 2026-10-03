package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书内全文搜索引擎测试：命中定位、坐标换算、大小写不敏感与摘要生成
 */
class BookSearchEngineTest {

    @Test
    fun testFindsAllHitsInOrder() {
        val body = "甲说了一句话，话里有话。乙回应了这句话。"
        val hits = BookSearchEngine.findMatches(
            body = body,
            query = "话",
            bodyStartInRaw = 0,
            baseCharOffset = 1000,
            chapterIndex = 3,
            chapterTitle = "第三章"
        )
        // 4 个"话"全部命中且按出现顺序
        assertEquals(4, hits.size)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.charOffset < b.charOffset })
        hits.forEach {
            assertEquals(3, it.chapterIndex)
            assertEquals("第三章", it.chapterTitle)
        }
    }

    @Test
    fun testCharOffsetMapsThroughHeaderAndBase() {
        // 正文是原文切片剥除 12 字符标题首行后的后缀
        val body = "正文第一段。\n\n正文第二段提到关键词。"
        val hits = BookSearchEngine.findMatches(
            body = body,
            query = "关键词",
            bodyStartInRaw = 12,
            baseCharOffset = 5000,
            chapterIndex = 7,
            chapterTitle = "第七章"
        )
        assertEquals(1, hits.size)
        val idx = body.indexOf("关键词")
        assertEquals(5000 + 12 + idx, hits[0].charOffset)
    }

    @Test
    fun testCaseInsensitiveLatinMatch() {
        val body = "He said Hello to Helen."
        val hits = BookSearchEngine.findMatches(body, "hello", 0, 0, 0, "t")
        assertEquals(1, hits.size)
        assertEquals(8, hits[0].charOffset)
    }

    @Test
    fun testSnippetContainsQueryWithoutLineBreaks() {
        // 20 字符铺垫 + 换行，命中距起点 21 > CONTEXT_BEFORE(14)，摘要前缘应截断补省略号
        val body = "这是一段足够长的铺垫内容用于验证省略号。\n关键词出现在换行之后。"
        val hits = BookSearchEngine.findMatches(body, "关键词", 0, 0, 0, "t")
        assertEquals(1, hits.size)
        val snippet = hits[0].snippet
        assertTrue(snippet.contains("关键词"))
        // 换行替换为空格，不再包含控制字符
        assertFalse(snippet.contains('\n'))
        // 前文被截断时带省略号
        assertTrue(snippet.startsWith("…"))
    }

    @Test
    fun testMaxHitsCapsResultCount() {
        val body = "重复词重复词重复词重复词"
        val hits = BookSearchEngine.findMatches(body, "重复词", 0, 0, 0, "t", maxHits = 2)
        assertEquals(2, hits.size)
    }

    @Test
    fun testEmptyInputsReturnNothing() {
        assertTrue(BookSearchEngine.findMatches("", "词", 0, 0, 0, "t").isEmpty())
        assertTrue(BookSearchEngine.findMatches("正文", "  ", 0, 0, 0, "t").isEmpty())
        assertTrue(BookSearchEngine.findMatches("正文", "不存在", 0, 0, 0, "t").isEmpty())
        assertTrue(BookSearchEngine.findMatches("正文", "正文", 0, 0, 0, "t", maxHits = 0).isEmpty())
    }

    @Test
    fun testIndexOfIgnoreCaseBasics() {
        assertEquals(2, BookSearchEngine.indexOfIgnoreCase("xxABCyy", "abc"))
        assertEquals(-1, BookSearchEngine.indexOfIgnoreCase("xxABCyy", "zzz"))
        // 中(0) 文(1) A(2) b(3) C(4)："AbC" 起始于索引 2
        assertEquals(2, BookSearchEngine.indexOfIgnoreCase("中文AbC测试", "abc"))
        assertEquals(-1, BookSearchEngine.indexOfIgnoreCase("abc", "", 0))
    }
}
