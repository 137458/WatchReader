package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页外观差量下发测试
 *
 * 端口由假件实现：只记录"下发了哪些属性、值是多少"，不构建任何 View。
 * 覆盖三类语义：无变化零下发、单字段变化只下发该字段、多项变化同时下发；
 * 另钉住字面句柄的引用相等语义（相等但不同实例必须重新下发）。
 */
class ReaderPaneAppearanceTest {

    /** 记录下发调用的假端口 */
    private class RecordingPort : ReaderPaneStylePort {
        val calls = mutableListOf<String>()

        override fun setBackgroundArgb(argb: Int) {
            calls += "background=$argb"
        }

        override fun setChromeArgb(argb: Int) {
            calls += "chrome=$argb"
        }

        override fun setBodyColorArgb(argb: Int) {
            calls += "bodyColor=$argb"
        }

        override fun setTitleColorArgb(argb: Int) {
            calls += "titleColor=$argb"
        }

        override fun setMetaColorArgb(argb: Int) {
            calls += "metaColor=$argb"
        }

        override fun setTitleTextSizeSp(sp: Float) {
            calls += "titleSize=$sp"
        }

        override fun setBodyTextSizeSp(sp: Float) {
            calls += "bodySize=$sp"
        }

        override fun setLineSpacingMultiplier(multiplier: Float) {
            calls += "lineSpacing=$multiplier"
        }

        override fun setLetterSpacingEm(em: Float) {
            calls += "letterSpacing=$em"
        }

        override fun setBodyTypefaceToken(token: Any?) {
            calls += "typeface=" + (token ?: "null")
        }
    }

    private fun appearance(
        backgroundArgb: Int = 0xFF101010.toInt(),
        chromeArgb: Int = 0xFF202020.toInt(),
        bodyColorArgb: Int = 0xFFDDDDDD.toInt(),
        titleColorArgb: Int = 0xFF88CCFF.toInt(),
        metaColorArgb: Int = 0xFF999999.toInt(),
        fontSizeSp: Int = 18,
        lineSpacingMultiplier: Float = 1.45f,
        letterSpacingEm: Float? = 0.03f,
        bodyTypefaceToken: Any? = "sans"
    ) = ReaderPaneAppearance(
        backgroundArgb = backgroundArgb,
        chromeArgb = chromeArgb,
        bodyColorArgb = bodyColorArgb,
        titleColorArgb = titleColorArgb,
        metaColorArgb = metaColorArgb,
        fontSizeSp = fontSizeSp,
        lineSpacingMultiplier = lineSpacingMultiplier,
        letterSpacingEm = letterSpacingEm,
        bodyTypefaceToken = bodyTypefaceToken
    )

    @Test
    fun testIdenticalAppearanceDispatchesNothing() {
        val port = RecordingPort()
        val previous = appearance()

        val applied = ReaderPaneStyles.apply(port, previous, appearance())

        assertEquals(emptyList<String>(), port.calls)
        assertEquals(previous, applied)
    }

    @Test
    fun testSingleColorChangeDispatchesOnlyThatField() {
        val port = RecordingPort()
        val previous = appearance(bodyColorArgb = 0xFFDDDDDD.toInt())
        val target = appearance(bodyColorArgb = 0xFF000000.toInt())

        ReaderPaneStyles.apply(port, previous, target)

        assertEquals(listOf("bodyColor=" + 0xFF000000.toInt()), port.calls)
    }

    @Test
    fun testFontSizeChangeDispatchesTitleAndBodyTogether() {
        val port = RecordingPort()

        ReaderPaneStyles.apply(port, appearance(fontSizeSp = 18), appearance(fontSizeSp = 22))

        // 标题恒比正文大 2sp：一次字号变化必须同时下发两个 View，否则行高不同步
        assertEquals(listOf("titleSize=24.0", "bodySize=22.0"), port.calls)
    }

    @Test
    fun testFontSizeAndTypefaceChangeDispatchBoth() {
        val port = RecordingPort()
        val previous = appearance(fontSizeSp = 18, bodyTypefaceToken = "sans")

        ReaderPaneStyles.apply(port, previous, appearance(fontSizeSp = 22, bodyTypefaceToken = "serif"))

        assertEquals(listOf("titleSize=24.0", "bodySize=22.0", "typeface=serif"), port.calls)
    }

    @Test
    fun testFirstApplyDispatchesEveryField() {
        val port = RecordingPort()

        ReaderPaneStyles.apply(port, null, appearance())

        assertEquals(
            listOf(
                "background=" + 0xFF101010.toInt(),
                "chrome=" + 0xFF202020.toInt(),
                "bodyColor=" + 0xFFDDDDDD.toInt(),
                "titleColor=" + 0xFF88CCFF.toInt(),
                "metaColor=" + 0xFF999999.toInt(),
                "titleSize=20.0",
                "bodySize=18.0",
                "lineSpacing=1.45",
                "letterSpacing=0.03",
                "typeface=sans"
            ),
            port.calls
        )
    }

    @Test
    fun testTypefaceComparedByIdentityNotEquality() {
        val port = RecordingPort()
        // 两个相等但不同实例的字面句柄：语义上是"已下发的那一个字面"，必须重新下发
        val previousTypeface: Any = String("serif".toCharArray())
        val resolvedTypeface: Any = String("serif".toCharArray())
        assertFalse(previousTypeface === resolvedTypeface)
        assertEquals(previousTypeface, resolvedTypeface)

        ReaderPaneStyles.apply(
            port,
            appearance(bodyTypefaceToken = previousTypeface),
            appearance(bodyTypefaceToken = resolvedTypeface)
        )

        assertEquals(listOf("typeface=serif"), port.calls)
    }

    @Test
    fun testUnappliedLetterSpacingIsDispatchedOnFirstBind() {
        val port = RecordingPort()
        val previous = appearance(letterSpacingEm = null)

        ReaderPaneStyles.apply(port, previous, appearance(letterSpacingEm = 0.06f))

        assertEquals(listOf("letterSpacing=0.06"), port.calls)
    }

    @Test
    fun testLineSpacingAndChromeChangeDispatchBoth() {
        val port = RecordingPort()
        val previous = appearance(lineSpacingMultiplier = 1.45f, chromeArgb = 0xFF202020.toInt())
        val target = appearance(lineSpacingMultiplier = 1.7f, chromeArgb = 0xFF303030.toInt())

        ReaderPaneStyles.apply(port, previous, target)

        assertEquals(listOf("chrome=" + 0xFF303030.toInt(), "lineSpacing=1.7"), port.calls)
        assertTrue(port.calls.none { it.startsWith("bodySize") })
    }
}
