package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPositionTest {

    @Test
    fun startsAtInitialValue() {
        val position = ReadingPosition(42)
        assertEquals(42, position.current)
        assertEquals(42, position.latestValue)
    }

    @Test
    fun updateMovesBothViews() {
        val position = ReadingPosition(0)
        position.update(1200)
        assertEquals(1200, position.current)
        assertEquals(1200, position.latestValue)
    }

    @Test
    fun repeatedUpdateKeepsLastValue() {
        val position = ReadingPosition(0)
        position.update(10)
        position.update(10)
        position.update(7)
        assertEquals(7, position.latestValue)
        assertEquals(7, position.current)
    }
}
