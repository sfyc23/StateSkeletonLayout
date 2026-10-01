package com.sfyc.ssl.internal.skeleton

import com.sfyc.ssl.SkeletonDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimmerMotionTest {

    @Test
    fun `LTR StartToEnd的高亮中心沿渐变轴向右移动`() {
        val start = ShimmerMotion.frame(
            progress = 0f,
            centerX = 50f,
            centerY = 40f,
            span = 100f,
            bandHalfWidth = 20f,
            direction = SkeletonDirection.START_TO_END,
            angleDegrees = 0,
            isRtl = false,
        )
        val end = ShimmerMotion.frame(
            progress = 1f,
            centerX = 50f,
            centerY = 40f,
            span = 100f,
            bandHalfWidth = 20f,
            direction = SkeletonDirection.START_TO_END,
            angleDegrees = 0,
            isRtl = false,
        )

        assertTrue(end.centerX > start.centerX)
        assertEquals(start.centerY, end.centerY, 0.0001f)
        assertEquals(0f, start.gradientRotationDegrees, 0.0001f)
    }

    @Test
    fun `RTL与反向枚举会反转水平移动`() {
        val rtlForward = deltaX(SkeletonDirection.START_TO_END, isRtl = true)
        val ltrReverse = deltaX(SkeletonDirection.END_TO_START, isRtl = false)
        val rtlReverse = deltaX(SkeletonDirection.END_TO_START, isRtl = true)

        assertTrue(rtlForward < 0f)
        assertTrue(ltrReverse < 0f)
        assertTrue(rtlReverse > 0f)
    }

    @Test
    fun `角度同时作用于位移轴与渐变轴`() {
        val quarter = ShimmerMotion.frame(
            progress = 0.25f,
            centerX = 0f,
            centerY = 0f,
            span = 100f,
            bandHalfWidth = 10f,
            direction = SkeletonDirection.START_TO_END,
            angleDegrees = 45,
            isRtl = false,
        )
        val half = ShimmerMotion.frame(
            progress = 0.5f,
            centerX = 0f,
            centerY = 0f,
            span = 100f,
            bandHalfWidth = 10f,
            direction = SkeletonDirection.START_TO_END,
            angleDegrees = 45,
            isRtl = false,
        )

        assertTrue(half.centerX > quarter.centerX)
        assertTrue(half.centerY > quarter.centerY)
        assertEquals(45f, half.gradientRotationDegrees, 0.0001f)
    }

    @Test
    fun `九十度时高亮只沿垂直方向移动`() {
        val start = frameAt(0f, angleDegrees = 90)
        val end = frameAt(1f, angleDegrees = 90)

        assertEquals(start.centerX, end.centerX, 0.0001f)
        assertTrue(end.centerY > start.centerY)
        assertEquals(90f, end.gradientRotationDegrees, 0.0001f)
    }

    @Test
    fun `动画首尾高亮带完整位于水平边界外`() {
        val start = frameAt(0f)
        val end = frameAt(1f)

        assertTrue(start.centerX + 20f <= 0f)
        assertTrue(end.centerX - 20f >= 100f)
    }

    private fun deltaX(direction: SkeletonDirection, isRtl: Boolean): Float {
        val start = frameAt(0f, direction, isRtl = isRtl)
        val end = frameAt(1f, direction, isRtl = isRtl)
        return end.centerX - start.centerX
    }

    private fun frameAt(
        progress: Float,
        direction: SkeletonDirection = SkeletonDirection.START_TO_END,
        angleDegrees: Int = 0,
        isRtl: Boolean = false,
    ): ShimmerMotion.Frame = ShimmerMotion.frame(
        progress = progress,
        centerX = 50f,
        centerY = 50f,
        span = 100f,
        bandHalfWidth = 20f,
        direction = direction,
        angleDegrees = angleDegrees,
        isRtl = isRtl,
    )
}
