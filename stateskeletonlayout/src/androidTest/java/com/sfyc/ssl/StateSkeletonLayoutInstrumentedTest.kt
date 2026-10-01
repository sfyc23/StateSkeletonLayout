package com.sfyc.ssl

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sfyc.ssl.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 / 模拟器 instrumentation 测试（对应实现方案第 24.3 节）。
 *
 * 覆盖在 JVM 上无法验证的行为：动画器生命周期、触摸拦截、
 * 无障碍节点隔离、detach/attach 恢复。运行命令见 README；
 * 无连接设备时仅做编译验证（`assembleDebugAndroidTest`）。
 */
@RunWith(AndroidJUnit4::class)
class StateSkeletonLayoutInstrumentedTest {

    private fun buildLayout(
        scenario: ActivityScenario<TestHarnessActivity>,
        block: (TestHarnessActivity, StateSkeletonLayout) -> Unit = { _, _ -> },
    ): StateSkeletonLayout {
        lateinit var layout: StateSkeletonLayout
        scenario.onActivity { activity ->
            layout = StateSkeletonLayout(activity)
            layout.minimumLoadingDurationMillis = 0L
            layout.transitionEffect = StateTransitionEffect.NONE
            layout.setContentView(TextView(activity).apply { text = "content" })
            block(activity, layout)
            activity.setContentView(
                layout,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return layout
    }

    // 同步完成一次布局，保证遮罩可同步构建。
    // 说明：首帧遍历是异步帧回调，直接断言会有竞态；手动 measure/layout 后，
    // 渲染器的 ensureOverlaySize 能拿到父容器尺寸，遮罩同步构建、同步可见。
    // 禁止用 Espresso 空闲同步等待骨架就绪——流光动画常驻导致主线程永不空闲；
    // 也禁止在 onActivity（主线程）内阻塞等待——与投递任务互锁。
    private fun layoutSynchronously(activity: TestHarnessActivity) {
        val root = activity.findViewById<View>(android.R.id.content)
        val metrics = activity.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun capturePixels(view: View, width: Int = 120, height: Int = 200): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / view.width.toFloat(), height / view.height.toFloat())
        view.draw(canvas)
        return IntArray(width * height).also { pixels ->
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
        }
    }

    @Test
    fun 四态可重复稳定切换() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario) { _, host ->
                host.setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
                host.setStateViewLayout(StateLayoutState.ERROR, R.layout.ssl_test_state_simple)
            }
            scenario.onActivity {
                layout.render(StateLayoutState.LOADING)
                assertEquals(StateLayoutState.LOADING, layout.renderedState)
                layout.render(StateLayoutState.EMPTY)
                assertEquals(StateLayoutState.EMPTY, layout.renderedState)
                layout.render(StateLayoutState.ERROR)
                assertEquals(StateLayoutState.ERROR, layout.renderedState)
                layout.render(StateLayoutState.CONTENT)
                assertEquals(StateLayoutState.CONTENT, layout.renderedState)
                // 重复切换保持稳定。
                repeat(3) {
                    layout.render(StateLayoutState.LOADING)
                    layout.render(StateLayoutState.CONTENT)
                }
                assertEquals(StateLayoutState.CONTENT, layout.renderedState)
            }
        }
    }

    @Test
    fun 骨架层可见并拦截触摸不穿透() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            scenario.onActivity { activity ->
                layoutSynchronously(activity)
                layout.render(StateLayoutState.LOADING)
                // 遮罩层是根容器的第三个内部子 View（内容/状态/骨架）。
                val overlay = layout.getChildAt(2)
                val parentView = overlay.parent as View
                assertTrue(
                    "骨架显示后遮罩层应可见：" +
                        "vis=${overlay.visibility} shown=${overlay.isShown} " +
                        "size=${overlay.width}x${overlay.height} " +
                        "parent=${parentView.width}x${parentView.height} " +
                        "attached=${overlay.isAttachedToWindow} " +
                        "rendered=${layout.renderedState}",
                    overlay.isShown,
                )
                val down = MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    100f,
                    100f,
                    0,
                )
                try {
                    assertTrue("遮罩层应消费触摸，不穿透到业务内容", overlay.dispatchTouchEvent(down))
                } finally {
                    down.recycle()
                }
            }
        }
    }

    @Test
    fun 流光相邻帧产生真实像素变化() {
        assumeTrue("系统动画关闭时流光按设计降级为静态", ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            lateinit var first: IntArray
            scenario.onActivity { activity ->
                layoutSynchronously(activity)
                layout.render(StateLayoutState.LOADING)
                first = capturePixels(layout.getChildAt(2))
            }

            SystemClock.sleep(250L)

            lateinit var second: IntArray
            scenario.onActivity {
                second = capturePixels(layout.getChildAt(2))
            }
            val changedPixels = first.indices.count { first[it] != second[it] }
            assertTrue("流光动画运行时相邻帧应有像素变化，实际=$changedPixels", changedPixels > 0)
        }
    }

    @Test
    fun 加载态隐藏业务内容的无障碍节点() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            scenario.onActivity {
                layout.render(StateLayoutState.LOADING)
                val contentHost = layout.getChildAt(0)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    contentHost.importantForAccessibility,
                )
                layout.render(StateLayoutState.CONTENT)
                assertTrue(
                    contentHost.importantForAccessibility !=
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                )
            }
        }
    }

    @Test
    fun detach后无悬挂任务且attach后恢复() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            scenario.onActivity {
                layout.render(StateLayoutState.LOADING)
                val parent = layout.parent as FrameLayout
                parent.removeView(layout)
                // detach 后只记录最新意图，不调度呈现或延迟任务。
                layout.render(StateLayoutState.CONTENT)
                assertEquals(StateLayoutState.CONTENT, layout.requestedState)
                assertEquals(StateLayoutState.LOADING, layout.renderedState)
                parent.addView(layout)
                assertEquals(StateLayoutState.CONTENT, layout.renderedState)
                layout.render(StateLayoutState.LOADING)
                assertEquals(StateLayoutState.LOADING, layout.renderedState)
            }
        }
    }

    @Test fun 自定义Loading切换到骨架后实际显示且运行效果() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario) { _, host ->
                host.setStateViewLayout(StateLayoutState.LOADING, R.layout.ssl_test_loading_simple)
            }
            scenario.onActivity { activity ->
                layoutSynchronously(activity)
                layout.showLoading()
                assertFalse(layout.diagnostics().effectiveUseSkeleton)
                layout.useSkeleton = true
                assertTrue(layout.getChildAt(2).isShown)
                assertTrue(layout.diagnostics().maskBytes > 0)
                assertEquals(StateLayoutState.LOADING, layout.renderedState)
            }
        }
    }

    @Test fun 固定模板几何在容器变宽后自动重建对应尺寸遮罩() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            var before = 0
            scenario.onActivity { activity ->
                layoutSynchronously(activity)
                layout.skeletonConfig = layout.skeletonConfig.copy(source = SkeletonSource.TEMPLATE,
                    templateLayoutResId = R.layout.ssl_test_template_fixed, effect = SkeletonEffect.SOLID)
                layout.showLoading()
                before = layout.diagnostics().maskBuildCount
                layout.layoutParams = FrameLayout.LayoutParams(200, 180)
                layoutSynchronously(activity)
            }
            // 等待下一帧的合并刷新，等待发生在测试线程，不阻塞主线程。
            SystemClock.sleep(80)
            scenario.onActivity {
                val diagnostics = layout.diagnostics()
                assertEquals(200, diagnostics.maskWidth)
                assertEquals(180, diagnostics.maskHeight)
                assertEquals(before + 1, diagnostics.maskBuildCount)
                assertTrue(layout.getChildAt(2).isShown)
            }
        }
    }

    @Test fun 聚合不可见停动画并通知宿主重见复用遮罩() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            val layout = buildLayout(scenario)
            scenario.onActivity { activity ->
                layoutSynchronously(activity)
                val events = mutableListOf<Boolean>()
                layout.setOnLoadingVisibilityChangedListener { _, visible, _ -> events += visible }
                layout.showLoading()
                val builds = layout.diagnostics().maskBuildCount
                layout.visibility = View.INVISIBLE
                assertFalse(layout.diagnostics().effectRunning)
                assertTrue(layout.diagnostics().maskBytes > 0)
                layout.visibility = View.VISIBLE
                assertEquals(listOf(true, false, true), events)
                assertEquals(builds, layout.diagnostics().maskBuildCount)
            }
        }
    }

    @Test fun 淡出容器拦截触摸而业务按钮属性更新不会被恢复覆盖() {
        ActivityScenario.launch(TestHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var clicks = 0
                val button = android.widget.Button(activity).apply { text = "content"; setOnClickListener { clicks++ } }
                val layout = StateSkeletonLayout(activity).apply {
                    setContentView(button)
                    setStateViewLayout(StateLayoutState.EMPTY, R.layout.ssl_test_state_simple)
                    transitionDurationMillis = 1000
                }
                activity.setContentView(layout)
                layoutSynchronously(activity)
                layout.showEmpty()
                val outgoing = layout.getChildAt(0)
                assertTrue(button.isEnabled)
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
                val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 20f, 20f, 0)
                try {
                    assertTrue(outgoing.dispatchTouchEvent(down))
                    assertTrue(outgoing.dispatchTouchEvent(up))
                } finally { down.recycle(); up.recycle() }
                assertEquals(0, clicks)
                button.isEnabled = false
                layout.showContent()
                assertFalse(button.isEnabled)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    layout.setStateDescription(StateLayoutState.EMPTY, "暂时无记录")
                    layout.showEmpty()
                    val node = layout.createAccessibilityNodeInfo()
                    assertEquals("暂时无记录", node.stateDescription)
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }
    }
}
