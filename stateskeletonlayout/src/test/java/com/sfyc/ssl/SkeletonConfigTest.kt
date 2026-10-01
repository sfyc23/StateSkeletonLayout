package com.sfyc.ssl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SkeletonConfig] 校验与归一化测试（对应实现方案第 24.1 节）。
 *
 * 业务意图：XML 与运行时共用同一构造校验，非法配置快速失败并给出
 * 可定位信息；角度自动归一化，调用方无需处理回绕。
 */
class SkeletonConfigTest {

    @Test fun `圆角拒绝NaN和Infinity且像素预算必须为正`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { radius ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SkeletonConfig(cornerRadius = radius) }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SkeletonNodeConfig(cornerRadius = radius) }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SkeletonConfig(maximumMaskPixels = 0) }
    }

    @Test
    fun `默认配置可正常构造`() {
        val config = SkeletonConfig()

        assertEquals(SkeletonSource.CONTENT, config.source)
        assertEquals(SkeletonEffect.SHIMMER, config.effect)
        assertEquals(1200L, config.animationDurationMillis)
        assertEquals(0, config.normalizedAngleDegrees)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `动画时长为0抛异常`() {
        SkeletonConfig(animationDurationMillis = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `动画时长为负数抛异常`() {
        SkeletonConfig(animationDurationMillis = -100L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `圆角为负数抛异常`() {
        SkeletonConfig(cornerRadius = -1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `脉冲最小透明度越界抛异常`() {
        SkeletonConfig(pulseMinAlpha = -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `脉冲最大透明度越界抛异常`() {
        SkeletonConfig(pulseMaxAlpha = 1.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `脉冲最小值大于最大值抛异常`() {
        SkeletonConfig(pulseMinAlpha = 0.9f, pulseMaxAlpha = 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `模板来源缺布局资源抛异常`() {
        SkeletonConfig(source = SkeletonSource.TEMPLATE, templateLayoutResId = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `模板来源布局资源为0抛异常`() {
        SkeletonConfig(source = SkeletonSource.TEMPLATE, templateLayoutResId = 0)
    }

    @Test
    fun `内容来源无需模板布局`() {
        val config = SkeletonConfig(source = SkeletonSource.CONTENT, templateLayoutResId = null)
        assertEquals(SkeletonSource.CONTENT, config.source)
    }

    @Test
    fun `角度归一化覆盖负数与超圈`() {
        assertEquals(270, SkeletonConfig(angleDegrees = -90).normalizedAngleDegrees)
        assertEquals(0, SkeletonConfig(angleDegrees = 360).normalizedAngleDegrees)
        assertEquals(90, SkeletonConfig(angleDegrees = 450).normalizedAngleDegrees)
        assertEquals(0, SkeletonConfig(angleDegrees = 720).normalizedAngleDegrees)
        assertEquals(45, SkeletonConfig(angleDegrees = 45).normalizedAngleDegrees)
    }

    @Test
    fun `相同参数的配置相等且可整体替换`() {
        val first = SkeletonConfig(effect = SkeletonEffect.PULSE)
        val second = first.copy(effect = SkeletonEffect.SOLID)

        assertEquals(SkeletonEffect.PULSE, first.effect)
        assertEquals(SkeletonEffect.SOLID, second.effect)
        assertEquals(first.copy(), first)
    }
}
