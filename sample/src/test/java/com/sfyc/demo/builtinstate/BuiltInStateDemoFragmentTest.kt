package com.sfyc.demo.builtinstate

import android.os.Looper
import android.view.View
import android.widget.Button
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
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowChoreographer
import com.sfyc.ssl.R as SslR

/**
 * 内置状态页示例的行为锁：XML 未配置 empty / error 布局时仍能显示库内置兜底页面，
 * 且内置重试按钮被自动接成统一重试入口。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BuiltInStateDemoFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: BuiltInStateDemoFragment
    private val activity get() = controller.get()
    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val retryCount get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_retry_count).text.toString()
    private val logText get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_log).text.toString()

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = BuiltInStateDemoFragment()
        activity.findViewById<View>(R.id.demo_detail_container).visibility = View.VISIBLE
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.demo_detail_container, fragment).commitNow()
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

    @Test fun `进入页面即呈现 Loading 骨架`() {
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertTrue(state.useSkeleton)
    }

    @Test fun `未配置 empty 与 error 布局时四态仍能切换`() {
        click(R.id.demo_btn_empty)
        assertEquals(StateLayoutState.EMPTY, state.renderedState)

        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, state.renderedState)

        click(R.id.demo_btn_content)
        assertEquals(StateLayoutState.CONTENT, state.renderedState)

        click(R.id.demo_btn_loading)
        assertEquals(StateLayoutState.LOADING, state.renderedState)
    }

    @Test fun `EMPTY 使用库内置布局且文案来自内置资源`() {
        click(R.id.demo_btn_empty)
        val root = state.getStateView(StateLayoutState.EMPTY)
        assertNotNull("EMPTY 未创建状态页", root)
        val message = root!!.findViewById<TextView>(SslR.id.ssl_builtin_state_message)
        assertNotNull("内置空态缺少 message 节点", message)
        assertEquals(activity.getString(SslR.string.ssl_builtin_empty_message), message.text.toString())
        // 内置空态没有重试按钮。
        assertEquals(null, root.findViewById<View>(SslR.id.ssl_builtin_state_retry))
    }

    @Test fun `ERROR 使用库内置布局且自动接管重试入口`() {
        click(R.id.demo_btn_error)
        val root = state.getStateView(StateLayoutState.ERROR)
        assertNotNull("ERROR 未创建状态页", root)
        val retry = root!!.findViewById<Button>(SslR.id.ssl_builtin_state_retry)
        assertNotNull("内置错误页缺少 retry 节点", retry)
        assertEquals(activity.getString(SslR.string.ssl_builtin_retry), retry.text.toString())

        retry.performClick()
        frame()
        assertEquals(activity.getString(R.string.demo_builtin_retry_count, 1), retryCount)
        assertTrue("未记录重试事件：$logText", logText.contains("收到内置重试按钮事件"))
    }

    @Test fun `替换内置错误页文案后 findById 仍可再次定位`() {
        click(R.id.demo_btn_replace_message)
        val root = state.getStateView(StateLayoutState.ERROR)!!
        val message = root.findViewById<TextView>(SslR.id.ssl_builtin_state_message)
        assertEquals(activity.getString(R.string.demo_builtin_replaced_message), message.text.toString())
        // 日志里记录的是解析出的资源 ID 数值与两个节点是否命中。
        assertTrue("未记录替换日志：$logText", logText.contains("getStateView(ERROR).findViewById"))
        assertTrue("未命中 root 节点：$logText", logText.contains("root=${SslR.id.ssl_builtin_state_root}"))
        assertTrue("未命中 message / retry 节点：$logText", logText.contains("message=true retry=true"))

        // 再点一次切回内置原文案，说明替换是可逆的、节点引用一直有效。
        click(R.id.demo_btn_replace_message)
        assertEquals(activity.getString(R.string.demo_builtin_origin_message), message.text.toString())
        assertEquals(activity.getString(R.string.demo_builtin_origin_message),
            activity.getString(SslR.string.ssl_builtin_error_message))
    }

    @Test fun `说明文字讲清内置 ID 契约`() {
        val note =
            fragment.requireView().findViewById<TextView>(R.id.demo_builtin_note).text.toString()
        assertEquals(activity.getString(R.string.demo_builtin_note), note)
        listOf(
            SslR.id.ssl_builtin_state_root,
            SslR.id.ssl_builtin_state_message,
            SslR.id.ssl_builtin_state_retry,
        ).forEach { id ->
            val name = activity.resources.getResourceEntryName(id)
            assertTrue("说明文字缺少 $name：$note", note.contains(name))
        }
        assertTrue(note.contains("getStateView(ERROR)"))
    }
}
