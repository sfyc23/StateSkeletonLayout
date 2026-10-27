package com.sfyc.ssl

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.sfyc.ssl.internal.SslDefaultsMerger
import com.sfyc.ssl.internal.XmlSslAttrs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 骨架来源 / 模板供给的优先级与互斥回归测试（方案第 5、8 节）。
 *
 * 来源对比使用不同布局资源、不同数量，避免值相同掩盖优先级错误。
 * T1 统一用 `ssl_test_template_fixed`，T2-A 用 `ssl_test_item_row_fixed`，
 * T2-B 用 `ssl_test_item_row`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SslTemplatePriorityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val t1 = R.layout.ssl_test_template_fixed
    private val itemA = R.layout.ssl_test_item_row_fixed
    private val itemB = R.layout.ssl_test_item_row

    @Before fun before() { StateSkeletonLayoutDefaults.reset() }
    @After fun after() { StateSkeletonLayoutDefaults.reset() }

    private fun merge(
        direct: XmlSslAttrs = XmlSslAttrs(),
        pageStyle: XmlSslAttrs = XmlSslAttrs(),
        global: SslGlobalDefaults = SslGlobalDefaults(),
        theme: XmlSslAttrs = XmlSslAttrs(),
    ) = SslDefaultsMerger.mergeLayered(context, direct, pageStyle, global, theme)

    // P01：Theme T1 + XML T2 × 3 → TEMPLATE + T2 × 3，清除 T1。
    @Test fun p01_themeT1_xmlT2_wins() {
        val merged = merge(
            direct = XmlSslAttrs(source = SkeletonSource.TEMPLATE, skeletonItemLayoutResId = itemA, skeletonItemCount = 3),
            theme = XmlSslAttrs(source = SkeletonSource.TEMPLATE, templateLayoutResId = t1),
        )
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertNull(merged.skeletonConfig.templateLayoutResId)
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(3, merged.skeletonItemCount)
    }

    // P02：Theme T2 × 8 + XML T1 → TEMPLATE + T1，不残留 item 或数量。
    @Test fun p02_themeT2_xmlT1_wins() {
        val merged = merge(
            direct = XmlSslAttrs(source = SkeletonSource.TEMPLATE, templateLayoutResId = t1),
            theme = XmlSslAttrs(source = SkeletonSource.TEMPLATE, skeletonItemLayoutResId = itemA, skeletonItemCount = 8),
        )
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(t1, merged.skeletonConfig.templateLayoutResId)
        assertEquals(0, merged.skeletonItemLayoutResId)
        assertNull(merged.skeletonItemCount)
    }

    // P03：Global CONTENT + XML T2 → XML 胜出，转为 TEMPLATE。
    @Test fun p03_globalContent_xmlT2_wins() {
        val merged = merge(
            direct = XmlSslAttrs(skeletonItemLayoutResId = itemA),
            global = SslGlobalDefaults(skeletonSource = SkeletonSource.CONTENT),
        )
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(SkeletonItemSpec.DEFAULT_COUNT, merged.skeletonItemCount)
    }

    // P04：Theme T2 + XML CONTENT → CONTENT 胜出，不保留低层 items / 数量。
    @Test fun p04_themeT2_xmlContent_shields() {
        val merged = merge(
            direct = XmlSslAttrs(source = SkeletonSource.CONTENT),
            theme = XmlSslAttrs(source = SkeletonSource.TEMPLATE, skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(SkeletonSource.CONTENT, merged.skeletonConfig.source)
        assertEquals(0, merged.skeletonItemLayoutResId)
        assertNull(merged.skeletonItemCount)
    }

    // P05：Theme T2 + Global CONTENT，无页面覆盖 → Global 胜出。
    @Test fun p05_themeT2_globalContent_wins() {
        val merged = merge(
            global = SslGlobalDefaults(skeletonSource = SkeletonSource.CONTENT),
            theme = XmlSslAttrs(source = SkeletonSource.TEMPLATE, skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(SkeletonSource.CONTENT, merged.skeletonConfig.source)
        assertEquals(0, merged.skeletonItemLayoutResId)
    }

    // P06：Global CONTENT + 页面 Style T2 → 页面 Style 胜出。
    @Test fun p06_globalContent_pageStyleT2_wins() {
        val merged = merge(
            pageStyle = XmlSslAttrs(source = SkeletonSource.TEMPLATE, skeletonItemLayoutResId = itemA, skeletonItemCount = 3),
            global = SslGlobalDefaults(skeletonSource = SkeletonSource.CONTENT),
        )
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(3, merged.skeletonItemCount)
    }

    // P07：页面 Style T1 + XML 直接 T2 → 直接属性胜出，不误判同层冲突。
    @Test fun p07_styleT1_directT2_wins() {
        val merged = merge(
            direct = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 3),
            pageStyle = XmlSslAttrs(templateLayoutResId = t1),
        )
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(3, merged.skeletonItemCount)
        assertNull(merged.skeletonConfig.templateLayoutResId)
    }

    // P08：页面 Style T2 + XML 直接 T1 → 直接属性胜出，旧数量不残留。
    @Test fun p08_styleT2_directT1_wins() {
        val merged = merge(
            direct = XmlSslAttrs(templateLayoutResId = t1),
            pageStyle = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(t1, merged.skeletonConfig.templateLayoutResId)
        assertEquals(0, merged.skeletonItemLayoutResId)
        assertNull(merged.skeletonItemCount)
    }

    // P09：同一有效层 T1 + T2 → 报错，信息含双方属性与来源层。
    @Test fun p09_sameLayer_T1_T2_throws() {
        try {
            merge(direct = XmlSslAttrs(templateLayoutResId = t1, skeletonItemLayoutResId = itemA))
            fail("同层 T1+T2 应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonTemplate"))
            assertTrue(e.message!!.contains("ssl_skeletonItemLayout") || e.message!!.contains("skeletonItems"))
            assertTrue(e.message!!.contains("XML 直接属性"))
        }
    }

    // P10：同一有效层 CONTENT + T2 → 报错；同层 CONTENT + T1 保持允许。
    @Test fun p10_sameLayer_contentT2_throws_contentT1_allowed() {
        try {
            merge(direct = XmlSslAttrs(source = SkeletonSource.CONTENT, skeletonItemLayoutResId = itemA))
            fail("同层 CONTENT+T2 应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("content"))
        }
        val allowed = merge(direct = XmlSslAttrs(source = SkeletonSource.CONTENT, templateLayoutResId = t1))
        assertEquals(SkeletonSource.CONTENT, allowed.skeletonConfig.source)
    }

    // P11：XML 只声明 TEMPLATE，Theme 有 T2 → 继承 T2；无任何供给 → 缺配。
    @Test fun p11_templateInheritsLowSupply_missingThrows() {
        val inherited = merge(
            direct = XmlSslAttrs(source = SkeletonSource.TEMPLATE),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(itemA, inherited.skeletonItemLayoutResId)
        assertEquals(5, inherited.skeletonItemCount)
        try {
            merge(direct = XmlSslAttrs(source = SkeletonSource.TEMPLATE))
            fail("TEMPLATE 无供给应报缺配")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonTemplate"))
        }
    }

    // P12：Theme item A × 5，XML 只改数量为 3 → item A × 3。
    @Test fun p12_countOnly_modifiesInherited() {
        val merged = merge(
            direct = XmlSslAttrs(skeletonItemCount = 3),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(3, merged.skeletonItemCount)
    }

    // P13：Theme item A × 5，XML 改为 item B 且不提供数量 → item B × 8。
    @Test fun p13_newLayout_withoutCount_usesDefault() {
        val merged = merge(
            direct = XmlSslAttrs(skeletonItemLayoutResId = itemB),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(itemB, merged.skeletonItemLayoutResId)
        assertEquals(8, merged.skeletonItemCount)
    }

    // P14：XML 孤立数量；同层 T1 + 数量 → 数量依赖错误。
    @Test fun p14_orphanCount_throws() {
        try {
            merge(direct = XmlSslAttrs(skeletonItemCount = 3), theme = XmlSslAttrs(templateLayoutResId = t1))
            fail("孤立数量应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonItemCount"))
        }
        try {
            merge(direct = XmlSslAttrs(templateLayoutResId = t1, skeletonItemCount = 3))
            fail("同层 T1+数量应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonItemCount"))
        }
    }

    // P15：被高层覆盖的低层 T2 含数量 → 不进入最终配置，不触发孤立错误。
    @Test fun p15_coveredLowCount_ignored() {
        val merged = merge(
            direct = XmlSslAttrs(templateLayoutResId = t1),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(t1, merged.skeletonConfig.templateLayoutResId)
        assertNull(merged.skeletonItemCount)
    }

    // P16：XML 直接清空 T1 → 不再继承 Theme T1；TEMPLATE 无供给报缺配。
    @Test fun p16_directClearsT1_blocksInheritance() {
        try {
            merge(
                direct = XmlSslAttrs(source = SkeletonSource.TEMPLATE, templateCleared = true),
                theme = XmlSslAttrs(templateLayoutResId = t1),
            )
            fail("清空后无供给应报缺配")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonTemplate"))
        }
    }

    // P17：页面 Style 用 @empty 清除父 T1 再提供 T2 → 采用 T2。
    @Test fun p17_styleClearsParentT1_adoptsT2() {
        val merged = merge(
            pageStyle = XmlSslAttrs(
                source = SkeletonSource.TEMPLATE,
                templateCleared = true,
                skeletonItemLayoutResId = itemA,
                skeletonItemCount = 3,
            ),
        )
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(3, merged.skeletonItemCount)
    }

    // P18：页面 Style 清除父 item 布局与数量再提供 T1 → 不残留。
    @Test fun p18_styleClearsParentT2_adoptsT1() {
        val merged = merge(
            pageStyle = XmlSslAttrs(
                templateLayoutResId = t1,
                skeletonItemLayoutCleared = true,
                skeletonItemCountCleared = true,
            ),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(t1, merged.skeletonConfig.templateLayoutResId)
        assertEquals(0, merged.skeletonItemLayoutResId)
        assertNull(merged.skeletonItemCount)
    }

    // P19：XML 将数量设为 @empty → 活动 T2 恢复默认 8，不继承低层数量。
    @Test fun p19_countCleared_resetsToDefault() {
        val merged = merge(
            direct = XmlSslAttrs(skeletonItemCountCleared = true),
            theme = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 5),
        )
        assertEquals(itemA, merged.skeletonItemLayoutResId)
        assertEquals(8, merged.skeletonItemCount)
    }

    // P28：item 数量 0 仍报错；动画时长 0 仍由 SkeletonConfig 拒绝。
    @Test fun p28_zeroCount_rejected() {
        try {
            merge(
                direct = XmlSslAttrs(skeletonItemLayoutResId = itemA, skeletonItemCount = 0),
            )
            fail("数量 0 应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_skeletonItemCount"))
        }
    }

    // 真实布局 inflate：P01 Theme T1 + XML T2 × 3。
    @Test @Config(sdk = [26, 34])
    fun inflate_p01_themeT1_xmlT2() {
        val themed = ContextThemeWrapper(context, R.style.ssl_TestTemplateTheme_T1)
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.ssl_test_host_template_priority_p01, null) as StateSkeletonLayout
        assertEquals(SkeletonSource.TEMPLATE, view.skeletonConfig.source)
        assertNull(view.skeletonConfig.templateLayoutResId)
        assertEquals(1, view.skeletonItems!!.size)
        assertEquals(itemA, view.skeletonItems!![0].layoutResId)
        assertEquals(3, view.skeletonItems!![0].count)
    }

    // 真实布局 inflate：P02 Theme T2 + XML T1。
    @Test @Config(sdk = [26, 34])
    fun inflate_p02_themeT2_xmlT1() {
        val themed = ContextThemeWrapper(context, R.style.ssl_TestTemplateTheme_T2)
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.ssl_test_host_template_priority_p02, null) as StateSkeletonLayout
        assertEquals(SkeletonSource.TEMPLATE, view.skeletonConfig.source)
        assertEquals(t1, view.skeletonConfig.templateLayoutResId)
        assertNull(view.skeletonItems)
    }

    // 真实布局 inflate：P07 Style T1 + 直接 T2。
    @Test @Config(sdk = [26, 34])
    fun inflate_p07_styleT1_directT2() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.ssl_test_host_template_priority_p07, null) as StateSkeletonLayout
        assertEquals(itemA, view.skeletonItems!![0].layoutResId)
        assertEquals(3, view.skeletonItems!![0].count)
        assertNull(view.skeletonConfig.templateLayoutResId)
    }

    // 真实布局 inflate：P08 Style T2 + 直接 T1。
    @Test @Config(sdk = [26, 34])
    fun inflate_p08_styleT2_directT1() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.ssl_test_host_template_priority_p08, null) as StateSkeletonLayout
        assertEquals(t1, view.skeletonConfig.templateLayoutResId)
        assertNull(view.skeletonItems)
    }

    // 真实布局 inflate：P16 直接 @empty 清空 T1 后报缺配，不重新继承。
    @Test @Config(sdk = [26, 34])
    fun inflate_p16_clearedT1_missingThrows() {
        val themed = ContextThemeWrapper(context, R.style.ssl_TestTemplateTheme_T1)
        try {
            LayoutInflater.from(themed)
                .inflate(R.layout.ssl_test_host_template_priority_p16, null)
            fail("清空后无供给应抛缺配")
        } catch (e: Exception) {
            var cur: Throwable? = e
            var found = false
            while (cur != null) {
                if ((cur.message ?: cur.toString()).contains("ssl_skeletonTemplate")) {
                    found = true
                    break
                }
                cur = cur.cause
            }
            assertTrue("异常链应含 ssl_skeletonTemplate，实际=$e", found)
        }
    }

    // 真实布局 inflate：P17 Style @empty 清除父 T1 后采用 T2。
    @Test @Config(sdk = [26, 34])
    fun inflate_p17_styleClearsParentT1() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.ssl_test_host_template_priority_p17, null) as StateSkeletonLayout
        assertEquals(itemA, view.skeletonItems!![0].layoutResId)
        assertEquals(3, view.skeletonItems!![0].count)
    }

    // 真实布局 inflate：P18 Style 清除父 T2 后采用 T1。
    @Test @Config(sdk = [26, 34])
    fun inflate_p18_styleClearsParentT2() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.ssl_test_host_template_priority_p18, null) as StateSkeletonLayout
        assertEquals(t1, view.skeletonConfig.templateLayoutResId)
        assertNull(view.skeletonItems)
    }

    // P20：Style 布局值经 ?attr 提供，按真实 Theme 解析并保留声明来源。
    @Test @Config(sdk = [26, 34])
    fun inflate_p20_attrRef_resolved() {
        val themed = ContextThemeWrapper(context, R.style.ssl_TestAttrTheme)
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.ssl_test_host_template_priority_p20, null) as StateSkeletonLayout
        assertEquals(SkeletonSource.TEMPLATE, view.skeletonConfig.source)
        assertEquals(itemA, view.skeletonItems!![0].layoutResId)
        assertEquals(3, view.skeletonItems!![0].count)
    }

    // P21：Global true + XML false（含自定义 Loading）→ 自定义布局生效。
    @Test fun p21_xmlFalse_overridesGlobalTrue() {
        val merged = merge(
            direct = XmlSslAttrs(useSkeleton = false, customLoadingLayoutResId = R.layout.ssl_test_loading_simple),
            global = SslGlobalDefaults(useSkeleton = true),
        )
        assertEquals(false, merged.useSkeleton)
        assertEquals(R.layout.ssl_test_loading_simple, merged.customLoadingLayoutResId)
    }

    // P22：Global false + XML true（含有效骨架供给）→ 骨架生效。
    @Test fun p22_xmlTrue_overridesGlobalFalse() {
        val merged = merge(
            direct = XmlSslAttrs(useSkeleton = true, skeletonItemLayoutResId = itemA, skeletonItemCount = 2),
            global = SslGlobalDefaults(useSkeleton = false),
        )
        assertEquals(true, merged.useSkeleton)
        assertEquals(SkeletonSource.TEMPLATE, merged.skeletonConfig.source)
        assertEquals(itemA, merged.skeletonItemLayoutResId)
    }

    // P23：只改 Loading 布局或只改骨架供给，不隐式改写 useSkeleton。
    @Test fun p23_supplyAlone_doesNotFlipUseSkeleton() {
        val onlyLoading = merge(direct = XmlSslAttrs(customLoadingLayoutResId = R.layout.ssl_test_loading_simple))
        assertEquals(false, onlyLoading.useSkeleton)
        val onlySupply = merge(direct = XmlSslAttrs(skeletonItemLayoutResId = itemA))
        assertEquals(false, onlySupply.useSkeleton)
        assertEquals(SkeletonSource.TEMPLATE, onlySupply.skeletonConfig.source)
    }

    // P24：XML 清空状态布局后不再继承 Theme；内置开关决定兜底。
    @Test fun p24_clearedStateLayout_respectsBuiltIn() {
        val theme = XmlSslAttrs(emptyLayoutResId = R.layout.ssl_test_state_simple)
        val withBuiltIn = merge(
            direct = XmlSslAttrs(emptyLayoutCleared = true),
            theme = theme,
            global = SslGlobalDefaults(useBuiltInStateLayouts = true),
        )
        assertEquals(R.layout.ssl_builtin_state_empty, withBuiltIn.emptyLayoutResId)
        val strict = merge(
            direct = XmlSslAttrs(emptyLayoutCleared = true),
            theme = theme,
            global = SslGlobalDefaults(useBuiltInStateLayouts = false),
        )
        assertEquals(0, strict.emptyLayoutResId)
    }

    // P25：XML 选择 SOLID / PULSE / NONE，同时继承其他效果参数，不误判互斥。
    @Test fun p25_effectOverride_keepsOtherParams() {
        val merged = merge(
            direct = XmlSslAttrs(effect = SkeletonEffect.SOLID),
            theme = XmlSslAttrs(highlightColor = 0xFF123456.toInt(), cornerRadius = 6f),
        )
        assertEquals(SkeletonEffect.SOLID, merged.skeletonConfig.effect)
        assertEquals(0xFF123456.toInt(), merged.skeletonConfig.highlightColor)
        val none = merge(direct = XmlSslAttrs(transitionEffect = StateTransitionEffect.NONE))
        assertEquals(StateTransitionEffect.NONE, none.transitionEffect)
        assertEquals(180L, none.transitionDurationMillis)
    }

    // P26：pulse 单边覆盖形成 min > max 仍报错；成对合法覆盖通过。
    @Test fun p26_pulseRange_validated() {
        try {
            merge(
                direct = XmlSslAttrs(pulseMinAlpha = 0.8f),
                global = SslGlobalDefaults(pulseMaxAlpha = 0.6f),
            )
            fail("min > max 应报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pulseMinAlpha"))
        }
        val ok = merge(direct = XmlSslAttrs(pulseMinAlpha = 0.8f, pulseMaxAlpha = 1.0f))
        assertEquals(0.8f, ok.skeletonConfig.pulseMinAlpha, 0.001f)
    }

    // P27：空重试数组覆盖 Global 非空列表，对应状态关闭，另一状态不受影响。
    @Test fun p27_emptyRetry_truncates() {
        val merged = merge(
            direct = XmlSslAttrs(emptyRetryViewIds = emptyList()),
            global = SslGlobalDefaults(
                retryConfig = StateRetryConfig(emptyViewIds = listOf(1), errorViewIds = listOf(2)),
            ),
        )
        assertEquals(emptyList<Int>(), merged.emptyRetryViewIds)
        assertEquals(listOf(2), merged.errorRetryViewIds)
    }
}
