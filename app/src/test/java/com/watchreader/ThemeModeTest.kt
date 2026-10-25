package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题模式持久化协议测试：DataStore 以 Int 存档。
 * ADR-018 将五档收敛为 Material 3 亮 / 暗双档，历史值必须无损归一。
 */
class ThemeModeTest {

    @Test
    fun `历史五档持久化值归一为亮暗双档`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue(0))   // 羊皮纸
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue(1))    // 极光黑
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue(2))    // 红光夜视
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue(3))    // 深空蓝
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue(4))   // 晴空蓝
    }

    @Test
    fun `非法持久化值回落浅色`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue(-1))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue(99))
    }

    @Test
    fun `双档持久化值与显示名锁定`() {
        assertEquals(0, ThemeMode.LIGHT.value)
        assertEquals(1, ThemeMode.DARK.value)
        assertEquals(2, ThemeMode.entries.size)
        assertEquals("浅色", ThemeMode.LIGHT.label)
        assertEquals("深色", ThemeMode.DARK.label)
        assertFalse(ThemeMode.LIGHT.isDark)
        assertTrue(ThemeMode.DARK.isDark)
    }
}
