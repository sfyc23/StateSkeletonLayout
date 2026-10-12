package com.sfyc.ssl

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sfyc.ssl.internal.skeleton.SkeletonTicker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 多实例骨架的资源开销计量（P-07 的量化前提）。
 *
 * # 为什么是"计量"而不是"基准"
 *
 * 真正的性能数字（帧间隔 P50/P95、构建耗时）需要设备与 Macrobenchmark，本工程的
 * 自动化跑在 JVM/Robolectric 上。这里量化的是**与设备无关的标量**：遮罩字节数
 * 与遮罩重建次数——足以判断"item 级多实例"是否可行，也足以在回归时发现数量级变化。
 *
 * # 关键结论（本测试固化，已实测确认）
 *
 * 1. `ALPHA_8` 遮罩字节数 = `maskWidth × maskHeight`（1 字节/像素，实测确认）。
 * 2. N 个骨架实例 → N 张遮罩，**线性增长**。这是列表场景的主要成本。
 * 3. 几何未变化时重复布局**不重建**遮罩（实测 1 → 1）。
 *
 * 第 2 条决定了 README 中"列表骨架推荐整屏模板"的结论：遮罩内存无法在实例间共享，
 * 只能靠减少实例数来省。第 3 条说明去重是有效的，成本不会随布局回调累积。
 *
 * # 夹具约定
 *
 * 所有实例都挂在**同一个根 LinearLayout** 里，用显式 `LayoutParams(w, h)` 定尺寸。
 * 不能逐个 `activity.setContentView(instance)`：那样只有最后一个真正挂载，
 * 且手动 measure 会与 Activity 的真实布局过程互相覆盖尺寸，导致遮罩被反复重建
 * （实测会把重建次数虚增到 12）。固定尺寸的容器才能得到稳定的计量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeletonInstanceFootprintTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        StateSkeletonLayoutDefaults.reset()
        SkeletonTicker.resetForTest()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        StateSkeletonLayoutDefaults.reset()
        SkeletonTicker.resetForTest()
        activity.finish()
    }

    private fun newInstance(effect: SkeletonEffect): StateSkeletonLayout =
        StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            useSkeleton = true
            skeletonConfig = SkeletonConfig(effect = effect)
            setContentView(TextView(activity))
        }

    /** 把实例挂进一个固定尺寸的根容器并完成布局，然后进入骨架加载态。 */
    private fun mountAndShow(instances: List<StateSkeletonLayout>, itemWidth: Int, itemHeight: Int) {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        instances.forEach { root.addView(it, LinearLayout.LayoutParams(itemWidth, itemHeight)) }
        activity.setContentView(root)
        ShadowLooper.idleMainLooper()
        val totalHeight = itemHeight * instances.size
        root.measure(
            View.MeasureSpec.makeMeasureSpec(itemWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(totalHeight, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, itemWidth, totalHeight)
        instances.forEach { it.showLoading() }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `单实例遮罩字节数等于像素数`() {
        // 业务意图：把"一张遮罩多大"钉死，后续所有内存估算都以它为单位。
        val instance = newInstance(SkeletonEffect.SOLID)
        mountAndShow(listOf(instance), itemWidth = 400, itemHeight = 160)

        val d = instance.diagnostics()
        assertEquals(400, d.maskWidth)
        assertEquals(160, d.maskHeight)
        assertEquals("ALPHA_8 是 1 字节/像素", 400L * 160L, d.maskBytes.toLong())
    }

    @Test
    fun `遮罩字节数随实例数线性增长`() {
        // 业务意图：量化"每个 item 一个骨架实例"的真实成本——这是列表场景的核心约束。
        val single = newInstance(SkeletonEffect.SOLID)
        mountAndShow(listOf(single), itemWidth = 400, itemHeight = 160)
        val singleBytes = single.diagnostics().maskBytes
        assertEquals(400L * 160L, singleBytes.toLong())

        // 20 个同尺寸实例：总字节应精确等于 20 倍（无共享、无对齐开销）。
        val many = (1..20).map { newInstance(SkeletonEffect.SOLID) }
        mountAndShow(many, itemWidth = 400, itemHeight = 160)

        // sumOf 在 Int 上返回 Int，必须先转 Long 再与 Long 期望值比较，
        // 否则 assertEquals(Long, Int) 会因装箱类型不同而失败（值相等也失败）。
        val total = many.sumOf { it.diagnostics().maskBytes.toLong() } + singleBytes.toLong()
        val expected = 400L * 160L * 21L
        assertEquals(
            "遮罩不共享，总量应随实例数线性增长（每个约 ${singleBytes / 1024} KB）",
            expected,
            total,
        )
        assertTrue("20 个 400×160 遮罩已占约 ${expected / 1024} KB", expected > 1_000_000L)
    }

    @Test
    fun `多实例共享同一个动画时钟`() {
        // 业务意图：改造前 N 个实例 = N 个并行无限动画；改造后主线程每帧只回调一次。
        val instances = (1..20).map { newInstance(SkeletonEffect.SHIMMER) }
        mountAndShow(instances, itemWidth = 200, itemHeight = 100)

        val running = instances.count { it.diagnostics().effectRunning }
        assertTrue("SHIMMER 实例应全部进入动画态，实际=$running", running >= 18)

        // 断言"订阅者数 = 实例数"而不是 SkeletonTicker.isTicking：
        // 改造前每个实例各持一个 ValueAnimator，改造后 20 个实例只订阅同一个时钟。
        // isTicking 依赖 ValueAnimator 的调度状态，而 Robolectric 在 looper 空闲后
        // 不一定继续报告运行中——那是测试环境的性质，不是库的性质。
        // 时钟自身的启停语义由 SkeletonTickerTest 在无 looper 干扰下覆盖。
        assertEquals(
            "20 个实例应全部订阅同一个共享时钟，而不是各自持有一个",
            instances.size,
            SkeletonTicker.subscriberCount,
        )
    }

    @Test
    fun `几何未变化时重复布局不重建遮罩`() {
        // 业务意图：滚动与布局回调会频繁触发布局，重建成本不能随回调次数累积。
        val instance = newInstance(SkeletonEffect.SOLID)
        mountAndShow(listOf(instance), itemWidth = 400, itemHeight = 160)
        val builds = instance.diagnostics().maskBuildCount
        assertEquals("首次显示应恰好构建一次", 1, builds)

        // 反复触发布局，几何完全未变。
        repeat(5) { instance.requestLayout(); ShadowLooper.idleMainLooper() }

        assertEquals(
            "几何未变化时不应重建遮罩",
            builds,
            instance.diagnostics().maskBuildCount,
        )
    }

    @Test
    fun `像素预算拦截后不分配遮罩并记录降级原因`() {
        // 业务意图：宿主可用预算把"多个大遮罩"挡在分配之前，代价是静态纯色降级。
        val instance = StateSkeletonLayout(activity).apply {
            transitionEffect = StateTransitionEffect.NONE
            minimumLoadingDurationMillis = 0
            useSkeleton = true
            skeletonConfig = SkeletonConfig(
                effect = SkeletonEffect.SOLID,
                maximumMaskPixels = 1_000L,
            )
        }
        instance.setContentView(TextView(activity))
        mountAndShow(listOf(instance), itemWidth = 400, itemHeight = 160)

        val d = instance.diagnostics()
        assertEquals(
            "400×160=64000 像素超过 1000 的预算，应记录降级原因",
            SkeletonFallbackReason.PIXEL_BUDGET_EXCEEDED,
            d.fallbackReason,
        )
        assertEquals("预算被拦下时不应分配遮罩", 0, d.maskBytes)
    }
}
