package com.sfyc.ssl.internal.skeleton

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.sfyc.ssl.SkeletonEffect

/**
 * 骨架动画驱动器：单例 ValueAnimator 持有者，动画帧只更新进度并请求重绘。
 *
 * - SHIMMER / PULSE 共用同一个 0→1 无限循环进度，效果映射由遮罩层在
 *   `onDraw` 内完成（位移矩阵 / 透明度函数）。
 * - SOLID 不创建 Animator，只绘制静态遮罩。
 * - 系统动画被关闭（[ValueAnimator.areAnimatorsEnabled] 为 false）时自动
 *   降级为静态绘制，不抛异常、不显示空白。
 * - start/stop 与 View 的 attach/detach、聚合可见性成对管理；
 *   重复 start 不会创建第二个 Animator。
 *
 * @param onProgress 动画进度回调（遮罩层实现：记录进度 + `invalidate()`）。
 */
internal class SkeletonAnimator(
    private val onProgress: (fraction: Float) -> Unit,
) {

    /** 当前动画进度 `[0, 1]`；静态模式下恒为 0。 */
    var progress: Float = 0f
        private set

    /** 动画是否正在运行（SOLID / 降级静态模式下恒为 false）。 */
    val isRunning: Boolean
        get() = animator?.isRunning == true

    var durationMillis: Long = DEFAULT_DURATION_MILLIS
        set(value) {
            field = value.coerceAtLeast(1L)
            animator?.duration = field
        }

    private var animator: ValueAnimator? = null

    /**
     * 按效果启动。SOLID 或系统动画关闭时停止动画器并回到静态进度。
     */
    fun start(effect: SkeletonEffect) {
        if (effect == SkeletonEffect.SOLID || !ValueAnimator.areAnimatorsEnabled()) {
            stop()
            return
        }
        val running = animator
        if (running?.isRunning == true) {
            running.duration = durationMillis
            return
        }
        stop()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            // 脉冲的平滑呼吸由遮罩层用余弦函数映射，插值器保持线性即可。
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                onProgress(progress)
            }
            start()
        }
    }

    /** 停止动画并释放 Animator；重复调用无副作用。 */
    fun stop() {
        animator?.cancel()
        animator = null
        if (progress != 0f) {
            progress = 0f
            onProgress(progress)
        }
    }

    companion object {
        private const val DEFAULT_DURATION_MILLIS = 1200L
    }
}
