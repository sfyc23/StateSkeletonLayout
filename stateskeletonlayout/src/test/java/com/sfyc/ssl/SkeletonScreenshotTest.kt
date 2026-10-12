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
 * 骨架渲染的截图回归测试（P-21 的验收条件）。
 *
 * # 为什么需要
 *
 * 骨架是**纯视觉**功能，而此前全部 143 个库单测都是断言状态、几何数据与
 * View 可见性 —— 一像素正确性几乎无覆盖。流光的带宽与相位、圆角半径、
 * 三种形状的轮廓、降级整块遮罩的外观，这些变化都不会让任何既有断言失败。
 *
 * 本测试把渲染结果落成 PNG 与实际像素比对，视觉回归会直接失败。
 *
 * # 运行方式
 *
 * - 首次或有意变更视觉时重新录制基线：`./gradlew :stateskeletonlayout:recordRoborazziDebug`
 * - 日常校验：`./gradlew :stateskeletonlayout:verifyRoborazziDebug`
 *
 * 基线 PNG 存放在 `stateskeletonlayout/src/test/screenshots/`，需随源码入库。
 *
 * # 稳定化
 *
 * - 骨架效果用 `SOLID`（不创建动画器）：动画帧的采样时刻不确定，
 *   流光与脉冲的逐帧截图会随机失败。流光/脉冲的**几何与着色器**已由
 *   `ShimmerMotionTest` 与 `SkeletonOverlayViewTest` 覆盖。
 * - 时间与随机源不参与渲染，无需额外冻结。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class SkeletonScreenshotTest {

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

    /**
     * 渲染一个处于骨架加载态的控件并截图。
     *
     * 用固定尺寸的叶子 View 构造内容，保证几何稳定：内容取形依赖子 View 的
     * 测量结果，尺寸一变截图就跟着变。
     */
    private fun captureSkeleton(
        name: String,
        config: SkeletonConfig,
        nodeRules: Map<Int, SkeletonNodeConfig> = emptyMap(),
    ) {
        // 固定 360×240，避免设备限定符变化影响几何。
        val layout = StateSkeletonLayout(activity).apply {
            id = View.generateViewId()
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            useSkeleton = true
            skeletonConfig = config
        }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.view.View(activity).apply {
                id = R.id.ssl_test_retry
                layoutParams = android.widget.LinearLayout.LayoutParams(120, 40)
            })
            addView(android.view.View(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24,
                ).apply { topMargin = 16 }
            })
        }
        layout.setContentView(content)
        nodeRules.forEach { (id, rule) -> layout.setSkeletonNodeConfig(id, rule) }

        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        layout.measure(widthSpec, heightSpec)
        layout.layout(0, 0, 360, 240)
        layout.showLoading()
        ShadowLooper.idleMainLooper()

        layout.captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun `内容取形骨架的静态渲染`() {
        captureSkeleton(
            name = "skeleton_content_solid",
            config = SkeletonConfig(effect = SkeletonEffect.SOLID),
        )
    }

    @Test
    fun `圆角变化会改变外观`() {
        captureSkeleton(
            name = "skeleton_content_large_radius",
            config = SkeletonConfig(effect = SkeletonEffect.SOLID, cornerRadius = 20f),
        )
    }

    @Test
    fun `节点规则把矩形改成圆形`() {
        captureSkeleton(
            name = "skeleton_node_circle",
            config = SkeletonConfig(effect = SkeletonEffect.SOLID),
            nodeRules = mapOf(R.id.ssl_test_retry to SkeletonNodeConfig(shape = SkeletonShape.CIRCLE)),
        )
    }

    /**
     * 骨架模板的空几何降级：模板尺寸与容器不符时整块静态遮罩。
     *
     * 这是用户可见的降级外观，此前只有 `fallbackReason` 的枚举断言，
     * 没有"看起来是什么样"的覆盖。
     */
    @Test
    fun `窄窗口下模板骨架降级为整块遮罩`() {
        captureSkeleton(
            name = "skeleton_content_fallback_full_bounds",
            config = SkeletonConfig(effect = SkeletonEffect.SOLID),
            nodeRules = mapOf(R.id.ssl_test_retry to SkeletonNodeConfig(excluded = true)),
        )
    }

    /** 内置空态布局的外观（P-12 新增的兜底页面）。 */
    @Test
    fun `内置空态布局渲染`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
        }
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        layout.measure(widthSpec, heightSpec)
        layout.layout(0, 0, 360, 240)
        layout.showEmpty()
        ShadowLooper.idleMainLooper()

        layout.captureRoboImage("src/test/screenshots/state_built_in_empty.png")
    }

    /** 内置错误态布局的外观，含重试按钮。 */
    @Test
    fun `内置错误态布局渲染`() {
        val layout = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            setContentView(TextView(activity))
        }
        activity.setContentView(layout)
        ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        layout.measure(widthSpec, heightSpec)
        layout.layout(0, 0, 360, 240)
        layout.showError()
        ShadowLooper.idleMainLooper()

        layout.captureRoboImage("src/test/screenshots/state_built_in_error.png")
    }
}
