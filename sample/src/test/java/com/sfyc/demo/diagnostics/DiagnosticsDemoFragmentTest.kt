package com.sfyc.demo.diagnostics

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.sfyc.demo.MainActivity
import com.sfyc.demo.ssl.R
import com.sfyc.ssl.SkeletonFallbackReason
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
import org.junit.Assert.assertFalse
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

/** 骨架诊断示例的行为锁：四态呈现、快照字段、像素预算降级与缓存清理。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticsDemoFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: DiagnosticsDemoFragment
    private val activity get() = controller.get()
    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val snapshotText get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_diagnostics).text.toString()
    private val logText get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_log).text.toString()

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = DiagnosticsDemoFragment()
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

    private fun assertState(expected: StateLayoutState) {
        assertEquals(expected, state.renderedState)
        assertEquals(expected, state.requestedState)
    }

    @Test fun `进入页面即呈现 Loading 骨架`() {
        assertState(StateLayoutState.LOADING)
        assertTrue(state.diagnostics().effectiveUseSkeleton)
    }

    @Test fun `四个按钮切四态后 renderedState 与文案同步更新`() {
        click(R.id.demo_btn_empty)
        assertState(StateLayoutState.EMPTY)
        assertTrue(snapshotText.contains("renderedState         = EMPTY"))
        assertTrue(snapshotText.contains("requestedState        = EMPTY"))

        click(R.id.demo_btn_error)
        assertState(StateLayoutState.ERROR)
        assertTrue(snapshotText.contains("renderedState         = ERROR"))

        click(R.id.demo_btn_content)
        assertState(StateLayoutState.CONTENT)
        assertTrue(snapshotText.contains("renderedState         = CONTENT"))

        click(R.id.demo_btn_loading)
        assertState(StateLayoutState.LOADING)
        assertTrue(state.diagnostics().effectiveUseSkeleton)
    }

    @Test fun `快照展开 diagnostics 的全部字段`() {
        click(R.id.demo_btn_refresh)
        listOf(
            "requestedState", "renderedState", "effectiveUseSkeleton", "maskWidth", "maskHeight",
            "maskBytes", "maskBuildCount", "fallbackReason", "effectRunning", "hasPendingWork",
            "inStateViewTransaction",
        ).forEach { field ->
            assertTrue("快照缺少字段 $field：$snapshotText", snapshotText.contains(field))
        }
    }

    @Test fun `缩小像素预算触发 PIXEL_BUDGET_EXCEEDED 降级并可恢复`() {
        settle()
        assertTrue("遮罩未构建：$snapshotText", state.diagnostics().maskBytes > 0)
        assertEquals(SkeletonFallbackReason.NONE, state.diagnostics().fallbackReason)

        click(R.id.demo_btn_budget_tiny)
        val degraded = state.diagnostics()
        assertEquals(SkeletonFallbackReason.PIXEL_BUDGET_EXCEEDED, degraded.fallbackReason)
        assertFalse(degraded.effectRunning)
        // 降级只影响骨架，业务状态仍停留在 Loading。
        assertEquals(StateLayoutState.LOADING, degraded.renderedState)
        assertTrue(snapshotText.contains("PIXEL_BUDGET_EXCEEDED"))

        click(R.id.demo_btn_budget_unlimited)
        settle()
        assertEquals(SkeletonFallbackReason.NONE, state.diagnostics().fallbackReason)
        assertTrue(state.diagnostics().maskBytes > 0)
    }

    @Test fun `invalidateSkeleton 重建遮罩`() {
        settle()
        val before = state.diagnostics().maskBuildCount
        click(R.id.demo_btn_invalidate)
        settle()
        assertTrue("遮罩未重建：$before", state.diagnostics().maskBuildCount >= before)
        assertTrue(logText.contains("invalidateSkeleton()"))
    }

    @Test fun `非当前态缓存可清理 当前态被拒绝且不影响画面`() {
        click(R.id.demo_btn_empty)
        click(R.id.demo_btn_error)
        click(R.id.demo_btn_empty)
        assertState(StateLayoutState.EMPTY)
        // ERROR 已不是当前态，可以清理。
        click(R.id.demo_btn_clear_error)
        assertTrue(logText.contains("clearStateViewCache(ERROR)"))
        assertState(StateLayoutState.EMPTY)

        // 当前可见态不允许清除，控件用异常明确拒绝而不是静默跳过。
        click(R.id.demo_btn_clear_empty)
        assertTrue("未看到拒绝日志：$logText", logText.contains("clearStateViewCache(EMPTY) 被拒绝"))
        assertState(StateLayoutState.EMPTY)
    }
}
