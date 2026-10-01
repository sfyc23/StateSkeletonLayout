package com.sfyc.ssl.internal.skeleton

import android.animation.ValueAnimator
import com.sfyc.ssl.SkeletonEffect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeletonAnimatorTest {

    @Test
    fun `重复启动复用运行中的Animator且stop幂等`() {
        val animator = SkeletonAnimator {}

        animator.start(SkeletonEffect.SHIMMER)
        assertTrue(animator.isRunning)
        animator.start(SkeletonEffect.PULSE)
        assertTrue(animator.isRunning)

        animator.stop()
        animator.stop()
        assertFalse(animator.isRunning)
    }

    @Test
    fun `系统动画关闭时保持静态且不运行Animator`() {
        val previousScale = ValueAnimator.getDurationScale()
        try {
            setDurationScale(0f)
            val animator = SkeletonAnimator {}

            animator.start(SkeletonEffect.SHIMMER)

            assertFalse(animator.isRunning)
        } finally {
            setDurationScale(previousScale)
        }
    }

    private fun setDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Void>(
            ValueAnimator::class.java,
            "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType!!, scale),
        )
    }
}
