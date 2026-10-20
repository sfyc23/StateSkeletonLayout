package com.sfyc.demo.retry

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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSystemClock

/**
 * 多重试入口示例的行为锁：全局安装一次、页面注册一次回调后，所有默认热区可用；
 * 实例隔离、显式关闭与恢复可预测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MultiRetryDemoFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: MultiRetryDemoFragment
    private val activity get() = controller.get()
    private fun stateA() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state_a)
    private fun stateB() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state_b)
    private val retryCount get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_retry_count).text.toString()

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = MultiRetryDemoFragment()
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

    @Test fun `空态图片与文字触发同一回调且计数隔离`() {
        // 初始已 showEmpty；默认节流 500ms，计数前推进时钟避免交替点击被节流。
        stateA().retryClickThrottleMillis = 0
        stateB().retryClickThrottleMillis = 0
        assertEquals(StateLayoutState.EMPTY, stateA().renderedState)
        stateA().getStateView(StateLayoutState.EMPTY)!!
            .findViewById<View>(R.id.demo_empty_image).performClick()
        frame()
        stateA().getStateView(StateLayoutState.EMPTY)!!
            .findViewById<View>(R.id.demo_empty_text).performClick()
        frame()
        assertEquals(activity.getString(R.string.demo_multi_retry_count, 2, 0), retryCount)

        // B 独立计数。
        stateB().getStateView(StateLayoutState.EMPTY)!!
            .findViewById<View>(R.id.demo_empty_image).performClick()
        frame()
        assertEquals(activity.getString(R.string.demo_multi_retry_count, 2, 1), retryCount)
    }

    @Test fun `错误态图片与按钮触发同一回调且B的ERROR已关闭`() {
        stateA().retryClickThrottleMillis = 0
        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, stateA().renderedState)
        stateA().getStateView(StateLayoutState.ERROR)!!
            .findViewById<View>(R.id.demo_error_image).performClick()
        frame()
        stateA().getStateView(StateLayoutState.ERROR)!!
            .findViewById<View>(R.id.demo_error_retry).performClick()
        frame()
        assertTrue(retryCount.contains("A=2"))

        // B 的 ERROR 显式关闭：无入口，点击不计数（此处仅验证列表为空）。
        assertTrue(stateB().getRetryViewIds(StateLayoutState.ERROR).isEmpty())
    }

    @Test fun `禁用开关停止派发且恢复后可用`() {
        stateA().retryClickThrottleMillis = 0
        click(R.id.demo_btn_toggle)
        stateA().getStateView(StateLayoutState.EMPTY)!!
            .findViewById<View>(R.id.demo_empty_image).performClick()
        frame()
        assertEquals(activity.getString(R.string.demo_multi_retry_count, 0, 0), retryCount)

        click(R.id.demo_btn_toggle)
        stateA().getStateView(StateLayoutState.EMPTY)!!
            .findViewById<View>(R.id.demo_empty_image).performClick()
        frame()
        assertEquals(activity.getString(R.string.demo_multi_retry_count, 1, 0), retryCount)
    }
}
