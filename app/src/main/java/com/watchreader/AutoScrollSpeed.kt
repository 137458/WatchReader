package com.watchreader

/**
 * 自动滚动与 RSVP 调速的唯一取值域。
 *
 * 阅读页表冠调速、RSVP 调速面板与持久化层共用同一区间，
 * 避免此前三处各自钳制（15..200 / 15..250 / 10..200）导致的取值漂移。
 */
object AutoScrollSpeed {

    const val MIN = 15f
    const val MAX = 250f

    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    /** 持久化层读回时使用：越界值收敛到合法区间而不是拒绝整个配置。 */
    fun fromStored(raw: Float): Float = clamp(raw)
}
