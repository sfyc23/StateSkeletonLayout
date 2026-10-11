package com.sfyc.demo

import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.sfyc.demo.ssl.R
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 示例应用冒烟测试：目录可进入详情，返回后空容器不会遮挡目录。
 *
 * 需连接设备或模拟器执行（见 README）；无设备时仅做编译验证。
 */
@RunWith(AndroidJUnit4::class)
class DemoSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun openDemo(index: Int = 0) {
        onView(withId(R.id.demo_list)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
            override fun getDescription() = "滚动到示例目录第 $index 项"
            override fun perform(uiController: UiController, view: View) {
                (view as RecyclerView).scrollToPosition(index)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withText(DemoCatalog.entries[index].title)).perform(click())
    }

    private fun assertDetail(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val detail = activity.findViewById<ViewGroup>(R.id.demo_detail_container)
            assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
            assertEquals(1, detail.childCount)
            assertEquals(View.VISIBLE, detail.visibility)
        }
    }

    private fun assertHome(scenario: ActivityScenario<MainActivity>) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val detail = activity.findViewById<ViewGroup>(R.id.demo_detail_container)
            assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
            assertEquals(0, detail.childCount)
            // 列表可能仍被可见的空容器遮挡，不能只断言列表 isDisplayed。
            assertEquals(View.GONE, detail.visibility)
            assertTrue(activity.findViewById<View>(R.id.demo_list).isEnabled)
        }
        onView(withId(R.id.demo_list)).check(matches(isDisplayed()))
    }

    private fun startPredictiveBack(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)
            )
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(300f, 400f, 0.6f, BackEventCompat.EDGE_LEFT)
            )
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun 首页列表展示并可进入首个示例() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.demo_list)).check(matches(isDisplayed()))
            openDemo()
            onView(withId(R.id.demo_detail_container)).check(matches(isDisplayed()))
            assertDetail(scenario)
        }
    }

    @Test fun 全部示例返回后目录可继续点击() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            DemoCatalog.entries.indices.forEach { index ->
                openDemo(index)
                assertDetail(scenario)
                pressBack()
                assertHome(scenario)
            }
            openDemo()
            assertDetail(scenario)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun 取消预测性返回保留详情完成返回恢复目录() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDemo()
            startPredictiveBack(scenario)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            instrumentation.waitForIdleSync()
            assertDetail(scenario)
            startPredictiveBack(scenario)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertHome(scenario)
            openDemo()
            assertDetail(scenario)
        }
    }

    @Test fun 详情重建后返回目录并可再次进入() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDemo()
            assertDetail(scenario)
            scenario.recreate()
            assertDetail(scenario)
            pressBack()
            assertHome(scenario)
            openDemo()
            assertDetail(scenario)
        }
    }
}
