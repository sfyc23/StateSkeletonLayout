package com.sfyc.ssl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SkeletonItemSpec] 数据语义测试（T2 条目拼装模板的声明单元）。
 *
 * 本类是纯 JVM 测试：只断言构造校验与 data class 值语义，
 * 不 inflate 布局、不触碰 View，因此不需要 Robolectric。
 */
class SkeletonItemSpecTest {

    @Test(expected = IllegalArgumentException::class)
    fun `layoutResId为0抛异常`() {
        SkeletonItemSpec(0, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count为0抛异常`() {
        SkeletonItemSpec(R.layout.ssl_test_template_fixed, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count为负数抛异常`() {
        SkeletonItemSpec(R.layout.ssl_test_template_fixed, -1)
    }

    @Test
    fun `copy与equals按值语义`() {
        val first = SkeletonItemSpec(R.layout.ssl_test_template_fixed, 8)
        assertEquals(first, first.copy())
        assertEquals(3, first.copy(count = 3).count)

        // binder 参与 equals：同一 lambda 实例相等，不同实例不等。
        val binder: (android.view.View, Int) -> Unit = { _, _ -> }
        val withBinder = SkeletonItemSpec(R.layout.ssl_test_template_fixed, 8, binder)
        assertEquals(withBinder, withBinder.copy())
        assertEquals(withBinder, SkeletonItemSpec(R.layout.ssl_test_template_fixed, 8, binder))
        assertNotEquals(withBinder, first)
        assertNotEquals(
            withBinder,
            SkeletonItemSpec(R.layout.ssl_test_template_fixed, 8) { _, _ -> },
        )
    }

    @Test
    fun `binder缺省为null且默认条数为8`() {
        val spec = SkeletonItemSpec(R.layout.ssl_test_template_fixed)
        assertNull("binder 缺省必须为 null，XML 单类型声明即无绑定", spec.binder)
        assertEquals(
            "默认条数与 XML 缺省的 ssl_skeletonItemCount 对齐",
            8,
            spec.count,
        )
        assertEquals(SkeletonItemSpec.DEFAULT_COUNT, spec.count)
    }
}
