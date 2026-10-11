package com.sfyc.demo

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.ssl.R
import java.time.Duration
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

/** 回归首页导航：返回结束必须移除遮挡首页的空详情容器。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityNavigationTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val detail get() = activity.findViewById<ViewGroup>(R.id.demo_detail_container)

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        idle()
    }

    @After fun teardown() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() {
        // 推进布局帧，确保 View 已挂载，View.post 会进入主线程消息队列。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private fun openDemo() {
        val list = activity.findViewById<RecyclerView>(R.id.demo_list)
        val adapter = list.adapter as MainDemoAdapter
        val holder = adapter.createViewHolder(list, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        // JVM 中直接触发已绑定监听器，真实触摸点击由设备冒烟用例验证。
        requireNotNull(shadowOf(holder.itemView).onClickListener).onClick(holder.itemView)
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
        assertEquals(View.VISIBLE, detail.visibility)
        assertEquals(1, detail.childCount)
    }

    private fun startPredictiveBack() {
        activity.onBackPressedDispatcher.dispatchOnBackStarted(
            BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)
        )
        idle()
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            BackEventCompat(300f, 400f, 0.6f, BackEventCompat.EDGE_LEFT)
        )
        idle()
    }

    private fun completeBack() {
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
    }

    private fun assertHome() {
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
        assertEquals(0, detail.childCount)
        // 列表 isShown 不能证明没有遮挡，必须直接检查上层容器已隐藏。
        assertEquals(View.GONE, detail.visibility)
        assertTrue(activity.findViewById<View>(R.id.demo_list).isShown)
    }

    @Test fun regularBackReturnsHomeAndAllowsReentry() {
        openDemo()
        completeBack()
        assertHome()
        openDemo()
    }

    @Test fun predictiveBackReturnsHomeAndAllowsReentry() {
        openDemo()
        startPredictiveBack()
        completeBack()
        assertHome()
        openDemo()
    }

    @Test fun cancelledPredictiveBackPreservesDetailThenCanReturnHome() {
        openDemo()
        startPredictiveBack()
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        idle()
        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
        assertEquals(1, detail.childCount)
        assertEquals(View.VISIBLE, detail.visibility)
        startPredictiveBack()
        completeBack()
        assertHome()
    }

    @Test fun homeRemainsVisibleAfterBackgroundAndForeground() {
        openDemo()
        startPredictiveBack()
        completeBack()
        assertHome()
        controller.pause().stop()
        controller.start().resume().visible()
        idle()
        assertHome()
        openDemo()
    }
}
