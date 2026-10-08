package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式章节切片装配器测试：全书单趟解码时按字符偏移边界切出各章原文切片，
 * 是搜索扫描 O(n²)→O(n) 改造的核心纯件（替代每章从头 skip 解码）
 */
class ChapterSliceAssemblerTest {

    private fun feedAll(assembler: ChapterSliceAssembler, book: String) {
        assembler.feed(book.toCharArray(), 0, book.length)
    }

    @Test
    fun `整块喂入时按半开区间边界精确切出各章`() {
        val book = "第一章甲乙丙丁第二章戊己庚辛壬癸第三章子丑寅卯"
        // 边界 = 各章右端（半开区间）：第一章 7 字、第二章 9 字、末章 7 字
        val assembler = ChapterSliceAssembler(intArrayOf(7, 16, book.length))
        feedAll(assembler, book)
        assembler.finish()
        assembler.drain().also { slices ->
            assertEquals(3, slices.size)
            assertEquals(0, slices[0].first)
            assertEquals("第一章甲乙丙丁", slices[0].second)
            assertEquals(1, slices[1].first)
            assertEquals("第二章戊己庚辛壬癸", slices[1].second)
            assertEquals(2, slices[2].first)
            assertEquals("第三章子丑寅卯", slices[2].second)
        }
    }

    @Test
    fun `不等块跨章边界喂入时切片拼接完整`() {
        val book = "AAAAA.BBBBBB.CCCC.D"
        // A×5 + 点 + B×6 + 点 + C×4 + 点 + D：章界落在分隔符之后
        val assembler = ChapterSliceAssembler(intArrayOf(6, 13, 18, book.length))
        // 1/2/4/剩余字符的四段不等块，块边界与章边界完全错开
        val chars = book.toCharArray()
        var from = 0
        for (len in intArrayOf(1, 2, 4, book.length)) {
            val end = minOf(from + len, book.length)
            if (end > from) {
                assembler.feed(chars, from, end - from)
                from = end
            }
        }
        assembler.finish()
        assembler.drain().also { slices ->
            assertEquals(listOf("AAAAA.", "BBBBBB.", "CCCC.", "D"), slices.map { it.second })
            assertEquals(listOf(0, 1, 2, 3), slices.map { it.first })
        }
    }

    @Test
    fun `末章在finish时冲刷且finish与空drain幂等`() {
        val book = "头三字尾章正文"
        val assembler = ChapterSliceAssembler(intArrayOf(3, book.length))
        feedAll(assembler, book)
        assembler.finish()
        val first = assembler.drain()
        assembler.finish()
        assertTrue(first.isNotEmpty())
        // finish 不再产出新切片，drain 取走即清
        assertTrue(assembler.drain().isEmpty())
    }

    @Test
    fun `相邻边界相等时空章节产出空切片保持章序对齐`() {
        val book = "ABCD"
        val assembler = ChapterSliceAssembler(intArrayOf(2, 2, book.length))
        feedAll(assembler, book)
        assembler.finish()
        assembler.drain().also { slices ->
            assertEquals(3, slices.size)
            assertEquals("AB", slices[0].second)
            assertEquals("", slices[1].second)
            assertEquals("CD", slices[2].second)
        }
    }

    @Test
    fun `实际解码少于预期边界时finish冲刷残段为末章`() {
        val book = "XYZ"
        val assembler = ChapterSliceAssembler(intArrayOf(2, 99))
        feedAll(assembler, book)
        assembler.finish()
        assembler.drain().also { slices ->
            assertEquals(2, slices.size)
            assertEquals("XY", slices[0].second)
            assertEquals("Z", slices[1].second)
        }
    }
}
