package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节偏移精确映射测试：正文排版坐标与原文切片坐标双向转换
 *
 * 背景约束：formattedBody 在排版时为每段追加了全角缩进并重排换行，
 * 正文字符索引不再等于原文偏移。持久化的阅读位置必须落在原文坐标域，
 * 否则全局进度、跨章节定位与 DataStore 保存值全部失真。
 */
class ChapterOffsetMapperTest {

    private fun buildContent(rawText: String): ChapterContent {
        val chapters = listOf(Chapter(index = 0, title = "第X章 标题", charOffset = 0))
        return formatChapterRawText(rawText, chapters, 0, 0, rawText.length)
    }

    @Test
    fun testEmptyMappingFallsBackToIdentity() {
        assertEquals(5, ChapterOffsetMapper.bodyToRaw(5, IntArray(0), IntArray(0), 100, rawLength = 100))
        assertEquals(7, ChapterOffsetMapper.rawToBody(7, IntArray(0), IntArray(0), 100))
    }

    @Test
    fun testFormatterRecordsAlignedParagraphStarts() {
        val raw = "第X章 标题\n\n　　内容一行。\n\n\n　　第二段正文。\n"
        val content = buildContent(raw)

        // 正文：　　内容一行。(7) + \n\n(2) + 　　第二段正文。(8) => 第二段起于 9
        assertEquals(2, content.bodyParagraphStarts.size)
        assertEquals(0, content.bodyParagraphStarts[0])
        assertEquals(9, content.bodyParagraphStarts[1])

        // 原文切片：标题占 0..5，两个空行，段一首字符在 10；三个换行后段二首字符在 20
        assertEquals(2, content.rawParagraphStarts.size)
        assertEquals(10, content.rawParagraphStarts[0])
        assertEquals(20, content.rawParagraphStarts[1])
    }

    @Test
    fun testBodyToRawInsideParagraph() {
        val raw = "第X章 标题\n\n　　内容一行。\n\n\n　　第二段正文。\n"
        val content = buildContent(raw)
        val bl = content.formattedBody.length
        val rl = raw.length

        // 段首"　　"缩进映射回段首原文位置
        assertEquals(10, ChapterOffsetMapper.bodyToRaw(0, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        assertEquals(10, ChapterOffsetMapper.bodyToRaw(1, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))

        // 正文下标2对应原文段首字符，此后一一对应
        assertEquals(11, ChapterOffsetMapper.bodyToRaw(3, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        assertEquals(14, ChapterOffsetMapper.bodyToRaw(6, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))

        // 第二段：下标9为段首，下标12为"二"（原文21）
        assertEquals(20, ChapterOffsetMapper.bodyToRaw(9, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        assertEquals(21, ChapterOffsetMapper.bodyToRaw(12, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
    }

    @Test
    fun testRawToBodyInsideParagraph() {
        val raw = "第X章 标题\n\n　　内容一行。\n\n\n　　第二段正文。\n"
        val content = buildContent(raw)
        val bl = content.formattedBody.length

        assertEquals(2, ChapterOffsetMapper.rawToBody(10, content.bodyParagraphStarts, content.rawParagraphStarts, bl))
        assertEquals(5, ChapterOffsetMapper.rawToBody(13, content.bodyParagraphStarts, content.rawParagraphStarts, bl))
        assertEquals(11, ChapterOffsetMapper.rawToBody(20, content.bodyParagraphStarts, content.rawParagraphStarts, bl))
        assertEquals(14, ChapterOffsetMapper.rawToBody(23, content.bodyParagraphStarts, content.rawParagraphStarts, bl))
    }

    @Test
    fun testGapPositionsClampToNearestParagraphContent() {
        val raw = "第X章 标题\n\n　　内容一行。\n\n\n　　第二段正文。\n"
        val content = buildContent(raw)
        val bl = content.formattedBody.length

        // 原文空行/行尾空白区(15..19)应吸附到段一末字符或段二首字符，绝不越界
        val mid = ChapterOffsetMapper.rawToBody(16, content.bodyParagraphStarts, content.rawParagraphStarts, bl)
        assertTrue("mid=$mid", mid == 6 || mid == 11)
        val near = ChapterOffsetMapper.rawToBody(18, content.bodyParagraphStarts, content.rawParagraphStarts, bl)
        assertTrue("near=$near", near == 6 || near == 11)
    }

    @Test
    fun testRoundTripOnRawContentPositions() {
        val raw = "第X章 标题\n\n　　内容一行。\n\n\n　　第二段正文，这一段更长一些，用于验证连续位置映射。\n"
        val content = buildContent(raw)
        val bl = content.formattedBody.length
        val rl = raw.length

        // 段一内容 10..14 与段二内容 20..44 逐字符往返一致
        for (t in 10..14) {
            val body = ChapterOffsetMapper.rawToBody(t, content.bodyParagraphStarts, content.rawParagraphStarts, bl)
            assertEquals("t=$t", t, ChapterOffsetMapper.bodyToRaw(body, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        }
        for (t in 20..44) {
            val body = ChapterOffsetMapper.rawToBody(t, content.bodyParagraphStarts, content.rawParagraphStarts, bl)
            assertEquals("t=$t", t, ChapterOffsetMapper.bodyToRaw(body, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        }
    }

    @Test
    fun testOutOfBoundsClamped() {
        val raw = "第X章 标题\n\n　　内容一行。\n"
        val content = buildContent(raw)
        val bl = content.formattedBody.length
        val rl = raw.length

        // 负值与超界均收敛到合法区间（正文 0 即段首缩进，映射段首原文位置）
        assertEquals(10, ChapterOffsetMapper.bodyToRaw(-3, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl))
        assertTrue(ChapterOffsetMapper.bodyToRaw(bl + 10, content.bodyParagraphStarts, content.rawParagraphStarts, bl, rawLength = rl) in 0..rl)
        assertEquals(0, ChapterOffsetMapper.rawToBody(-3, content.bodyParagraphStarts, content.rawParagraphStarts, bl))
        val lastBody = ChapterOffsetMapper.rawToBody(bl + 10, content.bodyParagraphStarts, content.rawParagraphStarts, bl)
        assertTrue(lastBody in 0..bl)
    }
}
