package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠路由：分派顺序、消费即终止、触边不消费继续下沉、门限内抖动不驱动页面消费
 */
class CrownRouterTest {

    private val log = mutableListOf<String>()

    private fun route(name: String, consume: Boolean) = CrownRoute { _, _ ->
        log += name
        consume
    }

    @Test
    fun testRoutesTriedInDeclaredOrderUntilAllDecline() {
        val router = CrownRouter(listOf(route("a", false), route("b", false), route("c", false)))

        assertFalse(router.onRotary(CrownTurn(6f)))
        assertEquals(listOf("a", "b", "c"), log)
    }

    @Test
    fun testFirstConsumingRouteStopsDispatch() {
        val router = CrownRouter(listOf(route("a", false), route("b", true), route("c", false)))

        assertTrue(router.onRotary(CrownTurn(6f)))
        // 一旦消费，后续策略不得重放同一次事件
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun testEmptyRouteListNeverConsumes() {
        assertFalse(CrownRouter(emptyList()).onRotary(CrownTurn(6f)))
    }

    @Test
    fun testOrderedRoutesFollowDeclaredPriority() {
        val router = CrownRouter(
            CrownRoutes.ordered(
                onRsvpRotary = { log += "rsvp"; false },
                pageTarget = { log += "slot"; Target(log, consume = false) }
            )
        )

        assertFalse(router.onRotary(CrownTurn(6f), Native(log)))
        assertEquals(listOf("rsvp", "slot", "target", "default", "focused", "scrollable"), log)
    }

    @Test
    fun testRsvpSpeedConsumesBeforePageTarget() {
        val router = CrownRouter(
            CrownRoutes.ordered(
                onRsvpRotary = { log += "rsvp"; true },
                pageTarget = { Target(log, consume = true) }
            )
        )

        assertTrue(router.onRotary(CrownTurn(6f), Native(log)))
        assertEquals(listOf("rsvp"), log)
    }

    @Test
    fun testPageTargetConsumesBeforeNativeTree() {
        val router = CrownRouter(
            CrownRoutes.ordered(
                onRsvpRotary = { log += "rsvp"; false },
                pageTarget = { Target(log, consume = true) }
            )
        )

        assertTrue(router.onRotary(CrownTurn(6f), Native(log)))
        // 页面目标消费后不得再进原生树：否则同一格转动会被滚动两次
        assertEquals(listOf("rsvp", "target"), log)
    }

    @Test
    fun testBoundaryTargetFallsThroughToNativeTree() {
        // 触边不消费：页面目标返回 false（无可滚内容），事件必须继续下沉而不是被吞掉
        val router = CrownRouter(
            CrownRoutes.ordered(
                onRsvpRotary = { false },
                pageTarget = { Target(log, consume = false) }
            )
        )

        assertTrue(router.onRotary(CrownTurn(-6f), Native(log, consumedAt = "focused")))
        assertEquals(listOf("target", "default", "focused"), log)
    }

    @Test
    fun testInsignificantTurnSkipsPageLevelRoutesButKeepsNativeTree() {
        val router = CrownRouter(
            CrownRoutes.ordered(
                onRsvpRotary = { log += "rsvp"; true },
                pageTarget = { Target(log, consume = true) }
            )
        )

        // 门限内抖动不驱动调速与页面滚动，但仍要给原生 View 树机会（与收口前一致）
        // 原生默认链消费即终止：门限内抖动只走原生树，调速与页面滚动都不应被驱动
        assertTrue(router.onRotary(CrownTurn(0.0005f), Native(log, consumedAt = "default")))
        assertEquals(listOf("default"), log)
    }

    @Test
    fun testLastResortScrollableViewRouteConsumes() {
        val router = CrownRouter(
            CrownRoutes.ordered(onRsvpRotary = { false }, pageTarget = { null })
        )

        assertTrue(router.onRotary(CrownTurn(6f), Native(log, consumedAt = "scrollable")))
        assertEquals(listOf("default", "focused", "scrollable"), log)
    }

    @Test
    fun testPageTargetReceivesNormalizedDelta() {
        val seen = mutableListOf<Float>()
        val target = object : CrownScrollTarget {
            override fun onCrownDelta(delta: Float): Boolean {
                seen += delta
                return true
            }
        }
        val router = CrownRouter(CrownRoutes.ordered(onRsvpRotary = { false }, pageTarget = { target }))

        router.onRotary(CrownTurn(7f))
        router.onRotary(CrownTurn(-7f))
        assertEquals(listOf(7f, -7f), seen)
    }

    private class Native(
        private val log: MutableList<String>,
        private val consumedAt: String? = null
    ) : CrownNativeDispatch {

        override fun dispatchDefault(): Boolean {
            log += "default"
            return consumedAt == "default"
        }

        override fun dispatchToFocused(): Boolean {
            log += "focused"
            return consumedAt == "focused"
        }

        override fun dispatchToActiveScrollableView(): Boolean {
            log += "scrollable"
            return consumedAt == "scrollable"
        }
    }

    private class Target(
        private val log: MutableList<String>,
        private val consume: Boolean
    ) : CrownScrollTarget {

        override fun onCrownDelta(delta: Float): Boolean {
            log += "target"
            return consume
        }
    }
}
