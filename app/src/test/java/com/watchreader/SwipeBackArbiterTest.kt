package com.watchreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右滑退出裁决器测试：菜单页与配色浮层的水平手势必须同时满足
 * 1) 只认水平主导位移（否则列表竖向滚动会被抢走）；
 * 2) 累加越过门限才判定（否则手抖即退出）；
 * 3) 判定后立即复位（否则一次长滑连发多次退出）。
 */
class SwipeBackArbiterTest {

    private val threshold = 60f

    @Test
    fun `水平位移累加越过门限判定为一次退出`() {
        val arbiter = SwipeBackArbiter(threshold)
        assertFalse(arbiter.onDrag(30f, 2f))
        assertTrue(arbiter.onDrag(35f, 1f))
    }

    @Test
    fun `判定退出后累加值复位 再次右滑可独立触发`() {
        val arbiter = SwipeBackArbiter(threshold)
        arbiter.onDrag(30f, 0f)
        assertTrue(arbiter.onDrag(40f, 0f))
        // 复位后必须重新累加满门限，而不是残留旧值立刻再触发
        assertFalse(arbiter.onDrag(20f, 0f))
        assertTrue(arbiter.onDrag(50f, 0f))
    }

    @Test
    fun `竖向主导位移永不判定退出以保护列表滚动`() {
        val arbiter = SwipeBackArbiter(threshold)
        repeat(10) {
            assertFalse("竖向拖动第 $it 帧被误判为右滑退出", arbiter.onDrag(5f, 40f))
        }
        // dx == dy 属于不确定方向，同样不判退出
        assertFalse(arbiter.onDrag(80f, 80f))
    }

    @Test
    fun `反向左滑回吐累加值`() {
        val arbiter = SwipeBackArbiter(threshold)
        assertFalse(arbiter.onDrag(-40f, 0f))
        assertFalse(arbiter.onDrag(30f, 0f))   // 累计 -10，未越限
        assertTrue(arbiter.onDrag(80f, 0f))    // 累计 70 > 60
    }

    @Test
    fun `门限为严格大于 恰好等于不触发`() {
        val arbiter = SwipeBackArbiter(threshold)
        assertFalse(arbiter.onDrag(20f, 0f))
        assertFalse(arbiter.onDrag(20f, 0f))
        assertFalse(arbiter.onDrag(20f, 0f))   // 累计恰好 60
        assertTrue(arbiter.onDrag(0.5f, 0f))   // 60.5 越限
    }

    @Test
    fun `reset 清空半途累加`() {
        val arbiter = SwipeBackArbiter(threshold)
        assertFalse(arbiter.onDrag(50f, 0f))
        arbiter.reset()
        assertFalse(arbiter.onDrag(10f, 0f))
    }
}
