package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSecondsBufferTest {

    @Test
    fun holdsBackUntilThreshold() {
        val buffer = ReadingSecondsBuffer()
        assertEquals(0L, buffer.record(10L))
        assertEquals(0L, buffer.record(10L))
        assertEquals(0L, buffer.record(10L))
        assertEquals(0L, buffer.record(10L))
        assertEquals(0L, buffer.record(10L))
    }

    @Test
    fun flushesExactlyAtThreshold() {
        val buffer = ReadingSecondsBuffer()
        repeat(5) { buffer.record(10L) }
        assertEquals(60L, buffer.record(10L))
        assertEquals(0L, buffer.record(10L))
    }

    @Test
    fun drainReturnsRemainderAndResets() {
        val buffer = ReadingSecondsBuffer()
        buffer.record(10L)
        buffer.record(10L)
        assertEquals(20L, buffer.drain())
        assertEquals(0L, buffer.drain())
    }

    @Test
    fun ignoresNonPositiveInput() {
        val buffer = ReadingSecondsBuffer()
        assertEquals(0L, buffer.record(0L))
        assertEquals(0L, buffer.record(-5L))
        assertEquals(0L, buffer.drain())
    }
}
