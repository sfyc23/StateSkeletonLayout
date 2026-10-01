package com.sfyc.ssl.internal.time

/**
 * 单调时钟：只前进、不受系统时间校准影响。
 *
 * 状态协调器禁止使用 `System.currentTimeMillis()`，避免用户或网络校准
 * 系统时间导致加载最短展示时长计算错误。
 */
internal fun interface MonotonicClock {

    /** 返回单调时间，单位毫秒（语义等同于 `SystemClock.uptimeMillis()`）。 */
    fun nowMillis(): Long
}
