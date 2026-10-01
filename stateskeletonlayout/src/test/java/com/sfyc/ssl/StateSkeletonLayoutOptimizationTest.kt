package com.sfyc.ssl

import android.app.Activity
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateSkeletonLayoutOptimizationTest {
    private lateinit var activity: Activity
    @Before fun before() {
        StateSkeletonLayoutDefaults.reset()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }
    @After fun after() { StateSkeletonLayoutDefaults.reset(); activity.finish() }

    private fun create(): StateSkeletonLayout = StateSkeletonLayout(activity).apply {
        transitionEffect = StateTransitionEffect.NONE
        minimumLoadingDurationMillis = 0
        setContentView(TextView(activity).apply { text = "content" })
        setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
    }

    private fun attach(layout: StateSkeletonLayout) {
        activity.setContentView(layout)
        layout.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 240, 160)
        layout.viewTreeObserver.dispatchOnPreDraw()
    }

    @Test fun `加载中从自定义布局切到骨架立即获得窗口状态`() {
        val layout = create()
        layout.setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        attach(layout)
        layout.showLoading()
        assertFalse(layout.diagnostics().effectiveUseSkeleton)
        layout.useSkeleton = true
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        assertTrue(layout.getChildAt(2).isShown)
        assertTrue(layout.diagnostics().maskBytes > 0)
        assertTrue(layout.diagnostics().effectRunning)
    }

    @Test fun `聚合隐藏时切换加载表现不运行动画且重见恢复`() {
        val layout = create()
        layout.setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        attach(layout)
        layout.showLoading()
        layout.onVisibilityAggregated(false)
        layout.useSkeleton = true
        assertFalse(layout.diagnostics().effectRunning)
        layout.onVisibilityAggregated(true)
        assertTrue(layout.diagnostics().effectRunning)
        assertTrue(layout.getChildAt(2).isShown)
    }

    @Test fun `无效模板配置替换保留原配置与当前画面`() {
        val layout = create()
        attach(layout)
        layout.showLoading()
        val old = layout.skeletonConfig
        val builds = layout.diagnostics().maskBuildCount
        assertThrows(IllegalArgumentException::class.java) {
            layout.skeletonConfig = old.copy(source = SkeletonSource.TEMPLATE,
                templateLayoutResId = R.layout.ssl_test_template_empty)
        }
        assertSame(old, layout.skeletonConfig)
        assertTrue(layout.getChildAt(2).isShown)
        assertEquals(builds, layout.diagnostics().maskBuildCount)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
    }

    @Test fun `正在呈现的状态布局绑定失败时保留旧布局并可重试`() {
        val layout = create()
        attach(layout)
        layout.showError()
        val old = layout.getStateView(StateLayoutState.ERROR)
        layout.setOnStateViewCreatedListener { _, _, _ -> error("候选绑定失败") }
        assertThrows(IllegalStateException::class.java) {
            layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_loading_simple)
        }
        assertSame(old, layout.getStateView(StateLayoutState.ERROR))
        assertTrue(old!!.isShown)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        layout.setOnStateViewCreatedListener(null)
        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_loading_simple)
        assertNotSame(old, layout.getStateView(StateLayoutState.ERROR))
    }

    @Test fun `创建回调重新提交状态被同步拒绝而不遗留半初始化缓存`() {
        val layout = create()
        attach(layout)
        layout.setOnStateViewCreatedListener { _, _, _ -> layout.showContent() }
        assertThrows(IllegalStateException::class.java) { layout.showEmpty() }
        assertEquals(StateLayoutState.CONTENT, layout.requestedState)
        assertNull(layout.getStateView(StateLayoutState.EMPTY))
        layout.setOnStateViewCreatedListener(null)
        layout.showEmpty()
        assertEquals(StateLayoutState.EMPTY, layout.renderedState)
    }

    @Test fun `隐藏期间修改业务属性返回内容时仍保留新值`() {
        val layout = create()
        attach(layout)
        val content = layout.getStateView(StateLayoutState.CONTENT)!!
        content.isEnabled = false
        layout.showLoading()
        content.isEnabled = true
        content.isClickable = true
        content.isFocusable = true
        layout.showContent()
        assertTrue(content.isEnabled)
        assertTrue(content.isClickable)
        assertTrue(content.isFocusable)
        layout.showError()
        content.isEnabled = false
        layout.showContent()
        assertFalse(content.isEnabled)
    }

    @Test fun `程序化初始Loading从真实提交开始执行最短展示`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(initialState = StateLayoutState.LOADING, useSkeleton = true, effect = SkeletonEffect.SOLID))
        val layout = StateSkeletonLayout(activity)
        layout.transitionEffect = StateTransitionEffect.NONE
        layout.setContentView(TextView(activity).apply { text = "content" })
        var committedAt = 0L
        layout.setOnRenderedStateChangedListener { _, _, state, _ ->
            if (state == StateLayoutState.LOADING) committedAt = android.os.SystemClock.uptimeMillis()
        }
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        attach(layout)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        layout.showContent()
        val remaining = 300L - (android.os.SystemClock.uptimeMillis() - committedAt)
        assertTrue("首次布局后剩余最短展示时间必须大于零", remaining > 1)
        ShadowLooper.idleMainLooper(remaining - 1, TimeUnit.MILLISECONDS)
        assertEquals("min=${layout.minimumLoadingDurationMillis}, start=$committedAt, now=${android.os.SystemClock.uptimeMillis()}",
            StateLayoutState.LOADING, layout.renderedState)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
    }

    @Test fun `挂载前显式请求优先于全局初始态`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(initialState = StateLayoutState.LOADING))
        val layout = create()
        layout.showContent()
        attach(layout)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        assertEquals(0, layout.diagnostics().maskBuildCount)
    }

    @Test fun `程序化初始Error允许先配置错误布局再挂载`() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults(initialState = StateLayoutState.ERROR))
        val layout = create()
        attach(layout)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        assertTrue(layout.getStateView(StateLayoutState.ERROR)!!.isShown)
    }

    private fun parcelState(layout: StateSkeletonLayout): SparseArray<Parcelable> {
        if (layout.id == View.NO_ID) layout.id = View.generateViewId()
        val saved = SparseArray<Parcelable>()
        layout.saveHierarchyState(saved)
        val parcel = Parcel.obtain()
        try {
            (saved[layout.id] as StateSkeletonLayout.SavedState).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            saved.put(layout.id, StateSkeletonLayout.SavedState.CREATOR.createFromParcel(parcel))
        } finally { parcel.recycle() }
        return saved
    }

    @Test fun `Parcel往返恢复运行时资源重试节点规则和状态描述`() {
        val layout = create()
        attach(layout)
        layout.errorRetryViewId = R.id.ssl_test_retry
        layout.retryClickThrottleMillis = 750L
        layout.retryClicksEnabled = false
        layout.setSkeletonNodeConfig(R.id.ssl_test_retry, SkeletonNodeConfig(shape = SkeletonShape.CIRCLE))
        layout.setStateDescription(StateLayoutState.ERROR, "服务不可用")
        layout.showError()
        val saved = parcelState(layout)
        val restored = StateSkeletonLayout(activity).apply {
            id = layout.id
            setContentView(TextView(activity))
        }
        restored.restoreHierarchyState(saved)
        assertEquals(StateLayoutState.ERROR, restored.renderedState)
        assertNotNull(restored.getStateView(StateLayoutState.ERROR)?.findViewById<View>(R.id.ssl_test_retry))
        assertEquals(750L, restored.retryClickThrottleMillis)
        assertFalse(restored.retryClicksEnabled)
        assertEquals("服务不可用", restored.stateDescription)
        val again = parcelState(restored)[restored.id] as StateSkeletonLayout.SavedState
        assertEquals(SkeletonShape.CIRCLE, again.nodeRules[R.id.ssl_test_retry]?.shape)
        assertEquals(R.layout.ssl_test_state_simple, again.errorResource)
    }

    @Test fun `恢复Loading尚未挂载时不提前推进呈现态`() {
        val layout = create()
        attach(layout)
        layout.minimumLoadingDurationMillis = 300L
        layout.skeletonConfig = layout.skeletonConfig.copy(effect = SkeletonEffect.SOLID)
        layout.showLoading()
        val saved = parcelState(layout)
        val restored = StateSkeletonLayout(activity).apply {
            id = layout.id
            setContentView(TextView(activity))
        }
        var committedAt = 0L
        restored.setOnRenderedStateChangedListener { _, _, state, _ ->
            if (state == StateLayoutState.LOADING) committedAt = android.os.SystemClock.uptimeMillis()
        }
        restored.restoreHierarchyState(saved)
        assertEquals(StateLayoutState.LOADING, restored.requestedState)
        assertEquals(StateLayoutState.CONTENT, restored.renderedState)
        attach(restored)
        assertEquals(StateLayoutState.LOADING, restored.renderedState)
        restored.showContent()
        val remaining = 300L - (android.os.SystemClock.uptimeMillis() - committedAt)
        assertTrue("恢复后必须重新建立最短展示计时", remaining > 1)
        ShadowLooper.idleMainLooper(remaining - 1, TimeUnit.MILLISECONDS)
        assertEquals("min=${restored.minimumLoadingDurationMillis}, start=$committedAt, now=${android.os.SystemClock.uptimeMillis()}",
            StateLayoutState.LOADING, restored.renderedState)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertEquals(StateLayoutState.CONTENT, restored.renderedState)
    }

    @Test fun `detach后请求状态不创建View且reattach才创建最新目标`() {
        val layout = create()
        attach(layout)
        val parent = layout.parent as FrameLayout
        parent.removeView(layout)
        var created = 0
        layout.setOnStateViewCreatedListener { _, _, _ -> created++ }
        layout.showEmpty()
        layout.showError()
        assertEquals(0, created)
        assertFalse(layout.diagnostics().hasPendingWork)
        parent.addView(layout)
        assertEquals(1, created)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
    }

    @Test fun `加载可见性回调覆盖隐藏恢复detach以及同态表现替换`() {
        val layout = create()
        layout.setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        attach(layout)
        val events = mutableListOf<Boolean>()
        layout.setOnLoadingVisibilityChangedListener { _, visible, _ -> events += visible }
        layout.showLoading()
        layout.onVisibilityAggregated(false)
        layout.onVisibilityAggregated(true)
        layout.useSkeleton = true
        val parent = layout.parent as FrameLayout
        parent.removeView(layout)
        assertEquals(listOf(true, false, true, false, true, false), events)
        assertFalse(layout.diagnostics().effectRunning)
        assertEquals(0, layout.diagnostics().maskBytes)
    }

    @Test fun `动态状态内容绑定不改变状态且释放缓存后下次重新创建`() {
        val layout = create()
        attach(layout)
        assertNull(layout.getStateView(StateLayoutState.ERROR))
        var created = 0
        var released = 0
        layout.setOnStateViewCreatedListener { _, _, _ -> created++ }
        layout.setOnStateViewReleasedListener { _, _, _ -> released++ }
        layout.updateStateView(StateLayoutState.ERROR) { it.findViewById<TextView>(R.id.ssl_test_retry).text = "再试一次" }
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        assertEquals(1, created)
        layout.clearStateViewCache(StateLayoutState.ERROR)
        assertEquals(1, released)
        assertNull(layout.getStateView(StateLayoutState.ERROR))
        layout.showError()
        assertEquals(2, created)
        assertThrows(IllegalStateException::class.java) { layout.clearStateViewCache(StateLayoutState.ERROR) }
    }

    @Test fun `重试入口可配置节流关闭并拒绝非当前错误页点击`() {
        val layout = create()
        layout.errorRetryViewId = R.id.ssl_test_retry
        layout.retryClickThrottleMillis = 500
        attach(layout)
        var retries = 0
        layout.setOnRetryClickListener { retries++ }
        layout.showError()
        val retry = layout.getStateView(StateLayoutState.ERROR)!!.findViewById<View>(R.id.ssl_test_retry)
        retry.performClick()
        ShadowSystemClock.advanceBy(Duration.ofMillis(499))
        retry.performClick()
        assertEquals(1, retries)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        retry.performClick()
        assertEquals(2, retries)
        layout.retryClicksEnabled = false
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        retry.performClick()
        layout.retryClicksEnabled = true
        layout.showContent()
        retry.performClick()
        assertEquals(2, retries)
    }

    @Test fun `遮罩像素预算超限提供可观察的静态降级`() {
        val layout = create()
        layout.skeletonConfig = layout.skeletonConfig.copy(maximumMaskPixels = 100)
        attach(layout)
        layout.showLoading()
        val diagnostics = layout.diagnostics()
        assertEquals(SkeletonFallbackReason.PIXEL_BUDGET_EXCEEDED, diagnostics.fallbackReason)
        assertEquals(0, diagnostics.maskBytes)
        assertFalse(diagnostics.effectRunning)
        assertEquals(StateLayoutState.LOADING, diagnostics.renderedState)
    }

    @Test fun `重复请求Loading不重建遮罩且静态效果保持无动画`() {
        val layout = create()
        layout.skeletonConfig = layout.skeletonConfig.copy(effect = SkeletonEffect.SOLID)
        attach(layout)
        layout.showLoading()
        val count = layout.diagnostics().maskBuildCount
        repeat(20) { layout.showLoading() }
        assertEquals(count, layout.diagnostics().maskBuildCount)
        assertFalse(layout.diagnostics().effectRunning)
    }

    @Test @Config(sdk = [26]) fun `最低API下自定义状态描述和隐藏内容不调用高版本接口`() {
        val layout = create()
        attach(layout)
        layout.setStateDescription(StateLayoutState.ERROR, "连接失败")
        layout.showError()
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, layout.accessibilityLiveRegion)
        layout.showContent()
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, layout.accessibilityLiveRegion)
    }

    @Test fun `清理无关状态缓存不取消尚未布局的Loading提交`() {
        val layout = create()
        layout.updateStateView(StateLayoutState.ERROR) { }
        layout.showLoading()
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        layout.clearStateViewCache(StateLayoutState.ERROR)
        attach(layout)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
    }

    @Test fun `保存的零重试ID在恢复时继续表示关闭统一重试入口`() {
        val layout = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.ssl_test_host_attrs, null) as StateSkeletonLayout
        layout.errorRetryViewId = 0
        val saved = parcelState(layout)
        val restored = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.ssl_test_host_attrs, null) as StateSkeletonLayout
        restored.id = layout.id
        restored.restoreHierarchyState(saved)
        assertEquals(0, restored.errorRetryViewId)
    }

    @Test fun `detach期间更换当前状态资源重新挂载后呈现新布局`() {
        val layout = create()
        attach(layout)
        layout.showError()
        val parent = layout.parent as FrameLayout
        parent.removeView(layout)
        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_loading_simple)
        assertNull(layout.getStateView(StateLayoutState.ERROR))
        parent.addView(layout)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        assertNotNull(layout.getStateView(StateLayoutState.ERROR))
    }

    @Test fun `旧版无版本标记的Parcel仍可读取原有字段`() {
        val parcel = Parcel.obtain()
        try {
            // 独立重建优化前的线格式，不通过新 SavedState 的 writer 生成夹具。
            View.BaseSavedState(View.BaseSavedState.EMPTY_STATE).writeToParcel(parcel, 0)
            parcel.writeInt(StateLayoutState.ERROR.ordinal)
            parcel.writeInt(1)
            parcel.writeInt(StateTransitionEffect.NONE.ordinal)
            parcel.writeLong(180)
            parcel.writeInt(1)
            parcel.writeLong(100)
            parcel.writeLong(300)
            parcel.writeInt(SkeletonSource.CONTENT.ordinal)
            parcel.writeInt(0)
            parcel.writeInt(SkeletonEffect.PULSE.ordinal)
            parcel.writeInt(0xFF112233.toInt())
            parcel.writeInt(0xFFFFFFFF.toInt())
            parcel.writeFloat(12f)
            parcel.writeLong(1200)
            parcel.writeInt(SkeletonDirection.START_TO_END.ordinal)
            parcel.writeInt(45)
            parcel.writeFloat(0.35f)
            parcel.writeFloat(1f)
            parcel.setDataPosition(0)
            val restored = StateSkeletonLayout.SavedState.CREATOR.createFromParcel(parcel)
            assertEquals(1, restored.formatVersion)
            assertEquals(StateLayoutState.ERROR.ordinal, restored.requestedOrdinal)
            assertEquals(0xFF112233.toInt(), restored.maskColor)
            assertEquals(12f, restored.cornerRadius, 0f)
            assertEquals(100L, restored.showDelay)
            assertEquals(Long.MAX_VALUE, restored.maximumPixels)
        } finally { parcel.recycle() }
    }
}
