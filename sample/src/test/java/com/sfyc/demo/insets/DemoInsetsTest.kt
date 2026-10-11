package com.sfyc.demo.insets

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sfyc.demo.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 沉浸式落点的行为锁：原有 padding 只做加法；开关可单独关闭某一方向。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoInsetsTest {

    private fun dispatchBars(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(left, top, right, bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun attachedView(): Pair<MainActivity, View> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val view = View(activity).apply { setPadding(16, 16, 16, 16) }
        (activity.findViewById<View>(android.R.id.content) as ViewGroup).addView(view)
        return activity to view
    }

    @Test fun `preserves initial padding and adds system bars`() {
        val (_, view) = attachedView()
        view.applyDemoSystemBarPadding()
        dispatchBars(view, left = 10, top = 48, right = 10, bottom = 24)
        assertEquals(26, view.paddingLeft)
        assertEquals(64, view.paddingTop)
        assertEquals(26, view.paddingRight)
        assertEquals(40, view.paddingBottom)
    }

    @Test fun `flags can disable directions independently`() {
        val (_, view) = attachedView()
        view.applyDemoSystemBarPadding(applyTop = false, applyBottom = true, applySides = false)
        dispatchBars(view, left = 10, top = 48, right = 10, bottom = 24)
        assertEquals(16, view.paddingLeft)
        assertEquals(16, view.paddingTop)
        assertEquals(16, view.paddingRight)
        assertEquals(40, view.paddingBottom)
    }
}
