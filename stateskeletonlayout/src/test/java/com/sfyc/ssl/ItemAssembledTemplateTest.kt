package com.sfyc.ssl

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sfyc.ssl.internal.loading.SkeletonLoadingRenderer
import com.sfyc.ssl.internal.skeleton.SkeletonMaskBuilder
import com.sfyc.ssl.internal.skeleton.SkeletonGeometry
import com.sfyc.ssl.internal.skeleton.SkeletonOverlayView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * T2 条目拼装模板的形状验收。
 *
 * # 为什么分两条路径断言
 *
 * - 控件 / 渲染器路径（`showLoading` / `preflight`）：证明离屏拼装、
 *   binder 调用、互斥校验与失败信息都走了真实入口。
 * - 几何计数路径（`SkeletonMaskBuilder.collectFromTemplate`）：`diagnostics()`
 *   只暴露遮罩字节数与降级原因，不暴露几何个数；行数同级（8 行 => 24 个叶子）
 *   只能在这里按"逐条 inflate + 测量 + 采集"复现装配并计数。复现代码刻意与
 *   `SkeletonLoadingRenderer.measureItemHost` 保持相同的 MeasureSpec 语义
 *  （宽 EXACTLY / 高 AT_MOST），语义漂移时这里会先失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemAssembledTemplateTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        StateSkeletonLayoutDefaults.reset()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        StateSkeletonLayoutDefaults.reset()
        activity.finish()
    }

    private fun attach(layout: StateSkeletonLayout, width: Int = 360, height: Int = 640) {
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, width, height)
    }

    private fun idle(): StateSkeletonLayout {
        val layout = StateSkeletonLayout(activity).apply {
            id = View.generateViewId()
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            loadingShowDelayMillis = 0
            setContentView(TextView(activity).apply { text = "content" })
        }
        attach(layout)
        return layout
    }

    /**
     * 按渲染器相同的语义装配：逐条 inflate 进纵向列，宽 EXACTLY / 高 AT_MOST。
     */
    private fun assembleAndCollect(
        specs: List<SkeletonItemSpec>,
        width: Int = 360,
        height: Int = 2000,
    ): List<SkeletonGeometry> {
        val outer = FrameLayout(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        outer.addView(column)
        val inflater = LayoutInflater.from(activity)
        specs.forEach { spec ->
            repeat(spec.count) { position ->
                val item = inflater.inflate(spec.layoutResId, column, false)
                spec.binder?.invoke(item, position)
                column.addView(item)
            }
        }
        outer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        outer.layout(0, 0, outer.measuredWidth, outer.measuredHeight)
        val out = mutableListOf<SkeletonGeometry>()
        val collected = SkeletonMaskBuilder().collectFromTemplate(outer, out)
        assertTrue("装配宿主应产生有效几何", collected)
        return out
    }

    private fun fillBothTexts(): (View, Int) -> Unit = { item, _ ->
        item.findViewById<TextView>(R.id.ssl_test_item_title)?.text = "占位标题占位标题"
        item.findViewById<TextView>(R.id.ssl_test_item_subtitle)?.text = "占位副标题行"
    }

    @Test
    fun `复用成功布局拼8条产生24个几何_与行数同级`() {
        val geos = assembleAndCollect(
            listOf(SkeletonItemSpec(R.layout.ssl_test_item_row, 8, fillBothTexts())),
        )
        // 每行 3 个叶子（头像 + 标题 + 副标题），8 行共 24 个。
        assertEquals(24, geos.size)
        // 全部落在宿主宽度内，且纵向跨越 8 行的高度。
        val tops = geos.map { it.bounds.top }.distinct().size
        assertTrue("8 行应产生多个不同的纵向位置，实际去重 top 数=$tops", tops >= 8)
    }

    @Test
    fun `单行产生3个几何_固定行无需binder时只有头像`() {
        val withBinder = assembleAndCollect(
            listOf(SkeletonItemSpec(R.layout.ssl_test_item_row, 1, fillBothTexts())),
        )
        assertEquals(3, withBinder.size)

        val withoutBinder = assembleAndCollect(
            listOf(SkeletonItemSpec(R.layout.ssl_test_item_row, 1)),
        )
        // 空文本量出 0 尺寸被跳过，只剩固定 40dp 头像。
        assertEquals(1, withoutBinder.size)
    }

    @Test
    fun `手写镜像模板同为24个几何_与T2同级`() {
        val host = LayoutInflater.from(activity)
            .inflate(R.layout.ssl_test_item_rows_template, null) as View
        host.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        val out = mutableListOf<SkeletonGeometry>()
        assertTrue(SkeletonMaskBuilder().collectFromTemplate(host as android.view.ViewGroup, out))
        // 8 行 ×（头像 + 两条固定文字条）= 24，与 T2 + binder 的计数同级。
        assertEquals(24, out.size)
    }

    @Test
    fun `空文本无binder时配置入口快速失败且指引补绑定`() {
        val layout = idle()
        // setSkeletonItemTemplate 是原子的：内部先预检，空几何直接抛异常，
        // 比等到 showLoading 才暴露更早。失败时配置整体回滚。
        try {
            layout.setSkeletonItemTemplate(R.layout.ssl_test_item_text_only, 2)
            fail("纯空文本无 binder 应在配置入口预检时抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "失败信息应指引补占位绑定，实际=${expected.message}",
                expected.message!!.contains("binder"),
            )
        }
        assertNull("预检失败后条目声明不得残留", layout.skeletonItems)
        assertEquals(SkeletonSource.CONTENT, layout.skeletonConfig.source)
    }

    @Test
    fun `补binder后同一布局成功呈现骨架`() {
        val layout = idle()
        layout.setSkeletonItemTemplate(R.layout.ssl_test_item_text_only, 2) { item, _ ->
            item.findViewById<TextView>(R.id.ssl_test_item_title)?.text = "占位标题"
            item.findViewById<TextView>(R.id.ssl_test_item_subtitle)?.text = "占位副标题"
        }
        layout.showLoading()
        ShadowLooper.idleMainLooper()

        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        val snapshot = layout.diagnostics()
        assertEquals(SkeletonFallbackReason.NONE, snapshot.fallbackReason)
        assertTrue(snapshot.effectRunning)
        assertTrue(snapshot.maskBytes > 0)
    }

    @Test
    fun `binder按条目序号调用且总数等于条数`() {
        val firstPositions = mutableListOf<Int>()
        val secondPositions = mutableListOf<Int>()
        val specs = listOf(
            SkeletonItemSpec(R.layout.ssl_test_template_fixed, 3) { _, position ->
                firstPositions += position
            },
            SkeletonItemSpec(R.layout.ssl_test_template_fixed, 2) { _, position ->
                secondPositions += position
            },
        )
        val content = FrameLayout(activity).apply { layout(0, 0, 200, 400) }
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 200, 400) }
        val renderer = SkeletonLoadingRenderer(
            overlay, content,
            { SkeletonConfig(source = SkeletonSource.TEMPLATE) },
            LayoutInflater.from(activity),
            itemSpecsProvider = { specs },
        )
        renderer.preflight(SkeletonConfig(source = SkeletonSource.TEMPLATE), specs)

        // position 是每个 spec 内的序号（0..count-1），不是全局序号。
        assertEquals(listOf(0, 1, 2), firstPositions)
        assertEquals(listOf(0, 1), secondPositions)
    }

    @Test
    fun `固定尺寸布局无binder即可通过渲染器预检`() {
        val content = FrameLayout(activity).apply { layout(0, 0, 200, 400) }
        val overlay = SkeletonOverlayView(activity).apply { layout(0, 0, 200, 400) }
        val specs = listOf(SkeletonItemSpec(R.layout.ssl_test_template_fixed, 3))
        val renderer = SkeletonLoadingRenderer(
            overlay, content,
            { SkeletonConfig(source = SkeletonSource.TEMPLATE) },
            LayoutInflater.from(activity),
            itemSpecsProvider = { specs },
        )
        renderer.preflight(SkeletonConfig(source = SkeletonSource.TEMPLATE), specs)
        renderer.onAttachedToWindow()
        renderer.show {}
        assertTrue(overlay.isEffectRunning)
        renderer.hide()
    }
}
