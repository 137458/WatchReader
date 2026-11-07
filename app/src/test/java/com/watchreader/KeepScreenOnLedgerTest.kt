package com.watchreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定期望：FLAG_KEEP_SCREEN_ON 的归属按"持有者令牌"计数，
 * 只有最后一个持有者放手时才允许真正清旗。
 *
 * 背景：阅读页与速读页共享同一 window 的常亮标志，原先两处裸 add/clear 互相误清——
 * 速读退出时无条件 clearFlags，阅读页（尤其自动滚屏永久常亮）随之失效；
 * 阅读页 5 分钟无交互超时若在速读期间触发，也会清掉速读仍需要的旗。
 */
class KeepScreenOnLedgerTest {

    @Test
    fun `single owner holds until released`() {
        val ledger = KeepScreenOnLedger()
        val reader = Any()

        ledger.acquire(reader)
        assertTrue(ledger.isHeld)

        // 最后一个持有者放手 -> 允许清旗
        assertTrue(ledger.release(reader))
        assertFalse(ledger.isHeld)
    }

    @Test
    fun `flag survives rsvp leaving while reader holds`() {
        // 场景：阅读页持有常亮（如自动滚屏永久常亮），速读进入再退出；
        // 速读退出不得清掉阅读页的旗
        val ledger = KeepScreenOnLedger()
        val reader = Any()
        val rsvp = Any()

        ledger.acquire(reader)
        ledger.acquire(rsvp)

        // 速读退出：阅读页仍持有，不能发清旗信号
        assertFalse(ledger.release(rsvp))
        assertTrue(ledger.isHeld)

        // 阅读页也放手：才真正清旗
        assertTrue(ledger.release(reader))
        assertFalse(ledger.isHeld)
    }

    @Test
    fun `reader inactivity timeout firing during rsvp keeps flag`() {
        // 场景：阅读页 5 分钟无交互超时在速读期间触发；速读仍持有，旗必须保留
        val ledger = KeepScreenOnLedger()
        val reader = Any()
        val rsvp = Any()

        ledger.acquire(reader)
        ledger.acquire(rsvp)

        assertFalse(ledger.release(reader))
        assertTrue(ledger.isHeld)
    }

    @Test
    fun `repeated triggers do not stack holds`() {
        // 阅读页每次触摸/表冠交互都重新触发 acquire；重复触发不得叠加持份，
        // 否则超时释放一次后旗仍亮着，永不休眠
        val ledger = KeepScreenOnLedger()
        val reader = Any()

        ledger.acquire(reader)
        ledger.acquire(reader)
        ledger.acquire(reader)

        // 一次超时释放即应清空
        assertTrue(ledger.release(reader))
        assertFalse(ledger.isHeld)
    }

    @Test
    fun `releasing unknown or already released owner never clears`() {
        val ledger = KeepScreenOnLedger()
        val reader = Any()
        val stranger = Any()

        // 空台账上的陌生释放
        assertFalse(ledger.release(stranger))

        ledger.acquire(reader)
        // 他人持旗时的陌生释放
        assertFalse(ledger.release(stranger))
        assertTrue(ledger.isHeld)

        assertTrue(ledger.release(reader))
        // 重复释放幂等，不再发清旗信号
        assertFalse(ledger.release(reader))
    }
}
