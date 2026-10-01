package com.sfyc.ssl

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SslAttributePriorityTest {
    private val application = RuntimeEnvironment.getApplication()
    private val themed get() = ContextThemeWrapper(application, R.style.ssl_TestTheme)

    @Before fun before() { StateSkeletonLayoutDefaults.reset() }
    @After fun after() { StateSkeletonLayoutDefaults.reset() }

    @Test fun `Global胜出Theme而Theme仍填补未配置字段`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(effect = SkeletonEffect.PULSE))
        val view = StateSkeletonLayout(themed)
        assertEquals(SkeletonEffect.PULSE, view.skeletonConfig.effect)
        assertEquals(0xFFB39DDB.toInt(), view.skeletonConfig.maskColor)
        assertEquals(450L, view.minimumLoadingDurationMillis)
    }

    @Test fun `XML直接属性胜出显式style且零值和false也胜出Global`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(useSkeleton = true,
            maskColor = 0xFF111111.toInt(), effect = SkeletonEffect.PULSE, minimumLoadingDurationMillis = 900L))
        val view = LayoutInflater.from(themed).inflate(R.layout.ssl_test_host_priority, null) as StateSkeletonLayout
        assertEquals(0xFF333333.toInt(), view.skeletonConfig.maskColor)
        assertFalse(view.useSkeleton)
        assertEquals(0L, view.minimumLoadingDurationMillis)
        assertEquals(SkeletonEffect.SHIMMER, view.skeletonConfig.effect)
    }

    @Test @Config(sdk = [26, 34]) fun `显式style含父style胜出Global且主题引用按真实Theme解析`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(maskColor = 0xFF111111.toInt(),
            effect = SkeletonEffect.PULSE, highlightColor = 0xFFFFFFFF.toInt(), cornerRadius = 6f))
        val view = LayoutInflater.from(themed).inflate(R.layout.ssl_test_host_style, null) as StateSkeletonLayout
        assertEquals(0xFF222222.toInt(), view.skeletonConfig.maskColor)
        assertEquals(SkeletonEffect.SHIMMER, view.skeletonConfig.effect)
        assertEquals(0xFF123456.toInt(), view.skeletonConfig.highlightColor)
        assertEquals(6f, view.skeletonConfig.cornerRadius, 0f)
        assertEquals(800L, view.skeletonConfig.animationDurationMillis)
    }

    @Test @Config(sdk = [26, 34]) fun `style本身通过主题引用仍属于覆盖Global的页面配置`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(maskColor = 0xFF111111.toInt(),
            effect = SkeletonEffect.PULSE, highlightColor = 0xFFFFFFFF.toInt(), cornerRadius = 6f))
        val view = LayoutInflater.from(themed).inflate(R.layout.ssl_test_host_theme_style, null) as StateSkeletonLayout
        assertEquals(0xFF222222.toInt(), view.skeletonConfig.maskColor)
        assertEquals(SkeletonEffect.SHIMMER, view.skeletonConfig.effect)
        assertEquals(0xFF123456.toInt(), view.skeletonConfig.highlightColor)
        assertEquals(6f, view.skeletonConfig.cornerRadius, 0f)
        assertEquals(800L, view.skeletonConfig.animationDurationMillis)
    }

    @Test fun `未定义页面style主题属性时由Global和默认值补全`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(maskColor = 0xFF111111.toInt(),
            effect = SkeletonEffect.PULSE))
        val noPageStyle = ContextThemeWrapper(application, android.R.style.Theme_Material_Light_NoActionBar)
        val view = LayoutInflater.from(noPageStyle).inflate(R.layout.ssl_test_host_theme_style, null) as StateSkeletonLayout
        assertEquals(0xFF111111.toInt(), view.skeletonConfig.maskColor)
        assertEquals(SkeletonEffect.PULSE, view.skeletonConfig.effect)
        assertEquals(1200L, view.skeletonConfig.animationDurationMillis)
    }

    @Test fun `运行时setter胜出构造时各层且install不改变已有实例`() {
        val view = StateSkeletonLayout(themed)
        view.skeletonConfig = view.skeletonConfig.copy(effect = SkeletonEffect.PULSE)
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(effect = SkeletonEffect.SHIMMER))
        assertEquals(SkeletonEffect.PULSE, view.skeletonConfig.effect)
        assertEquals(SkeletonEffect.SHIMMER, StateSkeletonLayout(themed).skeletonConfig.effect)
    }

    @Test fun `资源工厂读取夜间颜色和dp圆角并提供英文状态描述`() {
        val configuration = Configuration(application.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            setLocale(Locale.ENGLISH)
        }
        val night = application.createConfigurationContext(configuration)
        val config = SkeletonConfig.from(night)
        assertEquals(0xFF2E3339.toInt(), config.maskColor)
        assertEquals(night.resources.getDimension(R.dimen.ssl_corner_radius_default), config.cornerRadius, 0f)
        assertEquals("Loading", night.getString(R.string.ssl_desc_loading))
        assertEquals(config.maskColor, StateSkeletonLayout(night).skeletonConfig.maskColor)
    }
}
