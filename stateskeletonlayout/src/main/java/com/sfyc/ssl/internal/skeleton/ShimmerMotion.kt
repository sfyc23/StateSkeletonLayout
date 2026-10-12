package com.sfyc.ssl.internal.skeleton

import androidx.annotation.VisibleForTesting
import com.sfyc.ssl.SkeletonDirection
import kotlin.math.cos
import kotlin.math.sin

/** 纯数学流光轨迹，保证位移轴与 LinearGradient 的渐变轴始终一致。 */
internal object ShimmerMotion {

    data class Axis(
        val x: Float,
        val y: Float,
        val gradientRotationDegrees: Float,
    )

    data class Frame(
        val centerX: Float,
        val centerY: Float,
        val gradientRotationDegrees: Float,
    )

    fun resolveAxis(
        direction: SkeletonDirection,
        angleDegrees: Int,
        isRtl: Boolean,
    ): Axis {
        val baseDegrees = when (direction) {
            SkeletonDirection.START_TO_END -> if (isRtl) 180f else 0f
            SkeletonDirection.END_TO_START -> if (isRtl) 0f else 180f
        }
        val rotation = baseDegrees + angleDegrees
        val radians = Math.toRadians(rotation.toDouble())
        return Axis(
            x = cos(radians).toFloat(),
            y = sin(radians).toFloat(),
            gradientRotationDegrees = rotation,
        )
    }

    /**
     * 一次性算出一帧的完整结果（中心点 + 渐变旋转角）。
     *
     * **仅供测试使用，生产绘制路径不要调用本方法。**
     *
     * [SkeletonOverlayView.onDraw] 走的是 [distance] + 缓存的 `scanAxisX/Y`：
     * 每帧只做一次加法与乘法。本方法内部会调用 [resolveAxis]，即每帧
     * 两条三角函数（`cos` / `sin`），且返回一个新的 [Frame] 对象——
     * 在 60fps 下是明确的浪费。它的价值在于用可读的形式断言
     * 「位移轴与渐变轴始终一致」这条不变量，因此保留而不删除。
     *
     * 渲染器改为每帧调用本方法会在动画帧内产生分配与三角函数，属回归。
     */
    @VisibleForTesting
    fun frame(
        progress: Float,
        centerX: Float,
        centerY: Float,
        span: Float,
        bandHalfWidth: Float,
        direction: SkeletonDirection,
        angleDegrees: Int,
        isRtl: Boolean,
    ): Frame {
        val axis = resolveAxis(direction, angleDegrees, isRtl)
        val distance = distance(progress, span, bandHalfWidth)
        return Frame(
            centerX = centerX + axis.x * distance,
            centerY = centerY + axis.y * distance,
            gradientRotationDegrees = axis.gradientRotationDegrees,
        )
    }

    /** 每帧无分配地计算相对中心位移；[span] 是目标矩形在扫描轴上的完整投影。 */
    fun distance(progress: Float, span: Float, bandHalfWidth: Float): Float {
        val travel = span + bandHalfWidth * 2f
        // 以矩形中心为原点，让高亮带从负端完全移出扫到正端完全移出。
        return -(span / 2f + bandHalfWidth) + progress.coerceIn(0f, 1f) * travel
    }
}
