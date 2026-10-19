package com.sfyc.demo.recycler

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.MainActivity
import com.sfyc.demo.ssl.R
import com.sfyc.ssl.SkeletonSource
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

/**
 * 条目复用骨架示例的行为锁：XML 声明 `itemLayout` 即 T2，
 * 占位绑定补齐后与 T1 手写模板可互相切换，业务 Adapter 全程不被替换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ItemReuseSkeletonFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: ItemReuseSkeletonFragment
    private val activity get() = controller.get()
    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val list get() = fragment.requireView().findViewById<RecyclerView>(R.id.demo_content_list)

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = ItemReuseSkeletonFragment()
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

    @Test fun `XML声明即T2复用成功布局`() {
        assertEquals(SkeletonSource.TEMPLATE, state.skeletonConfig.source)
        assertNull(state.skeletonConfig.templateLayoutResId)
        val items = state.skeletonItems
        assertNotNull("XML 声明的 itemLayout 应解析为 skeletonItems", items)
        assertEquals(1, items!!.size)
        assertEquals(R.layout.item_profile_row, items.single().layoutResId)
        assertEquals(8, items.single().count)
        assertEquals(StateLayoutState.LOADING, state.renderedState)
    }

    @Test fun `T2遮罩覆盖整屏且只有一个实例`() {
        val snapshot = state.diagnostics()
        assertTrue("遮罩未构建：$snapshot", snapshot.maskBytes > 0)
        assertEquals(state.width, snapshot.maskWidth)
        assertEquals(state.height, snapshot.maskHeight)
        assertTrue(snapshot.effectRunning)
    }

    @Test fun `切换到T1手写模板后再切回T2`() {
        click(R.id.demo_btn_toggle)
        assertNull(state.skeletonItems)
        assertEquals(R.layout.skeleton_list_template, state.skeletonConfig.templateLayoutResId)
        click(R.id.demo_btn_toggle)
        val items = state.skeletonItems
        assertNotNull(items)
        assertEquals(R.layout.item_profile_row, items!!.single().layoutResId)
        assertNull(state.skeletonConfig.templateLayoutResId)
    }

    @Test fun `业务Adapter全程不被替换且数据保留`() {
        val adapter = list.adapter
        assertNotNull("列表 Adapter 未设置", adapter)
        assertEquals(20, adapter!!.itemCount)
        click(R.id.demo_btn_toggle)
        assertSame(adapter, list.adapter)
        assertEquals(20, list.adapter!!.itemCount)
        click(R.id.demo_btn_replay)
        settle()
        assertSame(adapter, list.adapter)
        assertEquals(20, list.adapter!!.itemCount)
    }

    @Test fun `说明文字讲清XML声明与占位绑定分工`() {
        val hint =
            fragment.requireView().findViewById<TextView>(R.id.demo_log).text.toString()
        assertTrue("日志未记录 T2 条目复用：$hint", hint.contains("T2"))
    }
}
