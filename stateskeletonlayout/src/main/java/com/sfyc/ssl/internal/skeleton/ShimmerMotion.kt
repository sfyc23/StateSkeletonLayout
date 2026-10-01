package com.sfyc.ssl.internal.skeleton

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
