package com.sfyc.demo.listskeleton

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
 * 整屏列表骨架模板示例的行为锁：一个实例覆盖整屏多行、模板形状被采集、
 * 业务 Adapter 全程不被替换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ListSkeletonDemoFragmentTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: ListSkeletonDemoFragment
    private val activity get() = controller.get()
    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val list get() = fragment.requireView().findViewById<RecyclerView>(R.id.demo_list)
    private val logText get() =
        fragment.requireView().findViewById<TextView>(R.id.demo_log).text.toString()

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = ListSkeletonDemoFragment()
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

    @Test fun `模板骨架配置正确`() {
        val config = state.skeletonConfig
        assertEquals(SkeletonSource.TEMPLATE, config.source)
        assertEquals(R.layout.skeleton_list_rows_template, config.templateLayoutResId)
        assertEquals(StateLayoutState.LOADING, state.renderedState)
    }

    @Test fun `整屏模板用一张全尺寸遮罩服务全部八行`() {
        val snapshot = state.diagnostics()
        assertTrue("遮罩未构建：$snapshot", snapshot.maskBytes > 0)
        // 遮罩尺寸即整个 StateSkeletonLayout，而不是某一行。
        assertEquals(state.width, snapshot.maskWidth)
        assertEquals(state.height, snapshot.maskHeight)
        assertTrue(snapshot.effectRunning)

        // 关键取舍：整屏只有这一个 StateSkeletonLayout 实例，
        // 因此只有一份遮罩；每行一个实例会产生八份。
        assertEquals(1, countStateLayouts(fragment.requireView()))
    }

    /** 统计页面里的 StateSkeletonLayout 实例数，用于锁住「整屏一个实例」这个约定。 */
    private fun countStateLayouts(view: View): Int {
        var count = if (view is StateSkeletonLayout) 1 else 0
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) count += countStateLayouts(view.getChildAt(i))
        }
        return count
    }

    @Test fun `四态切换后 renderedState 与文案同步更新`() {
        click(R.id.demo_btn_empty)
        assertEquals(StateLayoutState.EMPTY, state.renderedState)
        click(R.id.demo_btn_error)
        assertEquals(StateLayoutState.ERROR, state.renderedState)
        click(R.id.demo_btn_content)
        assertEquals(StateLayoutState.CONTENT, state.renderedState)
        click(R.id.demo_btn_loading)
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertTrue("日志未记录遮罩尺寸：$logText", logText.contains("遮罩"))
    }

    @Test fun `业务 Adapter 全程不被替换且数据保留`() {
        val adapter = list.adapter
        assertNotNull("列表 Adapter 未设置", adapter)
        assertEquals(20, adapter!!.itemCount)
        assertTrue(list.layoutManager is androidx.recyclerview.widget.LinearLayoutManager)

        click(R.id.demo_btn_loading)
        assertSame(adapter, list.adapter)
        assertEquals(20, list.adapter!!.itemCount)
        click(R.id.demo_btn_content)
        assertSame(adapter, list.adapter)
        assertEquals(20, list.adapter!!.itemCount)
    }

    @Test fun `说明文字讲清整屏模板相对每 item 实例的取舍`() {
        val note =
            fragment.requireView().findViewById<TextView>(R.id.demo_list_skeleton_note).text.toString()
        assertEquals(activity.getString(R.string.demo_list_skeleton_note), note)
        // 必须点出「每实例一份全尺寸遮罩」这个真正的内存代价；
        // 示例同时提供 values-en，测试环境下默认解析到英文，因此按本地化资源断言。
        assertTrue("说明文字缺少 ALPHA_8 遮罩说明：$note", note.contains("ALPHA_8"))
        assertTrue("说明文字过短，未讲清取舍：$note", note.length > 200)
    }
}
