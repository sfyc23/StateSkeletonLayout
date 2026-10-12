package com.sfyc.demo.config

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.sfyc.demo.MainActivity
import com.sfyc.demo.ssl.R
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 配置变更示例的行为锁：四态切换、配置摘要随状态与 Configuration 更新。
 *
 * 昼夜 / RTL / locale 三维用 `@Config(qualifiers = …)` 覆盖逐一验证；
 * fontScale 没有可用的 Robolectric qualifier（`fontScale=1.5` 无法解析），
 * 改为改写 Activity 资源上的 Configuration。
 *
 * 注意：Robolectric 默认 locale 是 en_US，示例同时提供 values-en，
 * 因此断言一律对比 `getString(...)` 的结果而不是中文字面量。
 * 另外这里刻意不暂停 ShadowChoreographer——暂停后 `ActivityController.recreate()`
 * 会在 windowFocusChanged 上抛 NPE。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigChangeDemoFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    /**
     * 始终从 FragmentManager 取当前实例：`recreate()` 会重建 Activity 与其中的 Fragment，
     * 缓存旧引用会让断言落在已 detach 的对象上。
     */
    private val fragment: ConfigChangeDemoFragment
        get() = activity.supportFragmentManager
            .findFragmentById(R.id.demo_detail_container) as ConfigChangeDemoFragment

    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val summary get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_config_summary).text.toString()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity.findViewById<View>(R.id.demo_detail_container).visibility = View.VISIBLE
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.demo_detail_container, ConfigChangeDemoFragment()).commitNow()
        settle()
    }

    @After fun teardown() {
        controller.pause().stop().destroy()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun frame() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 2400)
        decor.viewTreeObserver.dispatchOnPreDraw()
        scheduler.runCurrent()
    }

    private fun settle() = repeat(20) { frame() }

    private fun click(id: Int) {
        fragment.requireView().findViewById<View>(id).performClick()
        frame()
    }

    @Test fun `Fragment 创建成功并进入 Loading`() {
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertEquals(StateLayoutState.LOADING, state.requestedState)
    }

    @Test fun `四态切换后 renderedState 与摘要同步更新`() {
        click(R.id.demo_btn_empty)
        assertEquals(StateLayoutState.EMPTY, state.renderedState)
        assertTrue(summary.contains("renderedState   = EMPTY"))

        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, state.renderedState)
        assertTrue(summary.contains("renderedState   = ERROR"))

        click(R.id.demo_btn_content)
        assertEquals(StateLayoutState.CONTENT, state.renderedState)
        assertTrue(summary.contains("renderedState   = CONTENT"))

        click(R.id.demo_btn_loading)
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertTrue(summary.contains("renderedState   = LOADING"))
        assertTrue(state.useSkeleton)
    }

    @Test fun `摘要展示 configuration 四维度与骨架当前取值`() {
        listOf("uiMode", "fontScale", "layoutDirection", "locale", "maskColor", "cornerRadius")
            .forEach { field ->
                assertTrue("摘要缺少 $field：$summary", summary.contains(field))
            }
        assertEquals(1f, activity.resources.configuration.fontScale, 0f)
        assertTrue(summary.contains("fontScale       = 1.0"))
    }

    @Test
    @Config(qualifiers = "night")
    fun `夜间配置下摘要显示夜间且状态页可切换`() {
        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        assertTrue(summary.contains(activity.getString(R.string.demo_config_night)))
        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, state.renderedState)
    }

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl")
    fun `RTL 配置下摘要显示从右到左`() {
        // 注意：单独写 ldrtl 在 Robolectric 里不会改变 layoutDirection，
        // 必须配合一个 RTL locale 才会真正解析为 RTL。
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        assertTrue(summary.contains(activity.getString(R.string.demo_config_rtl)))
        click(R.id.demo_btn_empty)
        assertEquals(StateLayoutState.EMPTY, state.renderedState)
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `locale 变更后摘要跟随新 locale`() {
        val locale = activity.resources.configuration.locales[0]
        assertEquals("fr", locale.language)
        assertTrue("摘要未跟随 locale：$summary", summary.contains(locale.toString()))
    }

    /** fontScale 无可用 qualifier，直接走 Activity 的配置变更路径。 */
    @Test fun `字体缩放变更后摘要跟随 fontScale`() {
        val updated = Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        controller.configurationChange(updated)
        settle()
        assertEquals(1.5f, activity.resources.configuration.fontScale, 0f)
        // Activity 的配置变更会重建 Fragment，摘要必须用新 Configuration 重新渲染。
        assertTrue("摘要未跟随 fontScale：$summary", summary.contains("fontScale       = 1.5"))
        click(R.id.demo_btn_empty)
        assertEquals(StateLayoutState.EMPTY, state.renderedState)
    }

    @Test fun `重建 Activity 后 Fragment 重新创建并恢复请求状态`() {
        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, state.renderedState)
        controller.recreate()
        settle()
        frame()
        assertEquals(StateLayoutState.ERROR, state.renderedState)
        assertEquals(StateLayoutState.ERROR, state.requestedState)
        // 重建后的新实例仍然能继续切态。
        click(R.id.demo_btn_content)
        assertEquals(StateLayoutState.CONTENT, state.renderedState)
    }

    @Test fun `说明文字讲清库已实现 onConfigurationChanged`() {
        val note = fragment.requireView()
            .findViewById<TextView>(R.id.demo_config_note).text.toString()
        // 说明文字走 @string，不硬编码在布局里；断言对比本地化资源而不是中文字面量，
        // 因为示例现在同时提供 values-en，测试环境下默认解析到英文。
        assertEquals(activity.getString(R.string.demo_config_note), note)
        assertTrue("说明文字缺少 onConfigurationChanged：$note", note.contains("onConfigurationChanged"))
        assertTrue("说明文字过短，未讲清处理范围：$note", note.length > 200)
    }
}
