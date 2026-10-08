package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题模式持久化协议测试：DataStore 以 Int 存档，
 * 值协议变更会破坏老安装的读取兼容，故逐值锁定。
 */
class ThemeModeTest {

    @Test
    fun `历史持久化值 0-1-2 分别映射羊皮纸 极光黑 红光夜视`() {
        assertEquals(ThemeMode.PARCHMENT, ThemeMode.fromValue(0))
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue(1))
        assertEquals(ThemeMode.RED_NIGHT, ThemeMode.fromValue(2))
    }

    @Test
    fun `非法持久化值回落羊皮纸`() {
        assertEquals(ThemeMode.PARCHMENT, ThemeMode.fromValue(-1))
        assertEquals(ThemeMode.PARCHMENT, ThemeMode.fromValue(99))
    }

    @Test
    fun `新增 HyperOS 主题占据持久化值 3 且属深色系`() {
        assertEquals(3, ThemeMode.MIUIX.value)
        assertEquals(ThemeMode.MIUIX, ThemeMode.fromValue(3))
        assert(ThemeMode.MIUIX.isDark)
    }

    @Test
    fun `HyperOS 亮色档占据持久化值 4 且属亮色系`() {
        assertEquals(4, ThemeMode.MIUIX_LIGHT.value)
        assertEquals(ThemeMode.MIUIX_LIGHT, ThemeMode.fromValue(4))
        assert(!ThemeMode.MIUIX_LIGHT.isDark)
    }

    @Test
    fun `主题档位共五档 供菜单循环切换取模`() {
        assertEquals(5, ThemeMode.entries.size)
        assertEquals(0, ThemeMode.PARCHMENT.value)
        assertEquals(1, ThemeMode.DARK.value)
        assertEquals(2, ThemeMode.RED_NIGHT.value)
        assertEquals(3, ThemeMode.MIUIX.value)
        assertEquals(4, ThemeMode.MIUIX_LIGHT.value)
    }
}
