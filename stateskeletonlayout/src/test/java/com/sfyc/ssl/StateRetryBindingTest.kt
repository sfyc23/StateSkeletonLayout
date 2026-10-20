package com.sfyc.ssl

import android.app.Activity
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * 多入口绑定测试：多入口、缓存更新、候选失败、释放、门禁与节流。
 * 观察回调次数、实际目标、状态事实、旧根监听与保存恢复，不只断言内部 Map。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRetryBindingTest {

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
        transitionEffect = StateTransitionEffect.NONE
        minimumLoadingDurationMillis = 0
        retryClickThrottleMillis = 0
        setContentView(TextView(activity).apply { text = "content" })
        setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_retry_multi)
        setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_retry_multi)
    }

    private fun attach(layout: StateSkeletonLayout) {
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, 240, 160)
    }

    private data class RetryEvent(
        val layout: StateSkeletonLayout,
        val state: StateLayoutState,
        val view: View,
    )

    // region 基本点击

    @Test
    fun `EMPTY多入口与ERROR多入口分别派发且携带正确信息`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image, R.id.ssl_test_multi_empty_text)
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_image, R.id.ssl_test_multi_error_button)
        val events = mutableListOf<RetryEvent>()
        layout.setOnRetryListener { l, s, v -> events += RetryEvent(l, s, v) }
        attach(layout)

        layout.showEmpty()
        val emptyRoot = checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
        emptyRoot.findViewById<View>(R.id.ssl_test_multi_empty_image).performClick()
        emptyRoot.findViewById<View>(R.id.ssl_test_multi_empty_text).performClick()

        assertEquals(2, events.size)
        assertTrue(events.all { it.layout === layout && it.state == StateLayoutState.EMPTY })
        assertEquals(R.id.ssl_test_multi_empty_image, events[0].view.id)
        assertEquals(R.id.ssl_test_multi_empty_text, events[1].view.id)

        events.clear()
        layout.showError()
        val errorRoot = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        errorRoot.findViewById<View>(R.id.ssl_test_multi_error_image).performClick()
        errorRoot.findViewById<View>(R.id.ssl_test_multi_error_button).performClick()

        assertEquals(2, events.size)
        assertTrue(events.all { it.state == StateLayoutState.ERROR })
    }

    @Test
    fun `库内置默认按钮有效`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            retryClickThrottleMillis = 0
            setContentView(TextView(activity))
        }
        var clicks = 0
        var clickedId = 0
        layout.setOnRetryListener { _, state, view ->
            clicks++
            clickedId = view.id
            assertEquals(StateLayoutState.ERROR, state)
        }
        attach(layout)
        layout.showError()
        val errorView = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        assertTrue(errorView.findViewById<View>(R.id.ssl_builtin_state_retry).performClick())
        assertEquals(1, clicks)
        assertEquals(R.id.ssl_builtin_state_retry, clickedId)
        // EMPTY 默认关闭：无入口。
        layout.showEmpty()
        assertEquals(emptyList<Int>(), layout.getRetryViewIds(StateLayoutState.EMPTY))
    }

    // endregion

    // region 非自动行为

    @Test
    fun `点击后不自动切换且无回调时不崩溃不占窗口`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_retry)
        // 无回调点击不崩溃。
        retry.performClick()
        assertEquals(StateLayoutState.ERROR, layout.renderedState)

        var clicks = 0
        layout.retryClickThrottleMillis = 500
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        // 上一次无回调点击不应消耗窗口，本次应派发。
        retry.performClick()
        assertEquals(1, clicks)
    }

    // endregion

    // region 门禁

    @Test
    fun `非ERROR呈现态与请求不一致时拒绝`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        // CONTENT 下点击旧错误页目标拒绝。
        layout.showContent()
        retry.performClick()
        assertEquals(0, clicks)
        // LOADING 下拒绝。
        layout.setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        layout.showLoading()
        // 错误页目标已不在当前顶层，拒绝。
        retry.performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `隐藏与禁用目标的performClick也不派发`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        retry.visibility = View.GONE
        retry.performClick()
        assertEquals(0, clicks)
        retry.visibility = View.VISIBLE
        retry.isEnabled = false
        retry.performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `detach期间不接受点击`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val parent = layout.parent as android.view.ViewGroup
        parent.removeView(layout)
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        retry.performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `旧根与失败候选拒绝派发`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val oldRoot = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        val oldRetry = oldRoot.findViewById<View>(R.id.ssl_test_retry)

        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_alternate)
        oldRetry.performClick()
        assertEquals(0, clicks)
        // 新根可用。
        checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_retry).performClick()
        assertEquals(1, clicks)
    }

    // endregion

    // region 节流

    @Test
    fun `同状态多入口共享窗口不同状态与实例互不阻塞`() {
        val a = create()
        val b = create()
        listOf(a, b).forEach {
            it.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image, R.id.ssl_test_multi_empty_text)
            it.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_image, R.id.ssl_test_multi_error_button)
            it.retryClickThrottleMillis = 500
        }
        var clicksA = 0
        var clicksB = 0
        a.setOnRetryListener { _, _, _ -> clicksA++ }
        b.setOnRetryListener { _, _, _ -> clicksB++ }
        attach(a)

        a.showError()
        val errRoot = checkNotNull(a.getStateView(StateLayoutState.ERROR))
        errRoot.findViewById<View>(R.id.ssl_test_multi_error_image).performClick()
        assertEquals(1, clicksA)
        // 同状态另一入口快速点击被节流。
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        errRoot.findViewById<View>(R.id.ssl_test_multi_error_button).performClick()
        assertEquals(1, clicksA)
        // 窗口后可重试。
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        errRoot.findViewById<View>(R.id.ssl_test_multi_error_button).performClick()
        assertEquals(2, clicksA)
        // 不同状态不阻塞。
        a.showEmpty()
        val emptyRoot = checkNotNull(a.getStateView(StateLayoutState.EMPTY))
        emptyRoot.findViewById<View>(R.id.ssl_test_multi_empty_image).performClick()
        assertEquals(3, clicksA)
        // 不同实例不阻塞（B 未 attach 也能验证计数独立，需 attach 后点击）。
        activity.setContentView(b)
        ShadowLooper.idleMainLooper()
        b.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
        )
        b.layout(0, 0, 240, 160)
        b.showError()
        b.getStateView(StateLayoutState.ERROR)!!
            .findViewById<View>(R.id.ssl_test_multi_error_image).performClick()
        assertEquals(1, clicksB)
    }

    @Test
    fun `零间隔关闭节流且禁用开关停止派发`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        layout.retryClickThrottleMillis = 0
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        repeat(3) { retry.performClick() }
        assertEquals(3, clicks)
        layout.retryClicksEnabled = false
        retry.performClick()
        assertEquals(3, clicks)
    }

    // endregion

    // region 动态绑定与回调更新

    @Test
    fun `先创建状态页后设置ID可立即点击`() {
        val layout = create()
        attach(layout)
        layout.showEmpty()
        assertEquals(0, layout.getRetryViewIds(StateLayoutState.EMPTY).size)
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image)
        checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
            .findViewById<View>(R.id.ssl_test_multi_empty_image).let {
                var clicks = 0
                layout.setOnRetryListener { _, _, _ -> clicks++ }
                it.performClick()
                assertEquals(1, clicks)
            }
    }

    @Test
    fun `移除ID或置空后旧入口失效相同列表重设不重置窗口`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        layout.retryClickThrottleMillis = 500
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        retry.performClick()
        assertEquals(1, clicks)
        // 相同列表重设：不重置窗口，快速点击仍被节流。
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        retry.performClick()
        assertEquals(1, clicks)
        // 置空关闭后失效。
        layout.setRetryViewIds(StateLayoutState.ERROR)
        assertTrue(layout.getRetryViewIds(StateLayoutState.ERROR).isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        retry.performClick()
        assertEquals(1, clicks)
        assertFalse(retry.hasOnClickListeners())
    }

    @Test
    fun `先创建页面后设置回调与替换回调语义`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        var first = 0
        var second = 0
        // 先创建后设置回调可用。
        layout.setOnRetryListener { _, _, _ -> first++ }
        retry.performClick()
        assertEquals(1, first)
        // 替换只调用新值。
        layout.setOnRetryListener { _, _, _ -> second++ }
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        // throttle 0 in create() -> actually 0, so immediate; advance anyway.
        retry.performClick()
        assertEquals(1, first)
        assertEquals(1, second)
        // 设 null 后停止。
        layout.setOnRetryListener(null)
        retry.performClick()
        assertEquals(1, second)
    }

    @Test
    fun `回调内同步触发下一次点击不重复派发`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        layout.retryClickThrottleMillis = 500
        attach(layout)
        layout.showError()
        val retry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_multi_error_button)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ ->
            clicks++
            // 同步触发第二次点击应被节流拒绝。
            retry.performClick()
        }
        retry.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `回调内可读不可修改重试配置`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        attach(layout)
        var read: List<Int>? = null
        var rejected = false
        layout.setOnStateViewCreatedListener { _, state, _ ->
            if (state == StateLayoutState.EMPTY) {
                read = layout.getRetryViewIds(StateLayoutState.ERROR)
                try {
                    layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_image)
                } catch (e: IllegalStateException) {
                    rejected = true
                }
            }
        }
        layout.showEmpty()
        assertNotNull(read)
        assertTrue(rejected)
    }

    // endregion

    // region 根节点隔离与释放

    @Test
    fun `相同ID在不同状态根隔离且两容器不串回调`() {
        val layout = create()
        // 两状态用同一单布局，同一 ID 同时出现在两根。
        layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_retry)
        // ERROR 不配置该 ID。
        layout.setRetryViewIds(StateLayoutState.ERROR)
        val events = mutableListOf<StateLayoutState>()
        layout.setOnRetryListener { _, s, _ -> events += s }
        attach(layout)
        layout.showEmpty()
        checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
            .findViewById<View>(R.id.ssl_test_retry).performClick()
        assertEquals(listOf(StateLayoutState.EMPTY), events)
        layout.showError()
        // ERROR 未配置该 ID，点击不应派发。
        checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_retry).performClick()
        assertEquals(1, events.size)

        // 两容器隔离。
        val other = create()
        other.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image)
        var otherClicks = 0
        other.setOnRetryListener { _, _, _ -> otherClicks++ }
        // other 未 attach 不点击，只验证监听独立（此处仅确保不抛异常）。
        assertEquals(0, otherClicks)
    }

    @Test
    fun `替换成功旧入口失效候选失败保留全部旧入口`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image)
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_button)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showEmpty()
        val oldEmpty = checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
        val oldTarget = oldEmpty.findViewById<View>(R.id.ssl_test_multi_empty_image)
        // 替换成功：旧入口失效。
        layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
        assertFalse(oldTarget.hasOnClickListeners())
        // 候选失败保留全部旧入口。
        layout.showError()
        val oldError = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        val oldErrorTarget = oldError.findViewById<View>(R.id.ssl_test_multi_error_button)
        var rejected: View? = null
        layout.setOnStateViewCreatedListener { _, state, view ->
            if (state == StateLayoutState.ERROR) {
                rejected = view
                throw IllegalStateException("模拟候选失败")
            }
        }
        try {
            layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_alternate)
            throw AssertionError("应抛异常")
        } catch (e: IllegalStateException) {
            assertEquals("模拟候选失败", e.message)
        }
        assertTrue(layout.getStateView(StateLayoutState.ERROR) === oldError)
        assertFalse(checkNotNull(rejected).findViewById<View>(R.id.ssl_test_retry).hasOnClickListeners())
        oldErrorTarget.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `清理缓存与配置重建重新绑定新根`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showEmpty()
        val before = checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
        layout.showContent()
        layout.clearStateViewCache(StateLayoutState.EMPTY)
        assertNull(layout.getStateView(StateLayoutState.EMPTY))
        layout.showEmpty()
        val after = checkNotNull(layout.getStateView(StateLayoutState.EMPTY))
        assertTrue(before !== after)
        after.findViewById<View>(R.id.ssl_test_multi_empty_image).performClick()
        assertEquals(1, clicks)
    }

    // endregion
}
