package com.sfyc.ssl.internal.skeleton

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.sfyc.ssl.*
import com.sfyc.ssl.internal.loading.SkeletonLoadingRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeletonGeometryOptimizationTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun `已验证模板在变窄后暂时空几何静态降级再次变宽恢复动画`() {
        val content = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 100, 100) }
        val config = SkeletonConfig(source = SkeletonSource.TEMPLATE, templateLayoutResId = R.layout.ssl_test_template_offset)
        val renderer = SkeletonLoadingRenderer(overlay, content, { config }, LayoutInflater.from(activity))
        renderer.preflight()
        renderer.onAttachedToWindow()
        renderer.show {}
        assertTrue(overlay.isEffectRunning)
        overlay.layout(0, 0, 40, 100)
        renderer.invalidate()
        assertEquals(SkeletonFallbackReason.EMPTY_TEMPLATE_AT_CURRENT_SIZE, overlay.fallbackReason)
        assertFalse(overlay.isEffectRunning)
        overlay.layout(0, 0, 100, 100)
        renderer.invalidate()
        assertEquals(SkeletonFallbackReason.NONE, overlay.fallbackReason)
        assertTrue(overlay.isEffectRunning)
        renderer.hide()
    }

    @Test fun `祖先裁剪边界限制越界叶子且内容与模板使用相同浮点坐标`() {
        val root = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val parent = FrameLayout(activity)
        root.addView(parent)
        parent.layout(20, 10, 60, 50)
        val leaf = View(activity).apply { translationX = 0.5f }
        parent.addView(leaf)
        leaf.layout(-5, 5, 60, 25)
        val overlay = View(activity).apply { layout(0, 0, 100, 100) }
        val content = mutableListOf<SkeletonGeometry>()
        val template = mutableListOf<SkeletonGeometry>()
        val builder = SkeletonMaskBuilder()
        builder.collectFromContent(root, overlay, content)
        builder.collectFromTemplate(root, template)
        assertEquals(content, template)
        assertEquals(RectF(20f, 15f, 60f, 35f), content.single().bounds)
        assertEquals(15.5f, content.single().originalBounds!!.left, 0f)
    }

    @Test fun `scroll和clipToPadding共同限制可见骨架`() {
        val root = FrameLayout(activity).apply { layout(0, 0, 100, 100); setPadding(10, 8, 10, 8) }
        val leaf = View(activity)
        root.addView(leaf)
        leaf.layout(0, 0, 80, 80)
        root.scrollTo(15, 10)
        val output = mutableListOf<SkeletonGeometry>()
        SkeletonMaskBuilder().collectFromTemplate(root, output)
        assertEquals(RectF(10f, 8f, 65f, 70f), output.single().bounds)
    }

    @Test fun `排除容器或隐藏全部后代不会把父容器补成骨架`() {
        val root = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val parent = FrameLayout(activity).apply { id = 10 }
        root.addView(parent)
        parent.layout(0, 0, 80, 80)
        val leaf = View(activity)
        parent.addView(leaf)
        leaf.layout(0, 0, 40, 20)
        val output = mutableListOf<SkeletonGeometry>()
        val builder = SkeletonMaskBuilder(nodeConfigProvider = { if (it == 10) SkeletonNodeConfig(excluded = true) else null })
        assertFalse(builder.collectFromTemplate(root, output))
        leaf.visibility = View.GONE
        assertFalse(SkeletonMaskBuilder().collectFromTemplate(root, output))
    }

    @Test fun `圆形与独立圆角通过真实遮罩像素验证`() {
        val builder = SkeletonMaskBuilder()
        val circle = builder.buildMask(listOf(SkeletonGeometry(RectF(0f, 0f, 20f, 20f), SkeletonShape.CIRCLE)),
            20, 20, 0f, false) as MaskBuildResult.Success
        val round = builder.buildMask(listOf(SkeletonGeometry(RectF(0f, 0f, 20f, 20f),
            SkeletonShape.ROUNDED_RECTANGLE, 8f)), 20, 20, 0f, false) as MaskBuildResult.Success
        try {
            assertEquals(0, circle.bitmap.getPixel(0, 0) ushr 24)
            assertTrue(circle.bitmap.getPixel(10, 10) ushr 24 > 0)
            assertEquals(0, round.bitmap.getPixel(0, 0) ushr 24)
            assertTrue(round.bitmap.getPixel(10, 10) ushr 24 > 0)
        } finally { circle.bitmap.recycle(); round.bitmap.recycle() }
    }

    @Test fun `圆形裁剪保持原圆心而不按裁剪结果重画`() {
        val geometry = SkeletonGeometry(RectF(10f, 0f, 20f, 20f), SkeletonShape.CIRCLE,
            originalBounds = RectF(0f, 0f, 20f, 20f))
        val mask = SkeletonMaskBuilder().buildMask(listOf(geometry), 20, 20, 0f, false) as MaskBuildResult.Success
        try {
            assertEquals(0, mask.bitmap.getPixel(5, 10) ushr 24)
            assertTrue(mask.bitmap.getPixel(10, 2) ushr 24 > 0)
        } finally { mask.bitmap.recycle() }
    }

    @Test fun `像素预算使用Long且超限时不调用Bitmap分配器`() {
        var allocations = 0
        val builder = SkeletonMaskBuilder { width, height -> allocations++; Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8) }
        val result = builder.buildMask(emptyList(), Int.MAX_VALUE, Int.MAX_VALUE, 0f, true, maximumPixels = 100)
        assertSame(MaskBuildResult.PixelBudgetExceeded, result)
        assertEquals(0, allocations)
    }

    @Test fun `颜色alpha在纯色和脉冲中保留`() {
        val overlay = SkeletonOverlayView(activity).apply {
            layout(0, 0, 20, 20)
            geometryProvider = { listOf(SkeletonGeometry(RectF(0f, 0f, 20f, 20f))) }
            refreshConfig(SkeletonConfig(maskColor = 0x80112233.toInt(), cornerRadius = 0f, effect = SkeletonEffect.SOLID))
        }
        overlay.ensureMask()
        val output = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            overlay.draw(Canvas(output))
            assertEquals(128, output.getPixel(10, 10) ushr 24)
            output.eraseColor(0)
            overlay.refreshConfig(overlay.config.copy(effect = SkeletonEffect.PULSE, pulseMinAlpha = 1f, pulseMaxAlpha = 1f))
            overlay.startEffect()
            overlay.draw(Canvas(output))
            assertEquals(128, output.getPixel(10, 10) ushr 24)
        } finally { overlay.stopEffect(); overlay.releaseMask(); output.recycle() }
    }

    @Test fun `内容由有效变空再恢复时静态降级与动画恢复成对发生`() {
        val parent = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val content = FrameLayout(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity)
        content.addView(leaf)
        leaf.layout(10, 10, 50, 30)
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 100, 100) }
        parent.addView(content)
        parent.addView(overlay)
        val renderer = SkeletonLoadingRenderer(overlay, content, { SkeletonConfig() }, LayoutInflater.from(activity))
        renderer.onAttachedToWindow()
        renderer.show {}
        assertTrue(overlay.isEffectRunning)
        leaf.visibility = View.GONE
        content.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(SkeletonFallbackReason.EMPTY_CONTENT, overlay.fallbackReason)
        assertFalse(overlay.isEffectRunning)
        leaf.visibility = View.VISIBLE
        content.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(SkeletonFallbackReason.NONE, overlay.fallbackReason)
        assertTrue(overlay.isEffectRunning)
        assertEquals(3, overlay.maskBuildCount)
        renderer.hide()
    }
}
