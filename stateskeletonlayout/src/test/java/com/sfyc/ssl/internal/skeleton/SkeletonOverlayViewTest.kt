package com.sfyc.ssl.internal.skeleton

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.sfyc.ssl.SkeletonConfig
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.R
import com.sfyc.ssl.internal.loading.SkeletonLoadingRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeletonOverlayViewTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `仅变更效果颜色方向与时长不会重建遮罩`() {
        val parent = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val content = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity).apply { layout(10, 10, 80, 40) }
        content.addView(leaf)
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 100, 100) }
        parent.addView(content)
        parent.addView(overlay)
        var config = SkeletonConfig(effect = SkeletonEffect.SHIMMER)
        val renderer = SkeletonLoadingRenderer(
            overlay = overlay,
            contentHost = content,
            configProvider = { config },
            inflater = LayoutInflater.from(activity),
        )
        renderer.onAttachedToWindow()
        renderer.show {}
        assertEquals(1, overlay.maskBuildCount)

        config = config.copy(
            effect = SkeletonEffect.PULSE,
            maskColor = 0xFF223344.toInt(),
            highlightColor = 0xFFEEEEEE.toInt(),
            angleDegrees = 45,
            animationDurationMillis = 900L,
        )
        renderer.onConfigChanged()

        assertEquals(1, overlay.maskBuildCount)

        config = config.copy(cornerRadius = config.cornerRadius + 1f)
        renderer.onConfigChanged()
        assertEquals(2, overlay.maskBuildCount)
    }

    @Test
    fun `Loading期间内容根容器隐藏后子View布局变化仍重建遮罩`() {
        val parent = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val content = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity)
        content.addView(leaf)
        leaf.layout(10, 10, 50, 30)
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 100, 100) }
        parent.addView(content)
        parent.addView(overlay)
        val renderer = SkeletonLoadingRenderer(
            overlay = overlay,
            contentHost = content,
            configProvider = { SkeletonConfig() },
            inflater = LayoutInflater.from(activity),
        )
        renderer.onAttachedToWindow()
        renderer.show {}
        assertEquals(1, overlay.maskBuildCount)

        content.visibility = View.INVISIBLE
        leaf.layout(30, 20, 90, 50)
        content.viewTreeObserver.dispatchOnGlobalLayout()

        assertEquals(2, overlay.maskBuildCount)
    }

    @Test
    fun `从内容取形切到模板后不再响应内容布局变化`() {
        val parent = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val content = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity)
        content.addView(leaf)
        leaf.layout(10, 10, 50, 30)
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 100, 100) }
        parent.addView(content)
        parent.addView(overlay)
        var config = SkeletonConfig()
        val renderer = SkeletonLoadingRenderer(
            overlay = overlay,
            contentHost = content,
            configProvider = { config },
            inflater = LayoutInflater.from(activity),
        )
        renderer.onAttachedToWindow()
        renderer.show {}

        config = SkeletonConfig(
            source = SkeletonSource.TEMPLATE,
            templateLayoutResId = R.layout.ssl_test_state_simple,
        )
        renderer.onConfigChanged()
        val buildsAfterSwitch = overlay.maskBuildCount
        leaf.layout(30, 20, 90, 50)
        content.viewTreeObserver.dispatchOnGlobalLayout()

        assertEquals(buildsAfterSwitch, overlay.maskBuildCount)
    }

    @Test
    fun `内容空几何降级为静态整块遮罩`() {
        val overlay = SkeletonOverlayView(activity).apply {
            layout(0, 0, 40, 20)
            geometryProvider = { emptyList() }
            fallbackToFullBounds = true
            refreshConfig(SkeletonConfig(effect = SkeletonEffect.SHIMMER))
        }

        val result = overlay.ensureMask()
        overlay.startEffect()

        assertTrue(result is MaskPreparationResult.Ready && result.fallbackUsed)
        assertFalse(overlay.isEffectRunning)
    }

    @Test
    fun `首次分配失败显示静态纯色而不是抛模板异常或空白`() {
        val overlay = SkeletonOverlayView(
            activity,
            SkeletonMaskBuilder { _, _ -> throw OutOfMemoryError("test") },
        ).apply {
            layout(0, 0, 40, 20)
            geometryProvider = { listOf(SkeletonGeometry(RectF(0f, 0f, 20f, 10f))) }
        }

        val result = overlay.ensureMask()
        overlay.startEffect()
        val output = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(output))

        assertTrue(result is MaskPreparationResult.AllocationFailed && !result.retainedPreviousMask)
        assertFalse(overlay.isEffectRunning)
        assertTrue("纯色降级必须产生非透明像素", output.getPixel(30, 15) ushr 24 > 0)
        output.recycle()
    }

    @Test
    fun `重建分配失败时保留旧遮罩`() {
        var failAllocation = false
        val builder = SkeletonMaskBuilder { width, height ->
            if (failAllocation) throw OutOfMemoryError("test")
            Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        }
        val overlay = SkeletonOverlayView(activity, builder).apply {
            layout(0, 0, 40, 20)
            geometryProvider = { listOf(SkeletonGeometry(RectF(0f, 0f, 20f, 10f))) }
        }
        assertTrue(overlay.ensureMask() is MaskPreparationResult.Ready)

        failAllocation = true
        overlay.refreshConfig(overlay.config.copy(cornerRadius = 2f))
        val result = overlay.ensureMask()
        val output = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(output))

        assertTrue(result is MaskPreparationResult.AllocationFailed && result.retainedPreviousMask)
        assertTrue("旧遮罩区域应继续可见", output.getPixel(5, 5) ushr 24 > 0)
        assertEquals("旧遮罩外仍应透明", 0, output.getPixel(30, 15) ushr 24)
        output.recycle()
    }
}
