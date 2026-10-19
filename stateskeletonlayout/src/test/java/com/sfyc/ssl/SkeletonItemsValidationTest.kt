package com.sfyc.ssl

import android.app.Activity
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 条目拼装模板的配置校验测试。
 *
 * # 业务意图
 *
 * T1（整张模板 `ssl_skeletonTemplate`）与 T2（条目拼装 `skeletonItems`）
 * 是同一 `SkeletonSource.TEMPLATE` 下互斥的两种供给方式。配错时必须在入口
 * 快速失败，且错误信息带出属性名，让宿主一眼知道改哪个 XML 属性。
 * 校验失败不得污染已有配置（原值保留），否则一次误操作就会把正在展示的
 * 骨架形状弄丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeletonItemsValidationTest {

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

    private fun create(): StateSkeletonLayout = StateSkeletonLayout(activity).apply {
        id = View.generateViewId()
        setContentView(TextView(activity).apply { text = "content" })
    }

    @Test
    fun `T1与T2并存抛异常且错误信息含属性名`() {
        val layout = create()
        layout.skeletonConfig = SkeletonConfig(
            source = SkeletonSource.TEMPLATE,
            templateLayoutResId = R.layout.ssl_test_template_fixed,
        )
        try {
            layout.skeletonItems = listOf(SkeletonItemSpec(R.layout.ssl_test_template_fixed, 2))
            fail("T1+T2 并存应抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "错误信息应带出 T1 属性名，实际=${expected.message}",
                expected.message!!.contains("ssl_skeletonTemplate"),
            )
            assertTrue(
                "错误信息应带出 T2 属性名，实际=${expected.message}",
                expected.message!!.contains("skeletonItems"),
            )
        }
        // 原值保留：仍为 T1，items 未写入。
        assertNull(layout.skeletonItems)
        assertEquals(R.layout.ssl_test_template_fixed, layout.skeletonConfig.templateLayoutResId)
    }

    @Test
    fun `反向冲突同样回滚_items保留`() {
        val layout = create()
        layout.setSkeletonItemTemplate(R.layout.ssl_test_template_fixed, 2)
        val itemsBefore = layout.skeletonItems
        try {
            layout.skeletonConfig = SkeletonConfig(
                source = SkeletonSource.TEMPLATE,
                templateLayoutResId = R.layout.ssl_test_template_fixed,
            )
            fail("T2 已生效时再配 T1 应抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "错误信息应带出互斥双方，实际=${expected.message}",
                expected.message!!.contains("skeletonItems"),
            )
        }
        // 原值保留：仍为 T2。
        assertEquals(itemsBefore, layout.skeletonItems)
        assertNull(layout.skeletonConfig.templateLayoutResId)
    }

    @Test
    fun `source为CONTENT时配items抛异常`() {
        val layout = create()
        try {
            layout.skeletonItems = listOf(SkeletonItemSpec(R.layout.ssl_test_template_fixed, 2))
            fail("source=CONTENT 配 items 应抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "错误信息应指引切到 TEMPLATE，实际=${expected.message}",
                expected.message!!.contains("TEMPLATE"),
            )
        }
        assertNull("校验失败不得写入 items", layout.skeletonItems)
    }

    @Test
    fun `空列表视为未配置而不抛异常`() {
        val layout = create()
        layout.setSkeletonItemTemplate(R.layout.ssl_test_template_fixed, 2)
        layout.skeletonItems = emptyList()
        assertNull(layout.skeletonItems)
    }
}
