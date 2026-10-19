package com.sfyc.ssl

import android.app.Activity
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * T2 条目拼装模板的配置变更与状态恢复测试。
 *
 * # 业务意图
 *
 * - 昼夜 / 字体缩放切换后，离屏拼装宿主按新 Configuration 重建，
 *   Loading 不中断、不崩溃、不退化为空几何。
 * - 进程重建恢复时只带回布局与条数（binder 不可序列化），恢复后置空并打
 *   Debug 日志指引宿主重设；形状声明完整，渲染可继续。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemAssembledConfigChangeTest {

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

    private fun idleLayout(): StateSkeletonLayout {
        val layout = StateSkeletonLayout(activity).apply {
            id = View.generateViewId()
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            loadingShowDelayMillis = 0
            setContentView(TextView(activity).apply { text = "content" })
        }
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        layout.layout(0, 0, 360, 640)
        return layout
    }

    /**
     * 触发一次配置变更回调（与 StateSkeletonLayoutRefinementsTest 相同的手段：
     * `onConfigurationChanged` 是 View 的 protected 方法，不放宽为公开 API）。
     */
    private fun StateSkeletonLayout.simulateConfigurationChange() {
        ReflectionHelpers.callInstanceMethod<Void>(
            this,
            "onConfigurationChanged",
            ReflectionHelpers.ClassParameter.from(
                android.content.res.Configuration::class.java,
                RuntimeEnvironment.getApplication().resources.configuration,
            ),
        )
    }

    @Test
    fun `昼夜切换后T2骨架重建且Loading不中断`() {
        val layout = idleLayout()
        layout.setSkeletonItemTemplate(R.layout.ssl_test_item_row, 4) { item, _ ->
            item.findViewById<TextView>(R.id.ssl_test_item_title)?.text = "占位标题"
            item.findViewById<TextView>(R.id.ssl_test_item_subtitle)?.text = "占位副标题"
        }
        layout.showLoading()
        ShadowLooper.idleMainLooper()
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        val buildsBefore = layout.diagnostics().maskBuildCount
        assertTrue(buildsBefore > 0)

        RuntimeEnvironment.setQualifiers("+night")
        try {
            layout.simulateConfigurationChange()
            ShadowLooper.idleMainLooper()
        } finally {
            RuntimeEnvironment.setQualifiers("")
        }

        // Loading 继续呈现，动画不停。
        assertEquals(StateLayoutState.LOADING, layout.renderedState)
        assertTrue(layout.diagnostics().effectRunning)
        // 条目声明保留：同一份成功布局、同样的条数。
        val items = layout.skeletonItems
        assertNotNull(items)
        assertEquals(1, items!!.size)
        assertEquals(R.layout.ssl_test_item_row, items.single().layoutResId)
        assertEquals(4, items.single().count)
        // 遮罩按新配置重建（计数只增不减）。
        assertTrue(
            "配置变更后遮罩应重建，变更前=$buildsBefore，变更后=${layout.diagnostics().maskBuildCount}",
            layout.diagnostics().maskBuildCount > buildsBefore,
        )
        assertEquals(SkeletonFallbackReason.NONE, layout.diagnostics().fallbackReason)
    }

    @Test
    fun `SavedState往返保留布局与条数_binder置空并打Debug日志`() {
        val layout = idleLayout()
        layout.setSkeletonItemTemplate(R.layout.ssl_test_item_row, 3) { _, _ -> }

        // 走真实保存路径：saveHierarchyState -> Parcel 往返 -> restoreHierarchyState。
        val stored = SparseArray<Parcelable>()
        layout.saveHierarchyState(stored)
        val parcel = Parcel.obtain()
        val restored: StateSkeletonLayout.SavedState
        try {
            val snapshot = stored[layout.id] as StateSkeletonLayout.SavedState
            snapshot.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            restored = StateSkeletonLayout.SavedState.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        assertEquals(listOf(R.layout.ssl_test_item_row), restored.skeletonItemLayouts)
        assertEquals(listOf(3), restored.skeletonItemCounts)

        ShadowLog.clear()
        val revived = StateSkeletonLayout(activity).apply {
            id = layout.id
            setContentView(TextView(activity).apply { text = "content" })
        }
        activity.setContentView(revived)
        revived.restoreHierarchyState(stored)

        val items = revived.skeletonItems
        assertNotNull("恢复后条目声明应保留", items)
        assertEquals(R.layout.ssl_test_item_row, items!!.single().layoutResId)
        assertEquals(3, items.single().count)
        assertNull("binder 不可序列化，恢复后必须置空", items.single().binder)
        assertEquals(SkeletonSource.TEMPLATE, revived.skeletonConfig.source)

        val debugLogs = ShadowLog.getLogsForTag("StateSkeletonLayout")
        assertTrue(
            "恢复丢 binder 时应打 Debug 日志指引重设，实际日志=$debugLogs",
            debugLogs.any { it.type == Log.DEBUG && it.msg.contains("binder") },
        )
    }
}
