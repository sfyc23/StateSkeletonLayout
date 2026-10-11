package com.sfyc.demo

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.sfyc.demo.ssl.R
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 实际设备验证本地解析与生命周期；使用条件等待，不将循环动画注册为忙碌资源。 */
@RunWith(AndroidJUnit4::class)
class SvgaLoadingSmokeTest {
    private fun openDemo() {
        onView(withId(R.id.demo_list)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
            override fun getDescription() = "滚动到 SVGA 加载示例"
            override fun perform(uiController: UiController, view: View) {
                val index = DemoCatalog.entries.indexOfFirst { it.title == "SVGA 加载" }
                assertTrue(index >= 0)
                (view as RecyclerView).scrollToPosition(index)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withText("SVGA 加载")).perform(click())
    }

    private fun player(state: StateSkeletonLayout): SVGAImageView? =
        state.getStateView(StateLayoutState.LOADING)?.findViewById(R.id.demo_svga_view)

    private fun await(description: String, predicate: (StateSkeletonLayout) -> Boolean) {
        onView(withId(R.id.demo_state)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(StateSkeletonLayout::class.java)
            override fun getDescription() = description
            override fun perform(uiController: UiController, view: View) {
                val state = view as StateSkeletonLayout
                val deadline = SystemClock.uptimeMillis() + 10000
                while (!predicate(state)) {
                    if (SystemClock.uptimeMillis() >= deadline) {
                        val summary = state.rootView.findViewById<android.widget.TextView>(R.id.demo_svga_status)?.text
                        throw AssertionError("$description 超时：${state.renderedState}，$summary")
                    }
                    uiController.loopMainThreadForAtLeast(16)
                }
            }
        })
    }

    private fun awaitPlaying() = await("等待本地 SVGA 解析并播放") {
        it.renderedState == StateLayoutState.LOADING && player(it)?.drawable is SVGADrawable && player(it)?.isAnimating == true
    }

    @Test fun 本地素材播放后切Content停止() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals(1, DemoCatalog.entries.count { entry -> entry.title == "SVGA 加载" })
            openDemo()
            awaitPlaying()
            onView(withId(R.id.demo_btn_content)).perform(scrollTo(), click())
            await("等待 Content 与 SVGA 停止") { state -> state.renderedState == StateLayoutState.CONTENT && player(state)?.isAnimating == false }
        }
    }

    @Test fun 持续Loading后台停止返回前台重新播放() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDemo()
            onView(withId(R.id.demo_btn_loading)).perform(scrollTo(), click())
            awaitPlaying()
            lateinit var original: SVGAImageView
            scenario.onActivity { original = player(it.findViewById(R.id.demo_state))!! }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertFalse(original.isAnimating) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPlaying()
            scenario.onActivity { assertSame(original, player(it.findViewById(R.id.demo_state))) }
        }
    }

    @Test fun 返回目录释放旧View重新进入可以播放() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDemo()
            awaitPlaying()
            lateinit var original: SVGAImageView
            scenario.onActivity { original = player(it.findViewById(R.id.demo_state))!! }
            pressBack()
            scenario.onActivity {
                assertFalse(original.isAnimating)
                assertNull(original.drawable)
            }
            openDemo()
            awaitPlaying()
            scenario.onActivity { assertNotSame(original, player(it.findViewById(R.id.demo_state))) }
        }
    }

    @Test fun 自动模拟七秒后显示Content() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openDemo()
            await("等待 7 秒模拟请求完成") { state -> state.renderedState == StateLayoutState.CONTENT && player(state)?.isAnimating == false }
        }
    }
}
