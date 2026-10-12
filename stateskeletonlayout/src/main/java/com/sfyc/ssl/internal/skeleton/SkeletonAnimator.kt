package com.sfyc.ssl.internal.skeleton

import android.animation.ValueAnimator
import com.sfyc.ssl.SkeletonEffect

/**
 * 骨架动画驱动器：共享时钟的订阅者，动画帧只更新进度并请求重绘。
 *
 * - SHIMMER / PULSE 共用 [SkeletonTicker] 提供的单调时间，按各自的
 *   [durationMillis] 换算出 `[0, 1)` 的循环进度。效果映射由遮罩层在
 *   `onDraw` 内完成（位移矩阵 / 透明度函数）。
 * - SOLID 不订阅时钟，只绘制静态遮罩。
 * - 系统动画被关闭（[ValueAnimator.areAnimatorsEnabled] 为 false）时
 *   自动降级为静态绘制，不抛异常、不显示空白。
 * - start/stop 与 View 的 attach/detach、聚合可见性成对管理；
 *   重复 start 不会重复订阅。
 *
 * # 与改造前的关系
 *
 * 改造前每个实例持有一个 `repeatCount = INFINITE` 的 `ValueAnimator`。
 * 列表场景下 N 个骨架行就是 N 个并行无限动画。现在 N 个实例订阅同一个
 * 时钟，主线程每帧只有一次回调。
 *
 * @param onProgress 动画进度回调（遮罩层实现：记录进度 + `invalidate()`）。
 */
internal class SkeletonAnimator(
    private val onProgress: (fraction: Float) -> Unit,
) {

    /** 当前动画进度 `[0, 1)`；未运行时恒为 0。 */
    var progress: Float = 0f
        private set

    /** 动画是否正在运行（SOLID / 降级静态模式下恒为 false）。 */
    var isRunning: Boolean = false
        private set

    var durationMillis: Long = DEFAULT_DURATION_MILLIS
        set(value) {
            // 时长只影响相位换算，不改动订阅状态；下一帧自然按新时长取值。
            field = value.coerceAtLeast(1L)
        }

    /** 订阅起点（时钟的单调毫秒）。每次 start 重新取值，避免跨会话相位跳变。 */
    private var startedAtMillis: Long = 0L

    private val tick: (Long) -> Unit = { now ->
        val elapsed = now - startedAtMillis
        // 取模保留 [0, 1) 的半开区间：progress == 1f 会让 SHIMMER 在末态
        // 停留一帧再跳回 0，产生可见的卡顿。
        // 双取模是为了容忍负的 elapsed：Kotlin 的 % 保留符号，
        // 单靠 (elapsed % duration) 在 elapsed < 0 时会得到负进度。
        val period = durationMillis
        val phase = ((elapsed % period) + period) % period
        val fraction = phase.toFloat() / period.toFloat()
        progress = fraction
        onProgress(fraction)
    }

    /**
     * 按效果启动。SOLID 或系统动画关闭时停止并回到静态进度。
     */
    fun start(effect: SkeletonEffect) {
        if (effect == SkeletonEffect.SOLID || !ValueAnimator.areAnimatorsEnabled()) {
            stop()
            return
        }
        if (isRunning) return
        isRunning = true
        // 必须先订阅再取起点时间。
        // [SkeletonTicker.nowMillis] 在时钟未推进时退回 SystemClock.uptimeMillis()，
        // 而订阅会启动时钟、其 elapsed 从 0 起算——先取时间会拿到一个与
        // 时钟时间轴不同基准的大数值，使每帧 elapsed 恒为负、进度为负。
        SkeletonTicker.subscribe(tick)
        startedAtMillis = SkeletonTicker.nowMillis()
    }

    /** 停止动画并退订共享时钟；重复调用无副作用。 */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        SkeletonTicker.unsubscribe(tick)
        if (progress != 0f) {
            progress = 0f
            onProgress(0f)
        }
    }

    companion object {
        private const val DEFAULT_DURATION_MILLIS = 1200L
    }
}
