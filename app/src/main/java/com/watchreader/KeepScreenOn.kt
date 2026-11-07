package com.watchreader

import android.view.Window
import android.view.WindowManager

/**
 * FLAG_KEEP_SCREEN_ON 的跨页归属台账。
 *
 * 阅读页（5 分钟无交互超时 / 自动滚屏永久常亮）与速读页共享同一 window 的常亮标志，
 * 原先各自裸 addFlags/clearFlags 会互相误清：速读退出时无条件清旗，阅读页（尤其自动
 * 滚屏）随之失效；阅读页超时若在速读期间触发，也会清掉速读仍需要的旗。
 *
 * 这里按"持有者令牌"计数：每个页面持一个私有令牌，acquire 幂等（重复触发不叠加持份），
 * 只有最后一个持有者 release 时才允许真正清旗。
 *
 * 纯归属判定收敛在 [KeepScreenOnLedger]，不碰 Android 类，可纯 JVM 单测；
 * [WindowKeepScreenOn] 只留 Window 薄封装。全部入口约定在主线程调用
 * （Compose 副作用与 Handler 回调天然满足）。
 */
class KeepScreenOnLedger {

    private val owners = mutableSetOf<Any>()

    /** 是否仍有持有者（旗应保持点亮） */
    val isHeld: Boolean
        get() = owners.isNotEmpty()

    /** 持有常亮需求。同一 owner 重复 acquire 幂等，不叠加持份 */
    fun acquire(owner: Any) {
        owners += owner
    }

    /**
     * 释放持有。
     * @return true 表示该 owner 确实持有过、且移除后已无任何持有者，调用方应真正清旗；
     *         陌生 owner 或重复释放一律 false，绝不误发清旗信号
     */
    fun release(owner: Any): Boolean = owners.remove(owner) && owners.isEmpty()
}

/**
 * [KeepScreenOnLedger] 的 Window 薄封装：acquire 总是补 addFlags
 * （覆盖窗口重建后旗状态为新的情况），release 只在台账清空时清旗。
 */
object WindowKeepScreenOn {

    private val ledger = KeepScreenOnLedger()

    fun acquire(window: Window?, owner: Any) {
        if (window == null) return
        ledger.acquire(owner)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun release(window: Window?, owner: Any) {
        if (window == null) return
        if (ledger.release(owner)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
