package com.watchreader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 锁定期望：表盘时间戳与书签时间戳都按"调用瞬间"的 Locale.getDefault() 渲染。
 *
 * 背景：旧实现把 SimpleDateFormat 缓存为字段/remember 值，捕获的是首次组合时的 locale，
 * 系统切换语言（尤其是数字系统不同的 locale，如英语 <-> 阿拉伯语）后界面继续按旧 locale
 * 渲染，直到进程重建。本测试通过临时改默认 locale 的方式，断言同一时刻戳在不同 locale 下
 * 产出不同文本。
 */
class LocaleFormatsTest {

    private lateinit var original: Locale

    @Before
    fun captureLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun clockTickFollowsCurrentDefaultLocale() {
        val fixedMillis = 1_762_700_000_000L // 任意固定时刻

        Locale.setDefault(Locale.US)
        val us = LocaleFormats.clockTickLabel(fixedMillis)

        Locale.setDefault(Locale.forLanguageTag("ar"))
        val ar = LocaleFormats.clockTickLabel(fixedMillis)

        // 阿语数字系统与 ASCII 不同，同一时刻的渲染必须发生可见变化
        assertNotEquals(us, ar)
        assertTrue(us.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun clockTickIsStableWithinSameLocale() {
        val fixedMillis = 1_762_700_000_000L

        Locale.setDefault(Locale.US)
        val first = LocaleFormats.clockTickLabel(fixedMillis)
        val second = LocaleFormats.clockTickLabel(fixedMillis)

        assertEquals(first, second)
    }

    @Test
    fun bookmarkDateFollowsCurrentDefaultLocale() {
        val fixedMillis = 1_762_700_000_000L

        Locale.setDefault(Locale.US)
        val us = LocaleFormats.bookmarkDateLabel(fixedMillis)

        Locale.setDefault(Locale.forLanguageTag("ar"))
        val ar = LocaleFormats.bookmarkDateLabel(fixedMillis)

        assertNotEquals(us, ar)
        // 英文口径：MM-dd HH:mm，月日两位数字 + 空格 + 时分
        assertTrue(us.matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }
}
