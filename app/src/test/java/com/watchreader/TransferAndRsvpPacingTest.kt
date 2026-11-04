package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传书页与速读页的纯口径锁定：文案/载荷、进度钳制、操作条接线、节拍与跳词判定。
 * 全部断言落在纯函数返回值上，不触碰 Compose / Context。
 */
class TransferPanelTextTest {

    @Test
    fun pageUrl_carriesPortAndToken() {
        assertEquals(
            "http://192.168.1.20:8888/?token=abc123",
            TransferPanelText.pageUrl("192.168.1.20", 8888, "abc123")
        )
    }

    @Test
    fun pageUrl_omitsTokenQueryWhenAbsent() {
        assertEquals("http://10.0.0.7:8888", TransferPanelText.pageUrl("10.0.0.7", 8888, null))
        assertEquals("http://10.0.0.7:8888", TransferPanelText.pageUrl("10.0.0.7", 8888, ""))
    }

    @Test
    fun serverReady_requiresRunningServerAndAddress() {
        assertTrue(TransferPanelText.serverReady(true, "192.168.1.2"))
        assertFalse(TransferPanelText.serverReady(true, null))
        assertFalse(TransferPanelText.serverReady(true, ""))
        assertFalse(TransferPanelText.serverReady(false, "192.168.1.2"))
    }

    @Test
    fun targetProgress_floorIsFivePercentWhileTransferring() {
        assertEquals(0.05f, TransferPanelText.targetProgress(true, 0f), 0.0001f)
        assertEquals(0.4f, TransferPanelText.targetProgress(true, 0.4f), 0.0001f)
        assertEquals(1.0f, TransferPanelText.targetProgress(true, 1.4f), 0.0001f)
        assertEquals(0f, TransferPanelText.targetProgress(false, 0.8f), 0f)
    }

    @Test
    fun percentOf_truncatesToInteger() {
        assertEquals(5, TransferPanelText.percentOf(0.05f))
        assertEquals(99, TransferPanelText.percentOf(0.999f))
        assertEquals(100, TransferPanelText.percentOf(1.0f))
    }

    @Test
    fun fileNameLabel_fallsBackToReceiving() {
        assertEquals("正在接收…", TransferPanelText.fileNameLabel(""))
        assertEquals("三体.epub", TransferPanelText.fileNameLabel("三体.epub"))
    }

    @Test
    fun transferStatusLabel_flipsAtNinetyNine() {
        assertEquals("传输中…", TransferPanelText.transferStatusLabel(98))
        assertEquals("已存入书架", TransferPanelText.transferStatusLabel(99))
        assertEquals("已存入书架", TransferPanelText.transferStatusLabel(100))
    }

    @Test
    fun readyLine_mergesUploadedCount() {
        assertEquals("服务已开启 · 等待手机扫码", TransferPanelText.readyLine(0))
        assertEquals("已接收 3 本 · 等待扫码", TransferPanelText.readyLine(3))
    }
}

class TransferBarSpecsTest {

    @Test
    fun runningServer_hasSingleBackKey() {
        val specs = transferBarSpecs(
            isServerRunning = true,
            focusedIndex = 0,
            focusCount = 1,
            onToggleServer = {},
            onBack = {}
        )
        assertEquals(1, specs.size)
        assertEquals("‹ 返回书架", specs[0].label)
        assertEquals(PillEmphasis.Primary, specs[0].emphasis)
        assertTrue(specs[0].focused)
    }

    @Test
    fun stoppedServer_putsRestartFirstAndBackLast() {
        val toggles = intArrayOf(0)
        val backs = intArrayOf(0)
        val specs = transferBarSpecs(
            isServerRunning = false,
            focusedIndex = 1,
            focusCount = 2,
            onToggleServer = { toggles[0]++ },
            onBack = { backs[0]++ }
        )
        assertEquals(2, specs.size)
        assertEquals("重启服务", specs[0].label)
        assertEquals("‹ 返回书架", specs[1].label)
        assertFalse(specs[0].focused)
        assertTrue(specs[1].focused)

        specs[0].onClick()
        specs[1].onClick()
        assertEquals(1, toggles[0])
        assertEquals(1, backs[0])
    }

