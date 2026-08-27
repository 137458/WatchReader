package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsvpTest {

    @Test
    fun testRsvpTokenizerChinese() {
        val text = "天地不仁，以万物为刍狗！"
        val tokens = tokenizeRsvpText(text, 100)

        assertTrue(tokens.isNotEmpty())
        assertEquals(100, tokens[0].charOffset)
        // 验证标点符号停顿权重
        val tokenWithComma = tokens.firstOrNull { it.text.contains("，") }
        val tokenWithExclamation = tokens.firstOrNull { it.text.contains("！") }

        assertTrue(tokenWithComma != null && tokenWithComma.pauseMultiplier >= 1.4f)
        assertTrue(tokenWithExclamation != null && tokenWithExclamation.pauseMultiplier >= 2.0f)
    }

    @Test
    fun testRsvpTokenizerEnglish() {
        val text = "Hello world! This is a test."
        val tokens = tokenizeRsvpText(text, 0)

        assertTrue(tokens.isNotEmpty())
        assertEquals("Hello", tokens[0].text)
        assertTrue(tokens[1].text.startsWith("world"))
    }

    @Test
    fun testRsvpEmpty() {
        val tokens = tokenizeRsvpText("", 0)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun testOrpIndexCalculation() {
        // 单字 / 双字 -> 索引 0
        assertEquals(0, calculateOrpIndex("天"))
        assertEquals(0, calculateOrpIndex("江湖"))

        // 3~5 字 -> 索引 1
        assertEquals(1, calculateOrpIndex("万物生"))
        assertEquals(1, calculateOrpIndex("风起云涌"))
        assertEquals(1, calculateOrpIndex("Hello"))

        // 6~9 字 -> 索引 2
        assertEquals(2, calculateOrpIndex("天地不仁以万物"))
        assertEquals(2, calculateOrpIndex("Running"))

        // 10+ 字 -> 约 35% 处
        val longWord = "Supercalifragilisticexpialidocious"
        val orp = calculateOrpIndex(longWord)
        assertTrue(orp in 10..15)
    }

    @Test
    fun testRsvpTokenContainsOrp() {
        val tokens = tokenizeRsvpText("初入江湖，风起云涌！", 0)
        assertTrue(tokens.isNotEmpty())
        for (token in tokens) {
            assertTrue(token.orpIndex in 0 until token.text.length)
        }
    }
}
