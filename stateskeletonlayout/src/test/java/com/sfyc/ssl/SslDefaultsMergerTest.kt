package com.sfyc.ssl

import org.robolectric.RuntimeEnvironment
import com.sfyc.ssl.internal.SslDefaultsMerger
import com.sfyc.ssl.internal.XmlSslAttrs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全局默认配置的合并优先级、部分覆盖与 reset 行为测试。
 *
 * 用例间通过 [StateSkeletonLayoutDefaults.reset] 隔离，避免全局状态串扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SslDefaultsMergerTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        StateSkeletonLayoutDefaults.reset()
    }

    @After
    fun tearDown() {
        StateSkeletonLayoutDefaults.reset()
    }

    // region 库内置默认（未安装全局默认）

    @Test
    fun merge_withoutGlobal_usesLibraryDefaults() {
        val merged = SslDefaultsMerger.merge(context, XmlSslAttrs(), SslGlobalDefaults())

        assertEquals(StateLayoutState.CONTENT, merged.initialState)
        assertFalse(merged.useSkeleton)
        assertEquals(SkeletonSource.CONTENT, merged.skeletonConfig.source)
        assertEquals(SkeletonEffect.SHIMMER, merged.skeletonConfig.effect)
        assertEquals(
            SkeletonConfig.DEFAULT_MASK_COLOR,
            merged.skeletonConfig.maskColor,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_HIGHLIGHT_COLOR,
            merged.skeletonConfig.highlightColor,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_CORNER_RADIUS_PX,
            merged.skeletonConfig.cornerRadius,
            0.001f,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_ANIMATION_DURATION_MILLIS,
            merged.skeletonConfig.animationDurationMillis,
        )
        assertEquals(SkeletonDirection.START_TO_END, merged.skeletonConfig.direction)
        assertEquals(
            SkeletonConfig.DEFAULT_ANGLE_DEGREES,
            merged.skeletonConfig.angleDegrees,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_PULSE_MIN_ALPHA,
            merged.skeletonConfig.pulseMinAlpha,
            0.001f,
        )
        assertEquals(
            SkeletonConfig.DEFAULT_PULSE_MAX_ALPHA,
            merged.skeletonConfig.pulseMaxAlpha,
            0.001f,
        )
        assertEquals(0L, merged.loadingShowDelayMillis)
        assertEquals(300L, merged.minimumLoadingDurationMillis)
        assertEquals(StateTransitionEffect.CROSSFADE, merged.transitionEffect)
        assertEquals(180L, merged.transitionDurationMillis)
        assertTrue(merged.announceStateChanges)
    }

    // endregion

    // region Global Config 覆盖库内置

    @Test
    fun merge_withFullGlobal_overridesAllFields() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(
                useSkeleton = true,
                skeletonSource = SkeletonSource.TEMPLATE,
                initialState = StateLayoutState.ERROR,
                effect = SkeletonEffect.PULSE,
                maskColor = 0xFF112233.toInt(),
                highlightColor = 0xFF445566.toInt(),
                cornerRadius = 24f,
                animationDurationMillis = 2000L,
                direction = SkeletonDirection.END_TO_START,
                angleDegrees = 45,
                pulseMinAlpha = 0.1f,
                pulseMaxAlpha = 0.9f,
                loadingShowDelayMillis = 100L,
                minimumLoadingDurationMillis = 500L,
                transitionEffect = StateTransitionEffect.NONE,
                transitionDurationMillis = 250L,
                announceStateChanges = false,
            ),
        )

        val merged = SslDefaultsMerger.merge(
            context,
            XmlSslAttrs(templateLayoutResId = R.layout.ssl_test_state_simple),
            StateSkeletonLayoutDefaults.current,
        )

        assertEquals(StateLayoutState.ERROR, merged.initialState)
        assertTrue(merged.useSkeleton)
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(SkeletonEffect.PULSE, merged.skeletonConfig.effect)
        assertEquals(0xFF112233.toInt(), merged.skeletonConfig.maskColor)
        assertEquals(0xFF445566.toInt(), merged.skeletonConfig.highlightColor)
        assertEquals(24f, merged.skeletonConfig.cornerRadius, 0.001f)
        assertEquals(2000L, merged.skeletonConfig.animationDurationMillis)
        assertEquals(SkeletonDirection.END_TO_START, merged.skeletonConfig.direction)
        assertEquals(45, merged.skeletonConfig.angleDegrees)
        assertEquals(0.1f, merged.skeletonConfig.pulseMinAlpha, 0.001f)
        assertEquals(0.9f, merged.skeletonConfig.pulseMaxAlpha, 0.001f)
        assertEquals(100L, merged.loadingShowDelayMillis)
        assertEquals(500L, merged.minimumLoadingDurationMillis)
        assertEquals(StateTransitionEffect.NONE, merged.transitionEffect)
        assertEquals(250L, merged.transitionDurationMillis)
        assertFalse(merged.announceStateChanges)
    }

    // endregion

    // region 部分覆盖

    @Test
    fun merge_withPartialGlobal_keepsLibraryDefaultsForUnsetFields() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(
                maskColor = 0xFFABCDEF.toInt(),
                useSkeleton = true,
            ),
        )

        val merged = SslDefaultsMerger.merge(
            context,
            XmlSslAttrs(),
            StateSkeletonLayoutDefaults.current,
        )

        // 已设置字段走 Global。
        assertEquals(0xFFABCDEF.toInt(), merged.skeletonConfig.maskColor)
        assertTrue(merged.useSkeleton)
        // 未设置字段保持库内置默认。
        assertEquals(
            SkeletonConfig.DEFAULT_HIGHLIGHT_COLOR,
            merged.skeletonConfig.highlightColor,
        )
        assertEquals(SkeletonEffect.SHIMMER, merged.skeletonConfig.effect)
        assertEquals(
            SkeletonConfig.DEFAULT_CORNER_RADIUS_PX,
            merged.skeletonConfig.cornerRadius,
            0.001f,
        )
        assertEquals(StateLayoutState.CONTENT, merged.initialState)
        assertFalse(merged.announceStateChanges.not())
    }

    // endregion

    // region 优先级链

    @Test
    fun merge_xmlOverridesGlobal() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(
                maskColor = 0xFF111111.toInt(),
                effect = SkeletonEffect.PULSE,
                useSkeleton = true,
            ),
        )

        val xmlAttrs = XmlSslAttrs(
            maskColor = 0xFF222222.toInt(),
            effect = SkeletonEffect.SOLID,
            useSkeleton = false,
        )

        val merged = SslDefaultsMerger.merge(
            context,
            xmlAttrs,
            StateSkeletonLayoutDefaults.current,
        )

        // XML 显式设置胜出。
        assertEquals(0xFF222222.toInt(), merged.skeletonConfig.maskColor)
        assertEquals(SkeletonEffect.SOLID, merged.skeletonConfig.effect)
        assertFalse(merged.useSkeleton)
    }

    @Test
    fun merge_globalOverridesLibraryDefault() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(effect = SkeletonEffect.SOLID),
        )

        val merged = SslDefaultsMerger.merge(
            context,
            XmlSslAttrs(),
            StateSkeletonLayoutDefaults.current,
        )

        // Global 覆盖库内置默认。
        assertEquals(SkeletonEffect.SOLID, merged.skeletonConfig.effect)
    }

    // endregion

    // region install 语义

    @Test
    fun install_replacesEntirely_notFieldMerge() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(maskColor = 0xFF111111.toInt(), useSkeleton = true),
        )
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(effect = SkeletonEffect.PULSE),
        )

        val current = StateSkeletonLayoutDefaults.current

        // 第二次 install 整体替换：第一次的字段全部丢失。
        assertEquals(null, current.maskColor)
        assertEquals(null, current.useSkeleton)
        assertEquals(SkeletonEffect.PULSE, current.effect)
    }

    // endregion

    // region reset

    @Test
    fun reset_restoresUninstalledState() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(maskColor = 0xFFABCDEF.toInt()),
        )
        StateSkeletonLayoutDefaults.reset()

        val current = StateSkeletonLayoutDefaults.current

        assertEquals(null, current.maskColor)
        assertEquals(null, current.useSkeleton)
        assertEquals(null, current.effect)
    }

    // endregion

    // region 校验

    @Test
    fun merge_withNegativeCornerRadius_throwsWithAttributeName() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(cornerRadius = -1f),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SslDefaultsMerger.merge(
                context,
                XmlSslAttrs(),
                StateSkeletonLayoutDefaults.current,
            )
        }

        assertTrue(exception.message!!.contains("cornerRadius"))
    }

    @Test
    fun merge_withZeroAnimationDuration_throwsWithAttributeName() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(animationDurationMillis = 0L),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SslDefaultsMerger.merge(
                context,
                XmlSslAttrs(),
                StateSkeletonLayoutDefaults.current,
            )
        }

        assertTrue(exception.message!!.contains("animationDurationMillis"))
    }

    // endregion

    // region StateSkeletonLayoutDefaults API

    @Test
    fun current_defaultsToAllNull() {
        val current = StateSkeletonLayoutDefaults.current

        assertEquals(null, current.useSkeleton)
        assertEquals(null, current.skeletonSource)
        assertEquals(null, current.initialState)
        assertEquals(null, current.effect)
        assertEquals(null, current.maskColor)
    }

    @Test
    fun install_overwritesPrevious() {
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(maskColor = 0xFF111111.toInt()),
        )
        StateSkeletonLayoutDefaults.install(
            SslGlobalDefaults(maskColor = 0xFF222222.toInt()),
        )

        assertEquals(
            0xFF222222.toInt(),
            StateSkeletonLayoutDefaults.current.maskColor,
        )
    }

    // endregion
}
