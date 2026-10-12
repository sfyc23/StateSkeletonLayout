package com.sfyc.ssl

import android.content.Context
import com.sfyc.ssl.internal.SslDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 默认值一致性测试（P-01 的验收条件）。
 *
 * # 为什么需要
 *
 * 同一批默认值曾经分散在三个地方：
 * 1. `res/values/styles.xml` 的样式项；
 * 2. `SslDefaultsMerger.merge()` 的 `?:` 字面量；
 * 3. `SkeletonConfig` / `StateCoordinator` 的构造默认值。
 *
 * 改一处漏一处既不会编译失败也不会测试失败，只会让
 * 「宿主在 AppTheme 声明了 `ssl_stateSkeletonLayoutStyle`」与
 * 「宿主没声明」两条构造路径拿到不同的默认值 —— 而当时两者数值
 * 恰好相同，属于巧合而非约束。
 *
 * 本测试把那个巧合变成约束：逐字段断言 styles.xml 解析出的值与
 * [SslDefaultValues] 一致。任一侧改动而另一侧未同步即失败。
 *
 * 颜色与圆角不在此断言：它们由资源提供（`ssl_mask_default` 等），
 * 在资源体系内本来就只有一个定义处，且需要昼夜限定符。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class SslDefaultValuesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * 直接读取库默认样式 `ssl_StateSkeletonLayout` 的每个成员，
     * 与 [SslDefaultValues] 逐字段比对。
     *
     * 不用 `obtainStyledAttributes(null, …, defStyleAttr, 0)`：那条路径只有在
     * 宿主 Theme 声明了 `ssl_stateSkeletonLayoutStyle` 时才解析到样式内容，
     * 测试环境默认没有，会全部拿到"未设置"。直接按样式资源读才是真正的读取。
     */
    @Test
    fun `styles_xml 与内置默认值逐字段一致`() {
        val a = context.obtainStyledAttributes(
            R.style.ssl_StateSkeletonLayout,
            R.styleable.SslStateSkeletonLayout,
        )
        try {
            fun int(name: String, index: Int): Int = a.getInt(index, Int.MIN_VALUE).also {
                assertEquals("$name 未在 styles.xml 中声明", false, it == Int.MIN_VALUE)
            }

            assertEquals(
                "ssl_useSkeleton",
                SslDefaultValues.USE_SKELETON,
                a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_useSkeleton, !SslDefaultValues.USE_SKELETON),
            )
            assertEquals(
                "ssl_skeletonSource",
                0,
                int("ssl_skeletonSource", R.styleable.SslStateSkeletonLayout_ssl_skeletonSource),
            )
            assertEquals(
                "ssl_skeletonEffect",
                1,
                int("ssl_skeletonEffect", R.styleable.SslStateSkeletonLayout_ssl_skeletonEffect),
            )
            assertEquals(
                "ssl_animationDuration",
                SslDefaultValues.ANIMATION_DURATION_MILLIS.toInt(),
                int("ssl_animationDuration", R.styleable.SslStateSkeletonLayout_ssl_animationDuration),
            )
            assertEquals(
                "ssl_shimmerDirection",
                SslDefaultValues.DIRECTION.ordinal,
                int("ssl_shimmerDirection", R.styleable.SslStateSkeletonLayout_ssl_shimmerDirection),
            )
            assertEquals(
                "ssl_shimmerAngle",
                SslDefaultValues.ANGLE_DEGREES,
                int("ssl_shimmerAngle", R.styleable.SslStateSkeletonLayout_ssl_shimmerAngle),
            )
            assertEquals(
                "ssl_pulseMinAlpha",
                SslDefaultValues.PULSE_MIN_ALPHA,
                a.getFloat(R.styleable.SslStateSkeletonLayout_ssl_pulseMinAlpha, Float.MIN_VALUE),
                0.0001f,
            )
            assertEquals(
                "ssl_pulseMaxAlpha",
                SslDefaultValues.PULSE_MAX_ALPHA,
                a.getFloat(R.styleable.SslStateSkeletonLayout_ssl_pulseMaxAlpha, Float.MIN_VALUE),
                0.0001f,
            )
            assertEquals(
                "ssl_loadingShowDelay",
                SslDefaultValues.LOADING_SHOW_DELAY_MILLIS.toInt(),
                int("ssl_loadingShowDelay", R.styleable.SslStateSkeletonLayout_ssl_loadingShowDelay),
            )
            assertEquals(
                "ssl_minLoadingDuration",
                SslDefaultValues.MINIMUM_LOADING_DURATION_MILLIS.toInt(),
                int("ssl_minLoadingDuration", R.styleable.SslStateSkeletonLayout_ssl_minLoadingDuration),
            )
            assertEquals(
                "ssl_transitionEffect",
                1,
                int("ssl_transitionEffect", R.styleable.SslStateSkeletonLayout_ssl_transitionEffect),
            )
            assertEquals(
                "ssl_transitionDuration",
                SslDefaultValues.TRANSITION_DURATION_MILLIS.toInt(),
                int("ssl_transitionDuration", R.styleable.SslStateSkeletonLayout_ssl_transitionDuration),
            )
            assertEquals(
                "ssl_announceStateChanges",
                SslDefaultValues.ANNOUNCE_STATE_CHANGES,
                a.getBoolean(R.styleable.SslStateSkeletonLayout_ssl_announceStateChanges, !SslDefaultValues.ANNOUNCE_STATE_CHANGES),
            )
        } finally {
            a.recycle()
        }
    }

    /**
     * 两条构造路径必须得到相同的默认值。
     *
     * 路径 A：宿主未在 Theme 中声明 `ssl_stateSkeletonLayoutStyle`，此时
     * `styles.xml` 的内容不参与解析，全部落到 [SslDefaultValues]。
     * 路径 B：宿主声明了该样式，此时 Theme Attribute 层生效。
     *
     * 这是 P-01 的核心断言：两条路径逐字段相等。
     */
    @Test
    fun `无Theme样式与有Theme样式两条路径默认值一致`() {
        val bare = SkeletonConfig()
        val fromResources = SkeletonConfig.from(context)

        // 形状 / 时长 / 角度 / 脉冲与资源无关，必须完全相等。
        assertEquals(bare.source, fromResources.source)
        assertEquals(bare.effect, fromResources.effect)
        assertEquals(bare.animationDurationMillis, fromResources.animationDurationMillis)
        assertEquals(bare.direction, fromResources.direction)
        assertEquals(bare.angleDegrees, fromResources.angleDegrees)
        assertEquals(bare.pulseMinAlpha, fromResources.pulseMinAlpha, 0.0001f)
        assertEquals(bare.pulseMaxAlpha, fromResources.pulseMaxAlpha, 0.0001f)
        assertEquals(bare.maximumMaskPixels, fromResources.maximumMaskPixels)

        // 颜色与圆角来自资源，`from(context)` 必须与资源默认值一致。
        assertEquals(context.getColor(R.color.ssl_mask_default), fromResources.maskColor)
        assertEquals(context.getColor(R.color.ssl_highlight_default), fromResources.highlightColor)
        assertEquals(
            context.resources.getDimension(R.dimen.ssl_corner_radius_default),
            fromResources.cornerRadius,
            0.0001f,
        )
    }

    /**
     * 公开常量必须是默认值的唯一真源：协调器与合并器都引用它们，
     * 而不是各自声明一份字面量。
     */
    @Test
    fun `公开常量与内置默认值同源`() {
        assertEquals(
            StateSkeletonLayout.DEFAULT_LOADING_SHOW_DELAY_MILLIS,
            SslDefaultValues.LOADING_SHOW_DELAY_MILLIS,
        )
        assertEquals(
            StateSkeletonLayout.DEFAULT_MINIMUM_LOADING_DURATION_MILLIS,
            SslDefaultValues.MINIMUM_LOADING_DURATION_MILLIS,
        )
        assertEquals(
            StateSkeletonLayout.DEFAULT_TRANSITION_DURATION_MILLIS,
            SslDefaultValues.TRANSITION_DURATION_MILLIS,
        )
        assertEquals(
            StateSkeletonLayout.DEFAULT_RETRY_CLICK_THROTTLE_MILLIS,
            SslDefaultValues.RETRY_CLICK_THROTTLE_MILLIS,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS,
            SslDefaultValues.ANIMATION_DURATION_MILLIS,
        )
        assertEquals(SkeletonConfig.DEFAULT_ANGLE_DEGREES, SslDefaultValues.ANGLE_DEGREES)
        assertEquals(SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA, SslDefaultValues.PULSE_MIN_ALPHA, 0.0001f)
        assertEquals(SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA, SslDefaultValues.PULSE_MAX_ALPHA, 0.0001f)
    }
}
