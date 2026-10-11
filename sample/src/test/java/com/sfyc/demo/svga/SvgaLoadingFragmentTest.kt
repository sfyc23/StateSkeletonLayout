package com.sfyc.demo.svga

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import com.sfyc.demo.MainActivity
import com.sfyc.demo.ssl.R
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** 状态和迟到结果使用可控解析回调；素材绘制另用真实解析器与 Native Canvas 验证。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class SvgaLoadingFragmentTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var fragment: SvgaLoadingFragment
    private val completions = mutableListOf<SVGAParser.ParseCompletion>()
    private val activity get() = controller.get()
    private val state get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.demo_state)
    private val player get() = state.getStateView(StateLayoutState.LOADING)!!.findViewById<SVGAImageView>(R.id.demo_svga_view)
    private val status get() = fragment.requireView().findViewById<TextView>(R.id.demo_svga_status).text.toString()

    @Before fun setup() {
        // 循环动画需要非零虚拟帧间隔，否则 idleFor 会持续执行同一时刻的新帧。
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = SvgaLoadingFragment().apply { parseAsset = { _, completion -> completions += completion } }
        activity.findViewById<View>(R.id.demo_detail_container).visibility = View.VISIBLE
        activity.supportFragmentManager.beginTransaction().replace(R.id.demo_detail_container, fragment).commitNow()
        frame()
    }

    @After fun teardown() {
        controller.pause().stop().destroy()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun frame() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        decor.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun settle() = repeat(30) { frame() }
    private fun click(id: Int) { fragment.requireView().findViewById<View>(id).performClick(); frame() }
    private fun video(fps: Int = 15, frames: Int = 90) = SVGAVideoEntity(
        JSONObject("""{"movie":{"viewBox":{"width":375,"height":667},"fps":$fps,"frames":$frames},"images":{},"sprites":[]}"""),
        activity.cacheDir,
    )

    // 固定版本没有公开资源释放查询；检查实体的图像映射，证明迟到结果确实被清理。
    @Suppress("UNCHECKED_CAST")
    private fun imageMap(video: SVGAVideoEntity): MutableMap<String, Bitmap> =
        video.javaClass.getDeclaredField("imageMap").apply { isAccessible = true }.get(video) as MutableMap<String, Bitmap>

    @Test fun `模拟请求在7000毫秒后完成不等待动画解析`() {
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        scheduler.advanceTimeBy(6999)
        scheduler.runCurrent()
        assertEquals(StateLayoutState.LOADING, state.requestedState)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        settle()
        assertEquals(StateLayoutState.CONTENT, state.renderedState)
    }

    @Test fun `手动切换取消旧模拟任务持续Loading不自动结束`() {
        click(R.id.demo_btn_empty)
        scheduler.advanceTimeBy(8000)
        scheduler.runCurrent()
        settle()
        assertEquals(StateLayoutState.EMPTY, state.renderedState)
        click(R.id.demo_btn_loading)
        scheduler.advanceTimeBy(14000)
        scheduler.runCurrent()
        settle()
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertEquals(1, completions.size)
    }

    @Test fun `解析失败保持Loading每次显式重试只发起一次解析`() {
        completions.single().onError()
        frame()
        assertEquals(StateLayoutState.LOADING, state.renderedState)
        assertTrue(status.contains(activity.getString(R.string.demo_svga_failed)))
        assertEquals(View.VISIBLE, state.getStateView(StateLayoutState.LOADING)!!.findViewById<ProgressBar>(R.id.demo_svga_progress).visibility)
        click(R.id.demo_btn_loading)
        repeat(4) { click(R.id.demo_btn_loading) }
        assertEquals(2, completions.size)
        completions.last().onComplete(video())
        assertTrue(status.contains(activity.getString(R.string.demo_svga_ready)))
    }

    @Test fun `隐藏时解析完成不播放重新Loading复用实体`() {
        click(R.id.demo_btn_content)
        settle()
        val entity = video()
        completions.single().onComplete(entity)
        assertFalse(player.isAnimating)
        assertSame(entity, (player.drawable as SVGADrawable).videoItem)
        click(R.id.demo_btn_loading)
        assertTrue(player.isAnimating)
        assertEquals(1, completions.size)
    }

    @Test fun `解码后参数无效清理实体并保留失败反馈不阻塞业务完成`() {
        val invalidVideos = listOf(video(fps = 0), video(frames = 0), video(frames = Int.MAX_VALUE))
        invalidVideos.forEachIndexed { index, entity ->
            if (index > 0) click(R.id.demo_btn_loading)
            val images = imageMap(entity)
            images["probe"] = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            completions.last().onComplete(entity)
            assertTrue(images.isEmpty())
            assertNull(player.drawable)
            assertEquals(StateLayoutState.LOADING, state.renderedState)
            assertTrue(status.contains(activity.getString(R.string.demo_svga_failed)))
        }
        click(R.id.demo_btn_replay)
        scheduler.advanceTimeBy(7000)
        scheduler.runCurrent()
        settle()
        assertEquals(StateLayoutState.CONTENT, state.renderedState)
    }

    @Test fun `后台停止前台恢复销毁后清理Drawable`() {
        completions.single().onComplete(video())
        click(R.id.demo_btn_loading)
        val original = player
        assertTrue(original.isAnimating)
        controller.pause().stop()
        assertFalse(original.isAnimating)
        controller.start().resume().visible()
        frame()
        assertTrue(original.isAnimating)
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        assertFalse(original.isAnimating)
        assertNull(original.drawable)
    }

    @Test fun `同一个Fragment重建View后迟到成功清理实体失败不污染新实例`() {
        val oldCompletion = completions.single()
        val original = player
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        frame()
        assertEquals(2, completions.size)
        assertNotSame(original, player)
        val lateVideo = video()
        val lateImages = imageMap(lateVideo)
        lateImages["probe"] = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        oldCompletion.onComplete(lateVideo)
        oldCompletion.onError()
        assertTrue(lateImages.isEmpty())
        assertNull(original.drawable)
        assertTrue(status.contains(activity.getString(R.string.demo_svga_parsing)))
        completions.last().onComplete(video())
        assertTrue(status.contains(activity.getString(R.string.demo_svga_ready)))
    }

    @Test fun `系统动画关闭显示静态文案仍能完成模拟请求`() {
        val previous = ReflectionHelpers.callStaticMethod<Float>(ValueAnimator::class.java, "getDurationScale")
        fun setScale(scale: Float) = ReflectionHelpers.callStaticMethod<Void>(
            ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType!!, scale),
        )
        try {
            setScale(0f)
            completions.single().onComplete(video())
            click(R.id.demo_btn_replay)
            assertFalse(player.isAnimating)
            assertEquals(View.GONE, state.getStateView(StateLayoutState.LOADING)!!.findViewById<View>(R.id.demo_svga_frame).visibility)
            assertTrue(status.contains(activity.getString(R.string.demo_svga_static)))
            assertEquals(0f, ReflectionHelpers.callStaticMethod<Float>(ValueAnimator::class.java, "getDurationScale"), 0f)
            scheduler.advanceTimeBy(7000)
            scheduler.runCurrent()
            settle()
            assertEquals(StateLayoutState.CONTENT, state.renderedState)
        } finally { setScale(previous) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `真实loading素材可解析且NativeCanvas绘制不同帧`() {
        completions.single().onError()
        fragment.parseAsset = { context, completion -> SVGAParser(context).decodeFromAssets("loading.svga", completion) }
        click(R.id.demo_btn_loading)
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (player.drawable !is SVGADrawable && System.nanoTime() < deadline) {
            Thread.sleep(10)
            frame()
        }
        assertTrue("真实解析未完成：$status", player.drawable is SVGADrawable)
        val entity = (player.drawable as SVGADrawable).videoItem
        assertEquals(90, entity.frames)
        assertEquals(15, entity.FPS)
        assertEquals(18, imageMap(entity).size)
        fun draw(frame: Int): Bitmap {
            player.stepToFrame(frame, false)
            return Bitmap.createBitmap(player.width, player.height, Bitmap.Config.ARGB_8888).also { player.draw(Canvas(it)) }
        }
        val first = draw(0)
        val second = draw(45)
        val firstPixels = IntArray(first.width * first.height)
        val secondPixels = IntArray(second.width * second.height)
        first.getPixels(firstPixels, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(secondPixels, 0, second.width, 0, 0, second.width, second.height)
        assertTrue("真实素材没有绘制像素", secondPixels.any { it ushr 24 != 0 })
        assertFalse("动画两个采样帧没有变化", firstPixels.contentEquals(secondPixels))
        val output = File("build/svga-preview/loading-frame.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { second.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
