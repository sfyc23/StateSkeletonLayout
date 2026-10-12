package com.sfyc.ssl.internal.skeleton

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.LinearInterpolator

/**
 * 进程级共享的骨架动画时钟。
 *
 * # 为什么需要
 *
 * 改造前每个骨架遮罩层各自持有一个 `repeatCount = INFINITE` 的 [ValueAnimator]。
 * 页面级单实例时这没有代价，但列表首屏占位是 10–20 个实例同时处于骨架态
 * （每行一个），那就是 20 个并行无限动画，每个都在 `Choreographer` 回调里
 * 争用同一帧预算，各自维护自己的重复计数与插值状态。
 *
 * 本对象把它们收敛成**一个**动画器：无论多少个遮罩层在跑，主线程每帧
 * 只有一次回调，由订阅者自己按各自的时长换算相位。
 *
 * # 与系统动画开关的关系
 *
 * 仍然使用 [ValueAnimator] 而不是 `Choreographer` 直接驱动，因为需要保留
 * [ValueAnimator.areAnimatorsEnabled] 的语义：系统关闭动画时（开发者选项
 * 里的动画倍率为 0），整个时钟不启动，所有遮罩层保持静态绘制，不显示空白。
 *
 * # 生命周期
 *
 * 订阅者归零时立即停止动画器，不空转。所有停止路径（`stop` / hide /
 * detach / 聚合不可见）都必须调用 [unsubscribe]，否则时钟会继续为
 * 已不可见的遮罩层耗电。
 *
 * # 时间基准
 *
 * [elapsedMillis] 是时钟启动以来的单调毫秒数，用累加整周期的方式推导，
 * 不依赖 `System.currentTimeMillis()`（可被系统时间校准打断，见
 * [com.sfyc.ssl.internal.time.MonotonicClock] 的同类约束）。
 */
internal object SkeletonTicker {

    /**
     * 单个动画周期的长度（毫秒）。
     *
     * 只影响 [elapsedMillis] 的推导精度，不影响任何遮罩层的视觉效果——
     * 相位由订阅者用自己的 `durationMillis` 换算。取 1000ms 是为了让
     * 每帧的相位增量（16.7 / 1000 ≈ 1.7%）有足够的浮点精度。
     */
    private const val PERIOD_MILLIS = 1000L

    private val listeners = mutableSetOf<(Long) -> Unit>()

    private var animator: ValueAnimator? = null

    /** 已经完成的整周期累计毫秒，用于把动画器的 0→1 分数还原为单调时间。 */
    private var completedMillis = 0L
    private var lastFraction = 0f

    /** 时钟是否正在推进（无订阅者或系统动画关闭时为 false）。 */
    val isTicking: Boolean
        get() = animator?.isRunning == true

    /** 当前订阅者数量；供测试断言订阅与退订是否配对。 */
    @androidx.annotation.VisibleForTesting
    internal val subscriberCount: Int
        get() = listeners.size

    /**
     * 清空全部订阅与动画器。
     *
     * 仅供测试隔离：本对象是进程级单例，前一个用例泄漏的订阅会污染后面的用例。
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        listeners.clear()
        stopTicker()
    }

    /** 时钟启动以来的单调毫秒数；未启动时为 0。 */
    fun elapsedMillis(): Long {
        val animator = animator ?: return completedMillis
        val fraction = animator.animatedFraction
        return completedMillis + (fraction * PERIOD_MILLIS).toLong()
    }

    /**
     * 订阅每帧回调。首次订阅时启动共享动画器。
     *
     * 回调参数是 [elapsedMillis]。回调在主线程执行。
     */
    fun subscribe(listener: (Long) -> Unit) {
        if (listeners.isEmpty()) startTicker()
        listeners += listener
    }

    /** 取消订阅。最后一个订阅者离开时停止共享动画器。 */
    fun unsubscribe(listener: (Long) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) stopTicker()
    }

    private fun startTicker() {
        stopTicker()
        // 系统关闭动画时整个时钟不启动：所有遮罩层由各自的 onDraw 走静态分支。
        if (!ValueAnimator.areAnimatorsEnabled()) return
        completedMillis = 0L
        lastFraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PERIOD_MILLIS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                // 分数回绕表示完成了一个整周期，累加进基准时间。
                if (fraction < lastFraction) completedMillis += PERIOD_MILLIS
                lastFraction = fraction
                val elapsed = completedMillis + (fraction * PERIOD_MILLIS).toLong()
                // 快照遍历：回调内可能 stop() 并退订，不能边遍历边改集合。
                // 必须用 this@SkeletonTicker 限定：apply 的接收者是 ValueAnimator，
                // 裸写 listeners 会解析到 Animator.getListeners()。
                val snapshot = this@SkeletonTicker.listeners.toList()
                for (slot in snapshot) slot.invoke(elapsed)
            }
            start()
        }
    }

    private fun stopTicker() {
        animator?.cancel()
        animator = null
        completedMillis = 0L
        lastFraction = 0f
    }

    /**
     * 当前单调时间，供 [SkeletonAnimator] 记录订阅起点。
     *
     * 时钟未运行时（例如系统动画被关闭）退回到 [SystemClock.uptimeMillis]，
     * 保证起点仍然单调、不受系统时间校准影响。
     */
    fun nowMillis(): Long =
        if (isTicking) elapsedMillis() else SystemClock.uptimeMillis()
}
