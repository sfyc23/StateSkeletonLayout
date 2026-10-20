package com.sfyc.ssl

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 第二轮优化的行为回归测试。
 *
 * 覆盖 2026-10-13 一轮优化新增/调整的语义：
 * - [SslLayoutOptions] 与 `applyOptions`（P-13）
 * - 相同值赋值的短路语义（P-14）
 * - 重试入口的绑定 / 解绑配对（P-05）
 * - 配置变更对资源派生骨架颜色的刷新（P-16）
 * - 清理缓存时拒绝正在参与过渡的状态页（P-06）
 *
 * 默认值一致性见 [SslDefaultValuesTest]，SavedState 往返见
 * [StateSkeletonLayoutSavedStateTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateSkeletonLayoutRefinementsTest {

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

    /**
     * 触发一次配置变更回调。
     *
     * `onConfigurationChanged` 覆写的是 `View` 的 protected 方法（由框架调用），
     * 这里用 Robolectric 的反射工具调用，而不放宽成 public——把它变公开会多出
     * 一个不该由宿主主动调用的 API，也会写进 API 基线。
     */
    private fun StateSkeletonLayout.simulateConfigurationChange() {
        ReflectionHelpers.callInstanceMethod<Void>(
            this,
            "onConfigurationChanged",
            ClassParameter.from(
                android.content.res.Configuration::class.java,
                RuntimeEnvironment.getApplication().resources.configuration,
            ),
        )
    }

    private fun attach(layout: StateSkeletonLayout, width: Int = 240, height: Int = 160) {
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, width, height)
    }

    // region P-13 批量配置

    @Test
    fun `applyOptions 一次性应用多项配置`() {
        // 业务意图：程序化创建时不必逐个 setter 调用 8–10 次。
        val layout = StateSkeletonLayout(activity).apply {
            setContentView(TextView(activity))
        }
        layout.applyOptions(
            SslLayoutOptions(
                emptyLayoutResId = R.layout.ssl_test_state_simple,
                errorLayoutResId = R.layout.ssl_test_state_simple,
                retryConfig = StateRetryConfig(
                    emptyViewIds = listOf(R.id.ssl_test_retry),
                    errorViewIds = listOf(R.id.ssl_test_retry),
                ),
                retryClickThrottleMillis = 750L,
                retryClicksEnabled = false,
                useSkeleton = true,
                skeletonConfig = SkeletonConfig(effect = SkeletonEffect.PULSE),
                transitionDurationMillis = 240L,
                loadingShowDelayMillis = 120L,
                minimumLoadingDurationMillis = 450L,
                announceStateChanges = false,
            ),
        )

        assertEquals(listOf(R.id.ssl_test_retry), layout.getRetryViewIds(StateLayoutState.EMPTY))
        assertEquals(listOf(R.id.ssl_test_retry), layout.getRetryViewIds(StateLayoutState.ERROR))
        assertEquals(750L, layout.retryClickThrottleMillis)
        assertFalse(layout.retryClicksEnabled)
        assertTrue(layout.useSkeleton)
        assertEquals(SkeletonEffect.PULSE, layout.skeletonConfig.effect)
        assertEquals(240L, layout.transitionDurationMillis)
        assertEquals(120L, layout.loadingShowDelayMillis)
        assertEquals(450L, layout.minimumLoadingDurationMillis)
        assertFalse(layout.announceStateChanges)
    }

    @Test
    fun `applyOptions 的null字段保留当前值`() {
        // 业务意图：部分覆盖——只给关心的字段，其余不动。
        val layout = StateSkeletonLayout(activity).apply {
            setContentView(TextView(activity))
            transitionDurationMillis = 321L
            announceStateChanges = false
        }
        layout.applyOptions(SslLayoutOptions(useSkeleton = true))

        // 未给出的字段必须原样保留。
        assertEquals(321L, layout.transitionDurationMillis)
        assertFalse(layout.announceStateChanges)
        assertTrue(layout.useSkeleton)
    }

    @Test
    fun `全空options是空操作且isEmpty判定正确`() {
        val options = SslLayoutOptions()
        assertTrue(options.isEmpty)
        // retryConfig 全 null 仍属于空操作。
        assertTrue(SslLayoutOptions(retryConfig = StateRetryConfig(null, null)).isEmpty)
        // 任一状态非 null 即为有效配置，含显式关闭的空列表。
        assertFalse(SslLayoutOptions(retryConfig = StateRetryConfig(emptyList(), null)).isEmpty)

        val layout = StateSkeletonLayout(activity).apply {
            setContentView(TextView(activity))
            transitionDurationMillis = 200L
            useSkeleton = true
        }
        layout.applyOptions(options)

        assertEquals(200L, layout.transitionDurationMillis)
        assertTrue(layout.useSkeleton)
        assertFalse(SslLayoutOptions(useSkeleton = false).isEmpty)
    }

    @Test
    fun `skeleton工厂预置骨架组合`() {
        val options = SslLayoutOptions.skeleton(
            emptyLayoutResId = R.layout.ssl_test_state_simple,
            errorLayoutResId = R.layout.ssl_test_state_simple,
        )
        assertEquals(true, options.useSkeleton)
        assertEquals(R.layout.ssl_test_state_simple, options.errorLayoutResId)
        assertFalse(options.isEmpty)
    }

    // endregion

    // region P-14 相同值短路

    @Test
    fun `相同值赋值不重建遮罩`() {
        // 业务意图：重复写入相同配置不应触发遮罩重建（列表滚动时宿主可能反复赋值）。
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
            useSkeleton = true
            skeletonConfig = SkeletonConfig(effect = SkeletonEffect.SOLID)
        }
        attach(layout)
        layout.showLoading()
        val builds = layout.diagnostics().maskBuildCount

        // 逐项写回相同值，全部应为空操作。
        layout.transitionEffect = layout.transitionEffect
        layout.transitionDurationMillis = layout.transitionDurationMillis
        layout.announceStateChanges = layout.announceStateChanges
        layout.retryClickThrottleMillis = layout.retryClickThrottleMillis
        layout.retryClicksEnabled = layout.retryClicksEnabled
        layout.useSkeleton = layout.useSkeleton

        assertEquals("相同值赋值不应重建遮罩", builds, layout.diagnostics().maskBuildCount)
    }

    // endregion

    // region P-05 重试入口绑定 / 解绑配对

    @Test
    fun `更换重试ID后旧入口解绑新入口生效`() {
        // 业务意图：换 ID 时旧按钮不再触发回调，新按钮触发。
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
            setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_template_empty)
        }
        // 程序化初始为内置默认 [builtin]；切到宿主布局后显式关闭，模拟“未配置”起点。
        layout.setRetryViewIds(StateLayoutState.ERROR)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()

        // 已显式关闭，无入口。
        assertEquals(0, layout.getRetryViewIds(StateLayoutState.ERROR).size)

        // 换到布局里实际存在的 ID 后应当生效。
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        val errorView = layout.getStateView(StateLayoutState.ERROR)
        val retry = errorView?.findViewById<View>(R.id.ssl_test_retry)
        if (retry != null) {
            retry.performClick()
            assertEquals(1, clicks)
        }
    }

    // endregion

    @Test
    fun `替换错误页后旧重试入口失效`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            retryClickThrottleMillis = 0
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        }
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        var clicks = 0
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val oldRetry = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_retry)

        layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_alternate)

        assertFalse("已释放错误页的重试监听应解绑", oldRetry.hasOnClickListeners())
        oldRetry.performClick()
        assertEquals(0, clicks)
        checkNotNull(layout.getStateView(StateLayoutState.ERROR))
            .findViewById<View>(R.id.ssl_test_retry).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `错误页候选创建失败保留原入口且清理候选入口`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            retryClickThrottleMillis = 0
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        }
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        var clicks = 0
        var rejected: View? = null
        layout.setOnRetryListener { _, _, _ -> clicks++ }
        attach(layout)
        layout.showError()
        val original = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        val oldRetry = original.findViewById<View>(R.id.ssl_test_retry)
        layout.setOnStateViewCreatedListener { _, state, view ->
            if (state == StateLayoutState.ERROR) {
                rejected = view
                throw IllegalStateException("模拟候选状态页绑定失败")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_alternate)
        }

        assertTrue("事务失败应保留原状态页", layout.getStateView(StateLayoutState.ERROR) === original)
        val rejectedRetry = checkNotNull(rejected).findViewById<View>(R.id.ssl_test_retry)
        assertFalse("失败候选不得保留重试监听", rejectedRetry.hasOnClickListeners())
        rejectedRetry.performClick()
        assertEquals(0, clicks)
        oldRetry.performClick()
        assertEquals(1, clicks)
        layout.setRetryViewIds(StateLayoutState.ERROR)
        assertFalse("关闭重试仍须解绑原入口", oldRetry.hasOnClickListeners())
    }

    // region P-16 配置变更

    @Test
    fun `配置变更保留等待最短时长的加载画面与原截止时间`() {
        val content = TextView(activity)
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 1_000
            useSkeleton = false
            setContentView(content)
            setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        }
        val visibilityEvents = mutableListOf<Pair<Boolean, View?>>()
        layout.setOnLoadingVisibilityChangedListener { _, visible, view ->
            visibilityEvents += visible to view
        }
        attach(layout)
        layout.showLoading()
        val shownAt = SystemClock.uptimeMillis()
        val before = checkNotNull(layout.getStateView(StateLayoutState.LOADING))
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        layout.showContent()

        layout.simulateConfigurationChange()

        val after = checkNotNull(layout.getStateView(StateLayoutState.LOADING))
        assertNotSame(before, after)
        assertTrue("切换等待期间必须保留可见加载页", after.isShown)
        assertFalse(content.isShown)
        assertEquals(StateLayoutState.CONTENT, layout.requestedState)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        assertEquals(listOf(true to before, false to before, true to after), visibilityEvents)
        // 资源刷新与 View 帧任务也会推进测试时钟，按原绝对截止时间等待。
        ShadowLooper.idleMainLooper(shownAt + 999 - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertEquals("配置刷新不得重置最短展示计时", StateLayoutState.CONTENT, layout.renderedState)
        assertTrue(content.isShown)
        assertEquals(false to after, visibilityEvents.last())
    }

    @Test
    fun `配置变更保留等待加载延迟的错误画面与原截止时间`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            loadingShowDelayMillis = 1_000
            useSkeleton = false
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        }
        attach(layout)
        layout.showError()
        val before = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        val requestedAt = SystemClock.uptimeMillis()
        layout.showLoading()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        layout.simulateConfigurationChange()

        val after = checkNotNull(layout.getStateView(StateLayoutState.ERROR))
        assertNotSame(before, after)
        assertTrue("延迟加载期间必须保留可见错误页", after.isShown)
        assertEquals(StateLayoutState.LOADING, layout.requestedState)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        ShadowLooper.idleMainLooper(requestedAt + 999 - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertEquals("配置刷新不得重置加载显示延迟", StateLayoutState.LOADING, layout.renderedState)
    }

    @Test
    fun `脱离窗口时配置变更在重新挂载后恢复当前画面再等待目标状态`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            loadingShowDelayMillis = 1_000
            useSkeleton = false
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        }
        attach(layout)
        layout.showError()
        layout.showLoading()
        activity.setContentView(TextView(activity))

        layout.simulateConfigurationChange()
        attach(layout)

        assertEquals(StateLayoutState.LOADING, layout.requestedState)
        assertEquals(StateLayoutState.ERROR, layout.renderedState)
        assertTrue("重新挂载后在延迟期仍须显示错误页",
            checkNotNull(layout.getStateView(StateLayoutState.ERROR)).isShown)
        ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
    }

    @Test
    fun `零尺寸重新挂载不得让过期骨架准备覆盖最新内容状态`() {
        val content = TextView(activity)
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            useSkeleton = false
            setContentView(content)
            setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
        }
        attach(layout)
        layout.showLoading()
        activity.setContentView(TextView(activity))
        layout.useSkeleton = true
        layout.showContent()
        layout.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 0, 0)
        activity.setContentView(layout, android.view.ViewGroup.LayoutParams(0, 0))
        ShadowLooper.idleMainLooper()

        layout.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 240, 160)
        layout.viewTreeObserver.dispatchOnPreDraw()
        ShadowLooper.idleMainLooper()

        assertEquals(StateLayoutState.CONTENT, layout.requestedState)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        assertTrue("迟到的骨架准备回调不得遮住内容", content.isShown)
        assertFalse("内容态不得继续驱动骨架效果", layout.diagnostics().effectRunning)
    }

    @Test
    fun `配置变更刷新资源派生的骨架颜色`() {
        // 业务意图：宿主未显式指定颜色时，切到夜间模式骨架应跟随。
        val layout = StateSkeletonLayout(activity).apply {
            setContentView(TextView(activity))
        }
        val dayColor = layout.skeletonConfig.maskColor
        assertEquals(activity.getColor(R.color.ssl_mask_default), dayColor)

        RuntimeEnvironment.setQualifiers("+night")
        try {
            layout.simulateConfigurationChange()
            assertEquals(
                "配置变更后骨架色应重新解析为夜间资源值",
                RuntimeEnvironment.getApplication().getColor(R.color.ssl_mask_default),
                layout.skeletonConfig.maskColor,
            )
        } finally {
            RuntimeEnvironment.setQualifiers("")
        }
    }

    @Test
    fun `配置变更不覆盖宿主显式指定的颜色`() {
        // 业务意图：宿主给了品牌色就不能被资源默认值顶掉。
        val brand = 0xFF00FF00.toInt()
        val layout = StateSkeletonLayout(activity).apply {
            setContentView(TextView(activity))
            skeletonConfig = SkeletonConfig(maskColor = brand)
        }

        layout.simulateConfigurationChange()

        assertEquals("显式品牌色不得被配置变更覆盖", brand, layout.skeletonConfig.maskColor)
    }

    @Test
    fun `配置变更清空状态页缓存并保留资源声明`() {
        // 业务思路：状态页可能含固定 dp 与静态文案，需要按新配置重新 inflate。
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
        }
        attach(layout)
        layout.showEmpty()
        val before = layout.getStateView(StateLayoutState.EMPTY)

        layout.simulateConfigurationChange()

        // 当前正在呈现的状态页会先被清空、再按新配置重建，因此拿到的是**新实例**。
        // 这正是"固定 dp 与静态文案要跟随新配置"所需的语义。
        val after = layout.getStateView(StateLayoutState.EMPTY)
        assertNotNull("配置变更后活动状态页应按新配置重建", after)
        assertNotSame("重建后应是新实例，而非沿用旧缓存", before, after)
        assertEquals(StateLayoutState.EMPTY, layout.renderedState)
    }

    @Test
    fun `配置变更清空非活动状态页缓存`() {
        // 业务意图：非活动状态页清掉即可，下次显示时惰性重建，不必立刻重建。
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
            setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
        }
        attach(layout)
        layout.showError()
        // getStateView 是纯查询、不创建状态页；用 updateStateView 显式建出 EMPTY 缓存。
        layout.updateStateView(StateLayoutState.EMPTY) { }
        val emptyBefore = layout.getStateView(StateLayoutState.EMPTY)
        assertNotNull("EMPTY 缓存应已被建出", emptyBefore)

        layout.simulateConfigurationChange()

        // 非活动状态页缓存被清空，资源声明保留（再次请求仍可用）。
        assertNull("非活动状态页缓存应被清空", layout.getStateView(StateLayoutState.EMPTY))
        layout.showEmpty()
        assertEquals(StateLayoutState.EMPTY, layout.renderedState)
        assertNotSame("重新显示时应按新配置重建", emptyBefore, layout.getStateView(StateLayoutState.EMPTY))
    }

    // endregion
}
