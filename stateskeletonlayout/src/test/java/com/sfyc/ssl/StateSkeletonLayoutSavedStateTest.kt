package com.sfyc.ssl

import android.app.Activity
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * SavedState 往返一致性测试（P-02 的验收条件）。
 *
 * # 为什么需要反射驱动
 *
 * [StateSkeletonLayout.SavedState] 目前序列化约 30 个字段，而
 * `onSaveInstanceState` 赋值、`writeToParcel`、`Parcel` 构造读取是**三份手写清单**，
 * 顺序与版本判断必须严格对齐。新增一个可保存属性时要在四处同步：
 * 字段声明、`onSaveInstanceState` 赋值、`writeToParcel`、读取。任何一处遗漏都不会编译报错。
 *
 * 本测试用反射遍历 `SavedState` 的字段，断言每个字段都真的经过了 Parcel 往返。
 * 新增字段若没接进读写链路，会在这里失败，而不是等到线上恢复出一份错配置。
 *
 * # 覆盖范围
 *
 * 1. **字段集合**：反射遍历 `SavedState` 的实例字段，逐个断言往返后值不变。
 * 2. **版本自检**：`writeToParcel` 会断言 `formatVersion` 与写入布局一致。
 * 3. **端到端**：往返后的配置能真正驱动一个恢复实例。
 *
 * 不覆盖静态字段（`CREATOR`）与 `formatVersion`（由版本分支单独管理）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateSkeletonLayoutSavedStateTest {

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
        // saveHierarchyState 按 View ID 索引；没有 ID 就什么都不会被保存。
        // 必须在保存前赋值，否则取出来的是 null。
        id = View.generateViewId()
        transitionEffect = StateTransitionEffect.NONE
        minimumLoadingDurationMillis = 0
        setContentView(TextView(activity).apply { text = "content" })
        setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
        setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
    }

    /** 走真实保存路径拿到未往返的 SavedState。 */
    private fun snapshot(layout: StateSkeletonLayout): StateSkeletonLayout.SavedState {
        val stored = SparseArray<Parcelable>()
        layout.saveHierarchyState(stored)
        return stored[layout.id] as StateSkeletonLayout.SavedState
    }

    /** 把当前状态写进 Parcel 再读回来。 */
    private fun roundTrip(layout: StateSkeletonLayout): StateSkeletonLayout.SavedState {
        val parcel = Parcel.obtain()
        try {
            snapshot(layout).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return StateSkeletonLayout.SavedState.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /**
     * 核心断言：`SavedState` 的每个实例字段都经过 Parcel 往返且值不变。
     *
     * 反射遍历而非手写字段清单，正是为了让"新增字段忘了接线"直接变成失败。
     */
    @Test
    fun `SavedState每个字段都经过Parcel往返`() {
        val layout = create()
        // 尽量把每个字段设成与默认值不同的值，避免"默认值相等"掩盖漏写。
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_retry, R.id.ssl_test_retry_second)
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        layout.retryClickThrottleMillis = 750L
        layout.retryClicksEnabled = false
        layout.useSkeleton = true
        layout.transitionEffect = StateTransitionEffect.CROSSFADE
        layout.transitionDurationMillis = 240L
        layout.announceStateChanges = false
        layout.loadingShowDelayMillis = 120L
        layout.minimumLoadingDurationMillis = 450L
        layout.skeletonConfig = SkeletonConfig(
            source = SkeletonSource.CONTENT,
            effect = SkeletonEffect.PULSE,
            maskColor = 0xFF123456.toInt(),
            highlightColor = 0xFF654321.toInt(),
            cornerRadius = 10f,
            animationDurationMillis = 900L,
            direction = SkeletonDirection.END_TO_START,
            angleDegrees = 45,
            pulseMinAlpha = 0.2f,
            pulseMaxAlpha = 0.9f,
            maximumMaskPixels = 1_000_000L,
        )
        layout.setSkeletonNodeConfig(R.id.ssl_test_retry, SkeletonNodeConfig(shape = SkeletonShape.CIRCLE))
        layout.setStateDescription(StateLayoutState.ERROR, "服务不可用")

        val before = snapshot(layout)
        val after = roundTrip(layout)

        val fields = StateSkeletonLayout.SavedState::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.name == "formatVersion" }
            .onEach { it.isAccessible = true }

        // 字段数下限是防"反射悄悄失效"的哨兵：真掉到个位数说明遍历方式坏了，
        // 那种情况下空集合会让下面的循环假通过。
        assertTrue("SavedState 字段数异常，反射可能失效", fields.size >= 25)

        for (field in fields) {
            val expected = field.get(before)
            val actual = field.get(after)
            assertEquals("字段 ${field.name} 未经过 Parcel 往返（值不一致）", expected, actual)
        }
    }

    /**
     * 版本号必须与写入布局匹配：`writeToParcel` 内部有 `require` 自检。
     *
     * 这条防的是"新增字段改了写入布局，却忘了把 `formatVersion` 一起改"。
     * 读侧按版本决定解析哪些字段，两者脱节会静默读出错配置。
     */
    @Test
    fun `版本号与写入布局不匹配时拒绝写出`() {
        val saved = StateSkeletonLayout.SavedState(View.BaseSavedState.EMPTY_STATE).apply {
            formatVersion = StateSkeletonLayout.SavedState.CURRENT_FORMAT_VERSION + 1
        }
        val parcel = Parcel.obtain()
        try {
            val failure = runCatching { saved.writeToParcel(parcel, 0) }.exceptionOrNull()
            assertTrue(
                "formatVersion 与写入布局脱节时应抛异常，实际=$failure",
                failure is IllegalArgumentException,
            )
        } finally {
            parcel.recycle()
        }
    }

    /** 往返后的配置应能真正驱动一个恢复实例。 */
    @Test
    fun `往返后的配置可用于恢复实例`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_retry, R.id.ssl_test_retry_second)
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        layout.retryClicksEnabled = false
        layout.showError()

        val stored = SparseArray<Parcelable>()
        layout.saveHierarchyState(stored)

        val restored = StateSkeletonLayout(activity).apply {
            id = layout.id
            setContentView(TextView(activity))
        }
        restored.restoreHierarchyState(stored)

        assertEquals(StateLayoutState.ERROR, restored.renderedState)
        assertEquals(
            listOf(R.id.ssl_test_retry, R.id.ssl_test_retry_second),
            restored.getRetryViewIds(StateLayoutState.EMPTY),
        )
        assertEquals(listOf(R.id.ssl_test_retry), restored.getRetryViewIds(StateLayoutState.ERROR))
        assertNotNull(restored.getStateView(StateLayoutState.ERROR)?.findViewById<View>(R.id.ssl_test_retry))
    }

    @Test
    fun `显式关闭往返后仍关闭且无效格式拒绝恢复`() {
        val layout = create()
        layout.setRetryViewIds(StateLayoutState.EMPTY)
        layout.setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_retry)
        layout.showEmpty()

        val stored = SparseArray<Parcelable>()
        layout.saveHierarchyState(stored)

        val restored = StateSkeletonLayout(activity).apply {
            id = layout.id
            setContentView(TextView(activity))
        }
        restored.restoreHierarchyState(stored)
        assertTrue(restored.getRetryViewIds(StateLayoutState.EMPTY).isEmpty())
        assertEquals(listOf(R.id.ssl_test_retry), restored.getRetryViewIds(StateLayoutState.ERROR))

        // 无效格式：版本号篡改后写入拒绝。
        val bad = (stored[layout.id] as StateSkeletonLayout.SavedState).apply {
            formatVersion = StateSkeletonLayout.SavedState.CURRENT_FORMAT_VERSION + 1
        }
        val parcel = Parcel.obtain()
        try {
            val failure = runCatching { bad.writeToParcel(parcel, 0) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `恢复显式关闭时解绑已创建错误页的默认重试入口`() {
        val source = StateSkeletonLayout(activity).apply {
            id = View.generateViewId()
            transitionEffect = StateTransitionEffect.NONE
            setContentView(TextView(activity))
            setRetryViewIds(StateLayoutState.ERROR)
            showError()
        }
        val stored = SparseArray<Parcelable>().apply { put(source.id, roundTrip(source)) }
        val restored = StateSkeletonLayout(activity).apply {
            id = source.id
            transitionEffect = StateTransitionEffect.NONE
            setContentView(TextView(activity))
        }
        var clicks = 0
        restored.setOnRetryListener { _, _, _ -> clicks++ }
        attach(restored)
        restored.showError()
        val root = checkNotNull(restored.getStateView(StateLayoutState.ERROR))
        val retry = root.findViewById<View>(R.id.ssl_builtin_state_retry)
        assertTrue(retry.hasOnClickListeners())

        restored.restoreHierarchyState(stored)

        assertSame(root, restored.getStateView(StateLayoutState.ERROR))
        assertTrue(restored.getRetryViewIds(StateLayoutState.ERROR).isEmpty())
        retry.performClick()
        assertEquals(0, clicks)
        assertFalse(retry.hasOnClickListeners())
    }

    @Test
    fun `恢复多入口时重新绑定已缓存的空态和错误页`() {
        val source = create().apply {
            setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_retry_multi)
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_retry_multi)
            setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image, R.id.ssl_test_multi_empty_text)
            setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_image, R.id.ssl_test_multi_error_button)
            retryClickThrottleMillis = 0
        }
        val stored = SparseArray<Parcelable>().apply { put(source.id, roundTrip(source)) }
        val restored = create().apply {
            id = source.id
            setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_retry_multi)
            setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_retry_multi)
            setRetryViewIds(StateLayoutState.EMPTY, R.id.ssl_test_multi_empty_image)
            setRetryViewIds(StateLayoutState.ERROR, R.id.ssl_test_multi_error_image)
        }
        val clicks = mutableListOf<Int>()
        restored.setOnRetryListener { _, _, clickedView -> clicks += clickedView.id }
        attach(restored)
        restored.showEmpty()
        val emptyRoot = checkNotNull(restored.getStateView(StateLayoutState.EMPTY))
        restored.showError()
        val errorRoot = checkNotNull(restored.getStateView(StateLayoutState.ERROR))

        restored.restoreHierarchyState(stored)

        restored.showEmpty()
        assertSame(emptyRoot, restored.getStateView(StateLayoutState.EMPTY))
        emptyRoot.findViewById<View>(R.id.ssl_test_multi_empty_image).performClick()
        emptyRoot.findViewById<View>(R.id.ssl_test_multi_empty_text).performClick()
        restored.showError()
        assertSame(errorRoot, restored.getStateView(StateLayoutState.ERROR))
        errorRoot.findViewById<View>(R.id.ssl_test_multi_error_image).performClick()
        errorRoot.findViewById<View>(R.id.ssl_test_multi_error_button).performClick()
        assertEquals(
            listOf(R.id.ssl_test_multi_empty_image, R.id.ssl_test_multi_empty_text,
                R.id.ssl_test_multi_error_image, R.id.ssl_test_multi_error_button),
            clicks,
        )
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
}
