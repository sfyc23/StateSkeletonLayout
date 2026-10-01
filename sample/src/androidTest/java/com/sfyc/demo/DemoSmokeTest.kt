package com.sfyc.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sfyc.demo.ssl.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 示例应用冒烟测试：首页列表可展示，点击条目可进入详情。
 *
 * 需连接设备或模拟器执行（见 README）；无设备时仅做编译验证。
 */
@RunWith(AndroidJUnit4::class)
class DemoSmokeTest {

    @Test
    fun 首页列表展示并可进入首个示例() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.demo_list)).check(matches(isDisplayed()))
            onView(withText("基础四态")).perform(click())
            onView(withId(R.id.demo_detail_container)).check(matches(isDisplayed()))
        }
    }
}
