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
}
