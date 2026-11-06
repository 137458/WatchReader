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

    /**
     * P1-1 契约：实例跨章节导航稳定（`remember {}` 不重建），跳转时显式写回同一实例，
     * 之后滚动监听继续写这份实例时进度必须还在变。
     *
     * 旧实现用 `remember(initialCharOffset)` 换实例，滚动监听仍绑旧实例，
     * 表现为「跳章后进度冻结」——这里用同一对象的连续写入序列锁死该语义。
     */
    @Test
    fun navigationBaselineThenScrollWritesKeepAdvancingSameInstance() {
        val position = ReadingPosition(0)
        // 第一次滚动：正文位置推进
        position.update(500)
        // 跳章：显式写回新章节基线
        position.update(8200)
        // 跳章后继续滚动：必须继续由同一实例承接，而不是冻结在基线
        position.update(8650)

        assertEquals(8650, position.current)
        assertEquals(8650, position.latestValue)
    }
}
