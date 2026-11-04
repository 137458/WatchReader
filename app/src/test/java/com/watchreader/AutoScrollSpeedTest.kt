package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollSpeedTest {

    @Test
    fun clampsBelowMinimum() {
        assertEquals(15f, AutoScrollSpeed.clamp(3f), 0f)
    }

    @Test
    fun clampsAboveMaximum() {
        assertEquals(250f, AutoScrollSpeed.clamp(900f), 0f)
    }

    @Test
    fun keepsInRangeValueUntouched() {
        assertEquals(120f, AutoScrollSpeed.clamp(120f), 0f)
    }

    @Test
    fun storedValueUsesSameRange() {
        assertEquals(15f, AutoScrollSpeed.fromStored(10f), 0f)
        assertEquals(200f, AutoScrollSpeed.fromStored(200f), 0f)
        assertEquals(250f, AutoScrollSpeed.fromStored(260f), 0f)
    }
}
