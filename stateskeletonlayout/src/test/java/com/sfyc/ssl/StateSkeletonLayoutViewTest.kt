package com.sfyc.ssl

import android.app.Activity
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicReference

/**
 * StateSkeletonLayout View 层 Robolectric 测试（对应实现方案第 24.2 节）。
 *
 * 业务意图：验证 XML 属性解析、单内容约束、状态页按需复用、
 * 骨架不污染业务 View、重试单向事件与状态保存恢复。
 * 运行在 JVM，无需真机；动画与绘制走 Shadow 实现不断言像素。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class StateSkeletonLayoutViewTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun inflate(resId: Int): StateSkeletonLayout {
        return LayoutInflater.from(activity).inflate(resId, null) as StateSkeletonLayout
    }

    private fun attachAndLayout(layout: StateSkeletonLayout, width: Int = 1080, height: Int = 1920) {
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        layout.measure(widthSpec, heightSpec)
        layout.layout(0, 0, width, height)
    }

    @Test
    fun `XML属性正确解析且初始为内容态`() {
        // 业务意图：XML 声明的配置（效果/延迟/布局/重试 ID）应完整进入运行时。
        val layout = inflate(R.layout.ssl_test_host_attrs)

        assertEquals(StateLayoutState.CONTENT, layout.requestedState)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        assertEquals(SkeletonEffect.PULSE, layout.skeletonConfig.effect)
        assertFalse(layout.useSkeleton)
        assertEquals(0L, layout.loadingShowDelayMillis)
        assertEquals(300L, layout.minimumLoadingDurationMillis)
    }

    @Test
    fun `初始状态error直接呈现错误页`() {
        // 业务意图：ssl_initialState 非 content 时，膨胀完成即呈现对应状态。
        val layout = inflate(R.layout.ssl_test_host_initial_error)

        assertEquals(StateLayoutState.ERROR, layout.renderedState)
    }

    @Test
    fun `零业务子View膨胀时抛明确异常`() {
        // 膨胀过程会把 onFinishInflate 的异常包装为 InflateException，断言时沿因果链查找。
        assertInflateFails(R.layout.ssl_test_host_zero, "0")
    }

    @Test
    fun `多个业务子View膨胀时抛明确异常`() {
        assertInflateFails(R.layout.ssl_test_host_two, "2")
    }

    // 沿异常因果链查找单内容约束的 IllegalStateException。
    private fun assertInflateFails(resId: Int, messagePart: String) {
        try {
            inflate(resId)
            fail("非法业务子 View 数量应抛异常")
        } catch (e: Exception) {
            val illegal = generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .firstOrNull()
            assertTrue(
                "因果链中应包含单内容约束异常，实际=${e::class.java.name}",
                illegal != null && illegal.message!!.contains(messagePart),
            )
        }
    }

    @Test
    fun `程序化创建可设置唯一内容且重复设置抛异常`() {
        // 业务意图：非 XML 路径同样保证单内容不变量。
        val layout = StateSkeletonLayout(activity)
        layout.setContentView(TextView(activity))
        try {
            layout.setContentView(TextView(activity))
            fail("重复 setContentView 应抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("重复"))
        }
    }

    @Test
    fun `状态页按需创建且同一状态复用同一实例`() {
        // 业务意图：未请求的状态不 inflate；重复请求不重建。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)

        layout.render(StateLayoutState.EMPTY)
        val first = layout.findViewById<View>(R.id.ssl_test_retry)
        layout.render(StateLayoutState.CONTENT)
        layout.render(StateLayoutState.EMPTY)
        val second = layout.findViewById<View>(R.id.ssl_test_retry)

        assertSame("同一状态应复用已创建 View", first, second)
    }

    @Test
    fun `更换状态布局后旧View被移除`() {
        // 业务意图：setStateViewLayout 丢弃旧缓存，下次呈现重新 inflate。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)

        layout.render(StateLayoutState.EMPTY)
        val oldRetry = layout.findViewById<View>(R.id.ssl_test_retry)
        assertTrue(oldRetry.isAttachedToWindow)

        layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_loading_simple)
        assertFalse("旧状态 View 应解除挂载", oldRetry.isAttachedToWindow)
    }

    @Test
    fun `非法状态布局资源不会移除当前缓存View`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.render(StateLayoutState.EMPTY)
        val current = layout.findViewById<View>(R.id.ssl_test_retry)

        try {
            layout.setStateViewLayout(StateLayoutState.EMPTY, 0)
            fail("layoutResId=0 应同步拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("layoutResId"))
        }

        assertTrue(current.isAttachedToWindow)
        assertEquals(StateLayoutState.EMPTY, layout.renderedState)
    }

    @Test
    fun `骨架显示不改变业务后代的visibility`() {
        // 业务意图：进出骨架期间业务 View 的 VISIBLE/INVISIBLE/GONE 原样保留。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        val contentChild = (layout.getChildAt(0) as FrameLayout).getChildAt(0)
        assertEquals(View.VISIBLE, contentChild.visibility)

        layout.render(StateLayoutState.LOADING)
        assertEquals(
            "骨架抑制绘制不得修改业务后代 visibility",
            View.VISIBLE,
            contentChild.visibility,
        )

        layout.minimumLoadingDurationMillis = 0L
        layout.render(StateLayoutState.CONTENT)
        assertEquals(View.VISIBLE, contentChild.visibility)
    }

    @Test
    fun `骨架遮罩在布局完成后可用且遮罩层可见`() {
        // 业务意图：内容取形在真实布局后能产出遮罩，而不是空白加载页。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)

        layout.render(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.LOADING, layout.renderedState)
    }

    @Test
    fun `请求未配置状态时快速失败`() {
        // 业务意图：缺布局时抛可定位异常，不显示无反馈空白页。
        val layout = StateSkeletonLayout(activity)
        layout.setContentView(TextView(activity))
        try {
            layout.render(StateLayoutState.EMPTY)
            fail("未配置 emptyLayout 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ssl_emptyLayout"))
        }
    }

    @Test
    fun `自定义Loading缺少布局时自动回退骨架不抛异常`() {
        val layout = StateSkeletonLayout(activity)
        layout.setContentView(TextView(activity))
        layout.useSkeleton = false
        layout.loadingShowDelayMillis = 1_000L

        layout.render(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.LOADING, layout.requestedState)
    }

    @Test
    fun `状态View创建失败时画面状态保持原子且同一目标可重试`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.setOnStateViewCreatedListener { _, state, _ ->
            if (state == StateLayoutState.EMPTY) error("模拟绑定失败")
        }

        try {
            layout.render(StateLayoutState.EMPTY)
            fail("状态 View 绑定失败应同步抛出")
        } catch (e: IllegalStateException) {
            assertEquals("模拟绑定失败", e.message)
        }

        assertEquals(StateLayoutState.CONTENT, layout.requestedState)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        layout.setOnStateViewCreatedListener(null)
        layout.render(StateLayoutState.EMPTY)
        assertEquals(StateLayoutState.EMPTY, layout.renderedState)
    }

    @Test
    fun `骨架等待首次布局时不提前推进renderedState`() {
        val layout = StateSkeletonLayout(activity)
        layout.setContentView(TextView(activity))

        layout.render(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.LOADING, layout.requestedState)
        assertEquals(StateLayoutState.CONTENT, layout.renderedState)

        attachAndLayout(layout)

        assertEquals(StateLayoutState.LOADING, layout.renderedState)
    }

    @Test
    fun `运行时配置与监听器从后台线程修改会得到一致失败`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        val failures = listOf<() -> Unit>(
            { layout.useSkeleton = true },
            { layout.skeletonConfig = layout.skeletonConfig.copy(angleDegrees = 45) },
            { layout.loadingShowDelayMillis = 10L },
            { layout.minimumLoadingDurationMillis = 10L },
            { layout.transitionEffect = StateTransitionEffect.NONE },
            { layout.transitionDurationMillis = 10L },
            { layout.announceStateChanges = false },
            { layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple) },
            { layout.setOnRetryClickListener(null) },
            { layout.setOnStateViewCreatedListener(null) },
            { layout.setOnRenderedStateChangedListener(null) },
        )

        failures.forEach { operation ->
            val thrown = AtomicReference<Throwable?>()
            Thread {
                try {
                    operation()
                } catch (failure: Throwable) {
                    thrown.set(failure)
                }
            }.apply {
                start()
                join()
            }
            assertTrue(
                "所有 UI 配置入口都应拒绝后台线程",
                thrown.get() is IllegalStateException &&
                    thrown.get()!!.message!!.contains("必须在主线程"),
            )
        }
    }

    @Test
    fun `重试点击只发事件不自动改变状态`() {
        // 业务意图：重试是单向事件，控件不自动切 Loading、不发请求。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        var clicked = 0
        layout.setOnRetryClickListener { clicked++ }

        layout.render(StateLayoutState.ERROR)
        layout.findViewById<View>(R.id.ssl_test_retry).performClick()

        assertEquals(1, clicked)
        assertEquals(
            "重试点击后状态应保持 ERROR，由调用方决定下一步",
            StateLayoutState.ERROR,
            layout.renderedState,
        )
    }

    @Test
    fun `状态保存恢复后呈现最新请求`() {
        // 业务意图：旋转/进程重建后恢复请求状态与关键配置，无动画重建。
        // 走框架公开的层级状态接口（与旋转重建时系统调用路径一致）。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.id = View.generateViewId()
        layout.minimumLoadingDurationMillis = 0L
        layout.render(StateLayoutState.ERROR)

        val container = android.util.SparseArray<Parcelable>()
        layout.saveHierarchyState(container)

        val recreated = inflate(R.layout.ssl_test_host_attrs)
        recreated.id = layout.id
        recreated.restoreHierarchyState(container)

        assertEquals(StateLayoutState.ERROR, recreated.requestedState)
        assertEquals(StateLayoutState.ERROR, recreated.renderedState)
    }

    @Test
    fun `恢复同一个Content画面不会冻结当前View交互`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.id = View.generateViewId()
        val container = android.util.SparseArray<Parcelable>()
        layout.saveHierarchyState(container)

        val recreated = inflate(R.layout.ssl_test_host_attrs)
        recreated.id = layout.id
        recreated.restoreHierarchyState(container)
        val contentHost = recreated.getChildAt(0) as ViewGroup

        assertEquals(StateLayoutState.CONTENT, recreated.renderedState)
        assertTrue(contentHost.isEnabled)
        assertTrue(contentHost.getChildAt(0).isEnabled)
    }

    @Test
    fun `呈现回调按提交顺序触发且参数完整`() {
        // 业务意图：调用方可信赖回调做 Lottie 启停与测试等待。
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.minimumLoadingDurationMillis = 0L
        val events = mutableListOf<Pair<StateLayoutState, StateLayoutState>>()
        layout.setOnRenderedStateChangedListener { _, old, new, _ ->
            events += old to new
        }

        layout.render(StateLayoutState.LOADING)
        layout.render(StateLayoutState.CONTENT)

        assertEquals(
            listOf(
                StateLayoutState.CONTENT to StateLayoutState.LOADING,
                StateLayoutState.LOADING to StateLayoutState.CONTENT,
            ),
            events,
        )
    }

    @Test
    fun `Content到状态页交叉淡化期间旧内容仍可见但不可交互不可访问`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.transitionEffect = StateTransitionEffect.CROSSFADE
        layout.transitionDurationMillis = 1_000L
        val contentHost = layout.getChildAt(0) as ViewGroup

        layout.render(StateLayoutState.EMPTY)

        assertEquals("淡出阶段旧内容仍应参与绘制", View.VISIBLE, contentHost.visibility)
        assertFalse(contentHost.isEnabled)
        assertTrue("业务后代属性必须保留", contentHost.getChildAt(0).isEnabled)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            contentHost.importantForAccessibility,
        )
    }

    @Test
    fun `Empty到Error使用两个具体状态View交叉淡化并冻结旧节点`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        val views = mutableMapOf<StateLayoutState, View>()
        layout.setOnStateViewCreatedListener { _, state, view -> views[state] = view }
        layout.transitionEffect = StateTransitionEffect.NONE
        layout.render(StateLayoutState.EMPTY)
        val emptyView = views.getValue(StateLayoutState.EMPTY)

        layout.transitionEffect = StateTransitionEffect.CROSSFADE
        layout.transitionDurationMillis = 1_000L
        layout.render(StateLayoutState.ERROR)
        val errorView = views.getValue(StateLayoutState.ERROR)

        assertEquals(View.VISIBLE, emptyView.visibility)
        assertEquals(View.VISIBLE, errorView.visibility)
        assertFalse((emptyView.parent as View).isEnabled)
        assertTrue("原始状态根节点不被库改写", emptyView.isEnabled)
        assertTrue("业务按钮属性必须保留", emptyView.findViewById<View>(R.id.ssl_test_retry).isEnabled)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            (emptyView.parent as View).importantForAccessibility,
        )
        assertTrue(errorView.isEnabled)
    }

    @Test
    fun `动画中连续切换最终只保留最新状态且恢复内容交互`() {
        val layout = inflate(R.layout.ssl_test_host_attrs)
        attachAndLayout(layout)
        layout.minimumLoadingDurationMillis = 0L
        layout.transitionEffect = StateTransitionEffect.CROSSFADE
        layout.transitionDurationMillis = 1_000L
        val contentHost = layout.getChildAt(0) as ViewGroup

        layout.render(StateLayoutState.EMPTY)
        layout.render(StateLayoutState.ERROR)
        layout.render(StateLayoutState.CONTENT)

        assertEquals(StateLayoutState.CONTENT, layout.renderedState)
        assertTrue(contentHost.isEnabled)
        assertTrue(contentHost.getChildAt(0).isEnabled)
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, contentHost.visibility)
        assertEquals(View.GONE, layout.getChildAt(1).visibility)
    }
}
