package com.sfyc.ssl.internal.skeleton

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeletonMaskBuilderTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `Bitmap分配失败返回独立结果而不是伪装成空模板`() {
        val builder = SkeletonMaskBuilder { _, _ -> throw OutOfMemoryError("test") }

        val result = builder.buildMask(
            geometries = listOf(SkeletonGeometry(RectF(0f, 0f, 10f, 10f))),
            width = 100,
            height = 100,
            cornerRadius = 4f,
            fallbackToFullBounds = false,
        )

        assertSame(MaskBuildResult.AllocationFailed, result)
    }

    @Test
    fun `空模板与非法尺寸返回不同结果`() {
        val builder = SkeletonMaskBuilder()

        val empty = builder.buildMask(emptyList(), 100, 100, 0f, fallbackToFullBounds = false)
        val waiting = builder.buildMask(emptyList(), 0, 100, 0f, fallbackToFullBounds = false)

        assertSame(MaskBuildResult.EmptyGeometry, empty)
        assertSame(MaskBuildResult.InvalidSize, waiting)
    }

    @Test
    fun `内容根容器不可见时仍按局部坐标采集可见后代`() {
        val host = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            layout(0, 0, 100, 100)
        }
        val overlay = View(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity)
        host.addView(leaf)
        leaf.layout(-10, -5, 30, 15)
        val geometries = mutableListOf<SkeletonGeometry>()

        val collected = SkeletonMaskBuilder().collectFromContent(host, overlay, geometries)

        assertTrue(collected)
        assertEquals(1, geometries.size)
        assertEquals(RectF(0f, 0f, 30f, 15f), geometries.single().bounds)
    }

    @Test
    fun `内容后代自身不可见时不会被采集`() {
        val host = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            layout(0, 0, 100, 100)
        }
        val overlay = View(activity).apply { layout(0, 0, 100, 100) }
        val leaf = View(activity).apply { visibility = View.GONE }
        host.addView(leaf)
        leaf.layout(0, 0, 40, 20)
        val geometries = mutableListOf<SkeletonGeometry>()

        val collected = SkeletonMaskBuilder().collectFromContent(host, overlay, geometries)

        assertFalse(collected)
        assertTrue(geometries.isEmpty())
    }

    @Test
    fun `模板负坐标先计算原始边界再裁剪`() {
        val host = FrameLayout(activity).apply {
            visibility = View.GONE
            layout(0, 0, 100, 100)
        }
        val leaf = View(activity)
        host.addView(leaf)
        leaf.layout(-10, -5, 30, 15)
        val geometries = mutableListOf<SkeletonGeometry>()

        val collected = SkeletonMaskBuilder().collectFromTemplate(host, geometries)

        assertTrue(collected)
        assertEquals(RectF(0f, 0f, 30f, 15f), geometries.single().bounds)
    }

    @Test
    fun `内容空几何降级结果携带静态标记`() {
        val result = SkeletonMaskBuilder().buildMask(
            geometries = emptyList(),
            width = 20,
            height = 10,
            cornerRadius = 0f,
            fallbackToFullBounds = true,
        )

        assertTrue(result is MaskBuildResult.Success && result.fallbackUsed)
        (result as MaskBuildResult.Success).bitmap.recycle()
    }
}
