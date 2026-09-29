package com.watchreader

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer

/**
 * 临时性能探针（定位字体切换与排版净化卡顿归因后整体删除）
 */
object ReadPerf {

    private const val TAG = "WatchPerf"
    private const val SLOW_FRAME_MS = 100L

    fun <T> trace(point: String, extra: () -> String = { "" }, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        Log.i(TAG, "$point=${SystemClock.elapsedRealtime() - start}ms ${extra()}")
        return result
    }

    fun mark(point: String, extra: String = "") {
        Log.i(TAG, "$point $extra")
    }

    private var watching = false

    private val frameCallback = object : Choreographer.FrameCallback {
        private var lastFrameNs = 0L

        override fun doFrame(frameTimeNanos: Long) {
            if (!watching) return
            val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
            if (lastFrameNs != 0L && deltaMs > SLOW_FRAME_MS) {
                Log.w(TAG, "SLOW_FRAME=${deltaMs}ms")
            }
            lastFrameNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startFrameWatch() {
        if (watching || Looper.myLooper() != Looper.getMainLooper()) return
        watching = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopFrameWatch() {
        watching = false
    }
}
