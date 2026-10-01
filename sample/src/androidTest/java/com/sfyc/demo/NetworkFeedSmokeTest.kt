package com.sfyc.demo

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airbnb.epoxy.EpoxyRecyclerView
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.demo.network.CompletionStatus
import com.sfyc.demo.network.FeedDemoFragment
import com.sfyc.demo.network.FeedPageState
import com.sfyc.demo.ssl.R
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.scwang.smart.refresh.layout.constant.RefreshState
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkFeedSmokeTest {
    @Test fun 网络示例刷新三页与首屏分页重试() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.demo_list)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
                override fun getDescription() = "滚动到网络多类型示例"
                override fun perform(uiController: UiController, view: View) {
                    (view as RecyclerView).scrollToPosition(DemoCatalog.entries.indexOfFirst { it.title == "网络多类型" })
                    uiController.loopMainThreadUntilIdle()
                }
            })
            onView(withText("网络多类型")).perform(click())
            lateinit var fragment: FeedDemoFragment
            scenario.onActivity { fragment = it.supportFragmentManager.findFragmentById(R.id.demo_detail_container) as FeedDemoFragment }
            await(fragment, 10)
            onView(withId(R.id.latency_100)).perform(scrollTo(), click())
            val firstRequest = fragment.viewModel.uiState.value.lastCompletion!!.token.requestId
            scenario.onActivity {
                assertTrue(fragment.requireView().findViewById<SmartRefreshLayout>(R.id.feed).autoRefresh())
            }
            await(fragment, 10, minimumRequest = firstRequest + 1)
            onView(withId(R.id.load_next)).perform(scrollTo(), click())
            await(fragment, 20)
            onView(withId(R.id.load_next)).perform(scrollTo(), click())
            await(fragment, 27)
            scenario.onActivity {
                assertNull(fragment.viewModel.uiState.value.nextPage)
                assertEquals(30, fragment.requireView().findViewById<EpoxyRecyclerView>(R.id.epoxy_recycler).adapter!!.itemCount)
            }

            chooseScenario("首屏失败一次")
            await(fragment, 0, target = FeedPageState.ERROR, completion = CompletionStatus.FAILURE)
            onView(withId(R.id.demo_retry)).perform(click())
            await(fragment, 10)

            chooseScenario("第二页失败一次")
            await(fragment, 10)
            val initial = fragment.viewModel.uiState.value.lastCompletion!!.token.requestId
            onView(withId(R.id.load_next)).perform(scrollTo(), click())
            await(fragment, 10, minimumRequest = initial + 1, completion = CompletionStatus.FAILURE)
            scenario.onActivity { assertEquals(2, fragment.viewModel.uiState.value.nextPage) }
            onView(withId(R.id.load_next)).perform(scrollTo(), click())
            await(fragment, 20)
        }
    }

    private fun chooseScenario(title: String) {
        onView(withId(R.id.scenario_spinner)).perform(scrollTo(), click())
        onView(withText(title)).inRoot(isDialog()).perform(click())
    }

    private fun await(fragment: FeedDemoFragment, count: Int, target: FeedPageState = FeedPageState.CONTENT,
        minimumRequest: Long = 0, completion: CompletionStatus = CompletionStatus.SUCCESS,
    ) {
        val resource = FeedIdleResource(fragment, count, target, minimumRequest, completion)
        IdlingRegistry.getInstance().register(resource)
        try { onView(withId(R.id.state_layout)).check { _, failure -> if (failure != null) throw failure } }
        finally { IdlingRegistry.getInstance().unregister(resource); resource.close() }
    }

    /** 按业务、模型、四态和容器空闲条件等待，不把固定睡眠当作请求完成。 */
    private class FeedIdleResource(
        private val fragment: FeedDemoFragment,
        private val count: Int,
        private val target: FeedPageState,
        private val minimumRequest: Long,
        private val completion: CompletionStatus,
    ) : IdlingResource {
        private val handler = Handler(Looper.getMainLooper())
        private var callback: IdlingResource.ResourceCallback? = null
        private var closed = false
        private val poll = object : Runnable {
            override fun run() {
                if (closed) return
                if (isIdleNow) callback?.onTransitionToIdle() else handler.postDelayed(this, 16L)
            }
        }
        override fun getName() = "网络示例 $target 业务=$count 请求≥$minimumRequest"
        override fun isIdleNow(): Boolean {
            val view = fragment.view ?: return false
            val ui = fragment.viewModel.uiState.value
            val state = view.findViewById<StateSkeletonLayout>(R.id.state_layout)
            val rendered = when (target) {
                FeedPageState.CONTENT -> StateLayoutState.CONTENT
                FeedPageState.ERROR -> StateLayoutState.ERROR
                FeedPageState.EMPTY -> StateLayoutState.EMPTY
                FeedPageState.LOADING -> StateLayoutState.LOADING
            }
            return !ui.isBusy && ui.pageState == target && ui.recordCount == count &&
                ui.lastCompletion?.status == completion && (ui.lastCompletion?.token?.requestId ?: -1) >= minimumRequest &&
                state.renderedState == rendered && view.findViewById<SmartRefreshLayout>(R.id.feed).state == RefreshState.None
        }
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback) {
            this.callback = callback
            handler.post(poll)
        }
        fun close() { closed = true; handler.removeCallbacks(poll); callback = null }
    }
}