    @Test
    fun stoppedServer_highlightMovesWithCrownFocus() {
        val atZero = transferBarSpecs(false, 0, 2, {}, {})
        assertTrue(atZero[0].focused)
        assertFalse(atZero[1].focused)
    }
}

class RsvpPacingTest {

    @Test
    fun dragConstants_areUnchanged() {
        assertEquals(55f, RsvpPacing.DRAG_THRESHOLD_PX, 0f)
        assertEquals(5, RsvpPacing.DRAG_STEP_TOKENS)
    }

    @Test
    fun nextSpeedTier_cyclesThroughFivePresets() {
        val expected = mapOf(
            250f to 350f,
            299f to 350f,
            300f to 450f,
            399f to 450f,
            400f to 600f,
            549f to 600f,
            550f to 800f,
            749f to 800f,
            750f to 250f,
            1200f to 250f
        )
        for ((from, to) in expected) {
            assertEquals("from=$from", to, RsvpPacing.nextSpeedTier(from), 0f)
        }
    }

    @Test
    fun baseDelayMs_weightsLongerTokens() {
        val short = RsvpPacing.baseDelayMs(350f, 1)
        val long = RsvpPacing.baseDelayMs(350f, 20)
        assertTrue("short=$short long=$long", long > short)
        // 词长 10 已顶到 1.8 倍上限，与词长 40 同值
        assertEquals(RsvpPacing.baseDelayMs(350f, 40), RsvpPacing.baseDelayMs(350f, 10), 0f)
    }

    @Test
    fun actualDelayMs_matchesHandComputedValueAndClamps() {
        // 60000/350 * 1.8 ≈ 308.57ms，标点倍率 1.0
        assertEquals(308L, RsvpPacing.actualDelayMs(350f, 10, 1.0f))
        // 极快语速 + 极短词：21ms 被 40ms 下限兜住
        assertEquals(40L, RsvpPacing.actualDelayMs(2000f, 1, 1.0f))
        // 极慢语速 + 长词 + 句末停停顿：落在上限内但明显更长
        val slow = RsvpPacing.actualDelayMs(200f, 20, 2.2f)
        assertTrue("slow=$slow", slow in 1000L..1200L)
    }

    @Test
    fun actualDelayMs_pauseMultiplierLengthensHold() {
        val plain = RsvpPacing.actualDelayMs(350f, 6, 1.0f)
        val comma = RsvpPacing.actualDelayMs(350f, 6, 1.6f)
        val sentence = RsvpPacing.actualDelayMs(350f, 6, 2.2f)
        assertTrue("$plain < $comma < $sentence", plain < comma && comma < sentence)
    }

    @Test
    fun jumpTarget_clampsBothEnds() {
        assertEquals(5, RsvpPacing.jumpTarget(0, 5, 10))
        assertEquals(9, RsvpPacing.jumpTarget(8, 5, 10))
        assertEquals(0, RsvpPacing.jumpTarget(2, -5, 10))
        assertEquals(4, RsvpPacing.jumpTarget(4, 5, 5))
        // 空词表原地不动
        assertEquals(3, RsvpPacing.jumpTarget(3, 5, 0))
    }

    @Test
    fun progressPercent_and_label() {
        assertEquals(0, RsvpPacing.progressPercent(0, 100))
        assertEquals(50, RsvpPacing.progressPercent(50, 100))
        assertEquals(100, RsvpPacing.progressPercent(100, 100))
        assertEquals(0, RsvpPacing.progressPercent(0, 0))
        assertEquals("1/10 · 0%", RsvpPacing.progressLabel(0, 10))
        assertEquals("10/10 · 90%", RsvpPacing.progressLabel(9, 10))
        // 空词表沿用旧口径（不改文案）
        assertEquals("1/0 · 0%", RsvpPacing.progressLabel(0, 0))
    }

    @Test
    fun speedLabel_showsRateWhilePlaying() {
        assertEquals("350 字/分", RsvpPacing.speedLabel(350f, true))
        assertEquals("800 字/分", RsvpPacing.speedLabel(800.4f, true))
        assertEquals("继续", RsvpPacing.speedLabel(350f, false))
    }
}
