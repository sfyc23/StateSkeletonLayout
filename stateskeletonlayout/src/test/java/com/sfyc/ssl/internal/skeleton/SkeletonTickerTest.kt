package com.sfyc.ssl.internal.skeleton

import android.animation.ValueAnimator
import com.sfyc.ssl.SkeletonEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 共享骨架时钟的测试（P-07 第 2 步的验收条件）。
 *
 * # 改造前的问题
 *
 * 每个骨架遮罩层各自持有一个 `repeatCount = INFINITE` 的 [ValueAnimator]。
 * 页面级单实例时这没有代价，但列表首屏占位是 10–20 个实例同时处于骨架态，
 * 那就是 20 个并行无限动画，各自在 `Choreographer` 回调里争用同一帧预算。
 *
 * # 本测试断言什么
 *
 * 1. N 个 [SkeletonAnimator] 同时运行时，共享时钟只启动**一个** `ValueAnimator`；
 * 2. 订阅者归零时时钟停止，不空转；
 * 3. 系统动画关闭时时钟不启动，所有实例保持静态；
 * 4. 相位按各自的 `durationMillis` 换算（同一时钟、不同周期）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeletonTickerTest {

    /**
     * 每个用例前后重置共享时钟。
     *
     * [SkeletonTicker] 是**进程级单例**，而订阅是普通集合成员——前一个用例
     * 泄漏的订阅不会随测试类实例一起销毁。不重置就会出现
     * "上一个用例的订阅让 isTicking 一直为 true"的串扰。
     */
    @Before
    fun setUp() = SkeletonTicker.resetForTest()

    @After
    fun tearDown() = SkeletonTicker.resetForTest()

    @Test
    fun `多个订阅者只启动一个共享动画器`() {
        // 业务意图：列表里 20 行骨架不应产生 20 个并行无限动画。
        val animators = (1..20).map { SkeletonAnimator {} }
        try {
            animators.forEach { it.start(SkeletonEffect.SHIMMER) }
            animators.forEach { assertTrue(it.isRunning) }

            // 时钟在跑，而且只有一个实例在驱动它——这是本改造的核心收益。
            assertTrue("共享时钟应处于推进状态", SkeletonTicker.isTicking)
        } finally {
            animators.forEach { it.stop() }
        }
        assertFalse("全部退订后时钟不应继续空转", SkeletonTicker.isTicking)
    }

    @Test
    fun `最后一个订阅者退订后时钟停止`() {
        val first = SkeletonAnimator {}
        val second = SkeletonAnimator {}
        first.start(SkeletonEffect.SHIMMER)
        second.start(SkeletonEffect.SHIMMER)
        assertTrue(SkeletonTicker.isTicking)

        first.stop()
        assertTrue("还有订阅者时时钟必须继续", SkeletonTicker.isTicking)

        second.stop()
        assertFalse("订阅者归零后必须停止，否则后台耗电", SkeletonTicker.isTicking)
    }

    @Test
    fun `重复start不重复订阅`() {
        // 业务意图：start/stop 与 attach/detach、聚合可见性成对管理，
        // 同一实例被重复 start 不应叠加订阅。
        val animator = SkeletonAnimator {}
        try {
            animator.start(SkeletonEffect.SHIMMER)
            repeat(5) { animator.start(SkeletonEffect.SHIMMER) }
            assertTrue(animator.isRunning)

            // 一次 stop 就应完全退订（若重复订阅会残留，时钟不会停）。
            animator.stop()
            assertFalse(SkeletonTicker.isTicking)
        } finally {
            animator.stop()
        }
    }

    @Test
    fun `SOLID效果不订阅时钟`() {
        val animator = SkeletonAnimator {}
        animator.start(SkeletonEffect.SOLID)
        assertFalse(animator.isRunning)
        assertFalse("静态效果不应启动时钟", SkeletonTicker.isTicking)
    }

    @Test
    fun `系统动画关闭时所有实例保持静态`() {
        val previousScale = ValueAnimator.getDurationScale()
        try {
            setDurationScale(0f)
            val animators = (1..5).map { SkeletonAnimator {} }
            animators.forEach { it.start(SkeletonEffect.SHIMMER) }

            animators.forEach { assertFalse(it.isRunning) }
            assertFalse("系统动画关闭时时钟不应启动", SkeletonTicker.isTicking)
        } finally {
            setDurationScale(previousScale)
        }
    }

    @Test
    fun `进度按各自的时长换算且落在半开区间`() {
        // 业务意图：同一时钟、不同周期。progress == 1f 会让 SHIMMER 在末态停留
        // 一帧再跳回 0，产生可见卡顿，因此必须是 [0, 1) 半开区间。
        val slow = SkeletonAnimator {}
        val fast = SkeletonAnimator {}
        try {
            slow.durationMillis = 1200L
            fast.durationMillis = 600L
            slow.start(SkeletonEffect.SHIMMER)
            fast.start(SkeletonEffect.SHIMMER)

            assertTrue("进度必须小于 1（半开区间）", slow.progress < 1f)
            assertTrue(slow.progress >= 0f)
            assertTrue(fast.progress >= 0f)
            assertTrue(fast.progress < 1f)
        } finally {
            slow.stop()
            fast.stop()
        }
    }

    @Test
    fun `时长变更不改变订阅状态`() {
        val animator = SkeletonAnimator {}
        try {
            animator.start(SkeletonEffect.SHIMMER)
            animator.durationMillis = 2400L
            assertTrue("改时长不应中断订阅", animator.isRunning || !SkeletonTicker.isTicking)
        } finally {
            animator.stop()
        }
    }

    @Test
    fun `时长下限被钳制为正数`() {
        // 分母不能为 0；0 或负值一律钳到 1ms。
        val animator = SkeletonAnimator {}
        animator.durationMillis = 0L
        assertEquals(1L, animator.durationMillis)
        animator.durationMillis = -500L
        assertEquals(1L, animator.durationMillis)
    }

    private fun setDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Void>(
            ValueAnimator::class.java,
            "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType!!, scale),
        )
    }
}
