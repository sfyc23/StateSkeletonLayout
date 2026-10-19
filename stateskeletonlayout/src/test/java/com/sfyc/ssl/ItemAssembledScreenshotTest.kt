package com.sfyc.ssl

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * T2 条目拼装模板的截图回归测试。
 *
 * # 为什么需要
 *
 * 几何断言（[ItemAssembledTemplateTest]）只能证明"形状存在且数量对"，
 * 证明不了"画出来长什么样"。T2 的整条链路（离屏 inflate → binder 填充 →
 * 几何采集 → 遮罩绘制）任何一环退化（例如 binder 未执行导致文本行消失），
 * 几何数可能仍然 > 0（头像还在），只有像素比对能抓住。
 *
 * # 稳定化（与 SkeletonScreenshotTest 一致）
 *
 * - 骨架效果用 `SOLID`：流光 / 脉冲的动画帧采样时刻不确定，逐帧截图会随机失败。
 * - 两张基线各自比对（不互比）：T1 手写模板是固定色块，T2 是真实文本字形，
 *   像素天然不同；回归要抓的是"各自形状意外变化"，阈值与既有 6 张基线一致
 *   （Roborazzi 默认阈值，不额外放宽）。
 *
 * # 运行方式
 *
 * - 有意变更视觉时重新录制基线：`./gradlew :stateskeletonlayout:recordRoborazziDebug`
 * - 日常校验：`./gradlew :stateskeletonlayout:verifyRoborazziDebug`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ItemAssembledScreenshotTest {

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

    private fun captureLoading(name: String, bind: StateSkeletonLayout.() -> Unit) {
        // 固定 360×640：8 行在 Pixel5 限定符下只露出顶部若干行，
        // 回归要抓的是顶部形状，溢出裁剪本身也是被测行为。
        val layout = StateSkeletonLayout(activity).apply {
            id = View.generateViewId()
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            useSkeleton = true
            skeletonConfig = SkeletonConfig(
                source = SkeletonSource.TEMPLATE,
                effect = SkeletonEffect.SOLID,
            )
        }
        layout.setContentView(TextView(activity))
        bind(layout)

        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
        layout.measure(widthSpec, heightSpec)
        layout.layout(0, 0, 360, 640)
        layout.showLoading()
        ShadowLooper.idleMainLooper()

        layout.captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun `手写镜像模板渲染`() {
        captureLoading(name = "skeleton_t1_item_rows") {
            skeletonConfig = skeletonConfig.copy(
                templateLayoutResId = R.layout.ssl_test_item_rows_template,
            )
        }
    }

    @Test
    fun `条目复用成功布局加占位绑定渲染`() {
        captureLoading(name = "skeleton_t2_item_reuse") {
            setSkeletonItemTemplate(R.layout.ssl_test_item_row, 8) { item, _ ->
                item.findViewById<TextView>(R.id.ssl_test_item_title)?.text = "占位标题占位标题"
                item.findViewById<TextView>(R.id.ssl_test_item_subtitle)?.text = "占位副标题行"
            }
        }
    }
}
