package com.sfyc.demo.network

import android.os.Looper
import android.view.View
import android.widget.Spinner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.epoxy.EpoxyRecyclerView
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.demo.MainActivity
import com.sfyc.demo.ssl.R
import com.sfyc.demo.network.models.CarouselScrollStore
import com.sfyc.demo.network.models.HCarouselView
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.scwang.smart.refresh.layout.constant.RefreshState
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class FeedRenderingTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var fragment: FeedDemoFragment
    private val activity get() = activityController.get()
    private val recycler get() = fragment.requireView().findViewById<EpoxyRecyclerView>(R.id.epoxy_recycler)
    private val stateLayout get() = fragment.requireView().findViewById<StateSkeletonLayout>(R.id.state_layout)
    private val refresh get() = fragment.requireView().findViewById<SmartRefreshLayout>(R.id.feed)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        fragment = FeedDemoFragment()
        activity.findViewById<View>(R.id.demo_detail_container).visibility = View.VISIBLE
        activity.supportFragmentManager.beginTransaction().replace(R.id.demo_detail_container, fragment, "feed")
            .addToBackStack("网络多类型").commit()
        activity.supportFragmentManager.executePendingTransactions()
        frame()
    }

    @After fun teardown() {
        activityController.pause().stop().destroy()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun frame() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 1920)
        decor.viewTreeObserver.dispatchOnPreDraw()
        scheduler.runCurrent()
    }

    private fun settle() {
        scheduler.advanceUntilIdle()
        repeat(70) { frame() }
    }

    @Test fun `setData生成六类型唯一ID Grid仅Tile半行`() {
        settle()
        val adapter = recycler.adapter!!
        assertEquals(13, adapter.itemCount)
        val ids = (0 until adapter.itemCount).map(adapter::getItemId)
        assertEquals(13, ids.distinct().size)
        assertEquals(6, (0 until adapter.itemCount).map(adapter::getItemViewType).distinct().size)
        fragment.viewModel.setLayoutMode(FeedLayoutMode.GRID)
        settle()
        assertSame(adapter, recycler.adapter)
        val layout = recycler.layoutManager as GridLayoutManager
        assertEquals(2, layout.spanCount)
        fragment.viewModel.uiState.value.items.forEachIndexed { index, item ->
            assertEquals(if (item is FeedItem.Tile) 1 else 2, layout.spanSizeLookup.getSpanSize(index))
        }
        assertEquals(1L, fragment.viewModel.uiState.value.lastCompletion!!.token.requestId)
    }

    @Test fun `Loading保持Adapter和内容 模型提交前不开启手势`() {
        val adapter = recycler.adapter
        assertFalse(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
        settle()
        assertEquals(StateLayoutState.CONTENT, stateLayout.renderedState)
        fragment.viewModel.setLatency(1500)
        fragment.viewModel.replay()
        frame()
        assertSame(adapter, recycler.adapter)
        assertEquals(13, recycler.adapter!!.itemCount)
        assertFalse(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
        settle()
        assertSame(adapter, recycler.adapter)
        assertTrue(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
    }

    @Test fun `旧模型回调不能把新场景Loading变成Content`() {
        settle()
        fragment.viewModel.refresh()
        scheduler.advanceUntilIdle()
        // 新数据已到达但 Adapter diff 尚未在主 looper 提交，立即切新场景。
        fragment.viewModel.selectScenario(FeedScenario.FIRST_ERROR)
        scheduler.runCurrent()
        repeat(15) { frame() }
        assertEquals(FeedPageState.LOADING, fragment.viewModel.uiState.value.pageState)
        assertEquals(StateLayoutState.LOADING, stateLayout.requestedState)
        assertFalse(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
        settle()
        assertEquals(StateLayoutState.ERROR, stateLayout.renderedState)
    }

    @Test fun `真实Content呈现前禁用入口 Empty成功清空模型`() {
        fragment.viewModel.selectScenario(FeedScenario.FIRST_EMPTY)
        settle()
        assertEquals(0, recycler.adapter!!.itemCount)
        assertEquals(StateLayoutState.EMPTY, stateLayout.renderedState)
        assertFalse(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
        fragment.viewModel.selectScenario(FeedScenario.SUCCESS_PAGED)
        scheduler.runCurrent()
        repeat(20) { frame() }
        scheduler.advanceUntilIdle()
        scheduler.runCurrent()
        assertFalse(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
        settle()
        assertEquals(StateLayoutState.CONTENT, stateLayout.renderedState)
        assertTrue(fragment.requireView().findViewById<View>(R.id.load_next).isEnabled)
    }

    @Test fun `后台恢复读取完成快照 收尾后不重复刷新`() {
        settle()
        assertTrue(refresh.autoRefresh())
        repeat(60) { frame() }
        assertNotNull(fragment.viewModel.uiState.value.activeRequest)
        activityController.pause().stop()
        scheduler.advanceUntilIdle()
        activityController.start().resume().visible()
        settle()
        assertEquals(RefreshState.None, refresh.state)
        assertEquals(CompletionStatus.SUCCESS, fragment.viewModel.uiState.value.lastCompletion!!.status)
        val requestId = fragment.viewModel.uiState.value.lastCompletion!!.token.requestId
        repeat(20) { frame() }
        assertEquals(requestId, fragment.viewModel.uiState.value.lastCompletion!!.token.requestId)
    }

    @Test fun `旋转保留ViewModel和列表锚点 不重复首屏`() {
        settle()
        fragment.viewModel.loadMore(); settle()
        fragment.viewModel.setLayoutMode(FeedLayoutMode.GRID); settle()
        (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(16, -12)
        repeat(5) { frame() }
        val vm = fragment.viewModel
        val lastRequestId = vm.uiState.value.lastCompletion!!.token.requestId
        val before = (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        assertTrue(before > 5)
        activityController.recreate()
        fragment = activity.supportFragmentManager.findFragmentByTag("feed") as FeedDemoFragment
        settle()
        assertSame(vm, fragment.viewModel)
        assertEquals(lastRequestId, vm.uiState.value.lastCompletion!!.token.requestId)
        assertEquals(FeedLayoutMode.GRID, vm.uiState.value.layoutMode)
        assertEquals(before, (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
    }

    @Test fun `快速场景切换与重放保留最新选择 不多发Spinner请求`() {
        settle()
        val spinner = fragment.requireView().findViewById<Spinner>(R.id.scenario_spinner)
        spinner.setSelection(FeedScenario.FIRST_ERROR_ONCE.ordinal)
        frame(); settle()
        assertEquals(StateLayoutState.ERROR, stateLayout.renderedState)
        val dataset = fragment.viewModel.uiState.value.datasetId
        fragment.viewModel.retry(); settle()
        assertEquals(dataset, fragment.viewModel.uiState.value.datasetId)
        assertEquals(StateLayoutState.CONTENT, stateLayout.renderedState)
        fragment.viewModel.replay(); frame()
        fragment.viewModel.selectScenario(FeedScenario.SUCCESS_PAGED); settle()
        assertEquals(FeedScenario.SUCCESS_PAGED, fragment.viewModel.uiState.value.scenario)
        assertEquals(StateLayoutState.CONTENT, stateLayout.renderedState)
    }

    @Test fun `横向行复用Adapter并恢复同一子项集合的位置`() {
        val context = activity
        val store = CarouselScrollStore().apply { startDataset(1) }
        val item = FeedDataFactory.items(FeedDataFactory.records(1), false).filterIsInstance<FeedItem.Carousel>().single()
        val row = HCarouselView(context)
        row.setItem(item)
        row.setScrollStore(store)
        row.setOnItemClick { }
        row.bindChildren()
        val inner = row.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.carousel_list)
        val adapter = inner.adapter
        inner.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        (inner.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(3, 0)
        inner.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        assertEquals(3, (inner.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
        row.release()
        assertNotNull(store.restore(item.stableId, item.record.children.map { it.stableId }))
        row.setItem(item)
        row.setScrollStore(store)
        row.bindChildren()
        inner.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        assertEquals(3, (inner.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
        assertSame(adapter, inner.adapter)
        assertFalse(inner.isNestedScrollingEnabled)
        assertEquals(6, inner.adapter!!.itemCount)
    }

    @Test fun `短视口大字体错误页可滚动到完整重试按钮`() {
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 2f }
        val context = activity.createConfigurationContext(configuration)
        val error = android.view.LayoutInflater.from(context).inflate(R.layout.state_network_feed_error, null) as android.widget.ScrollView
        val height = (120 * context.resources.displayMetrics.density).toInt()
        error.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        error.layout(0, 0, 1080, height)
        error.fullScroll(View.FOCUS_DOWN)
        val retry = error.findViewById<View>(R.id.demo_retry)
        val bottom = retry.bottom + (retry.parent as View).top - error.scrollY
        assertTrue(retry.height >= 48 * context.resources.displayMetrics.density)
        assertTrue(bottom <= error.height)
        assertTrue(bottom - retry.height >= 0)
    }

    @Test
    @Config(qualifiers = "night")
    fun `夜间卡片使用独立配色 RTL不改变模型身份`() {
        settle()
        val card = com.sfyc.demo.network.models.ProfileCardView(activity)
        card.setItem(FeedItem.Profile(FeedRecord.Profile(1, 1)))
        assertEquals(android.graphics.Color.rgb(232, 237, 242),
            card.findViewById<android.widget.TextView>(R.id.profile_name).currentTextColor)
        val before = (0 until recycler.adapter!!.itemCount).map { recycler.adapter!!.getItemId(it) }
        fragment.requireView().layoutDirection = View.LAYOUT_DIRECTION_RTL
        repeat(5) { frame() }
        assertEquals(before, (0 until recycler.adapter!!.itemCount).map { recycler.adapter!!.getItemId(it) })
    }

    @Test
    @Config(qualifiers = "land")
    fun `横屏控制区放侧边 列表保留有效视口`() {
        settle()
        assertEquals(android.widget.LinearLayout.HORIZONTAL, (fragment.requireView() as android.widget.LinearLayout).orientation)
        assertTrue(recycler.measuredWidth > 0)
        assertTrue(recycler.measuredHeight > 0)
    }

    @Test fun `完成快照可越过RUNNING 重放只收尾一次`() {
        val tracker = FeedGestureTracker()
        val token = RequestToken(7, 1, RequestKind.REFRESH, 1)
        tracker.bind(token)
        val completed = FeedUiState(pageState = FeedPageState.CONTENT, datasetId = 1,
            lastCompletion = RequestCompletion(token, CompletionStatus.SUCCESS))
        assertTrue(tracker.takeFinished(completed, modelsReady = false).isEmpty())
        assertEquals(listOf(completed.lastCompletion), tracker.takeFinished(completed, modelsReady = true))
        assertTrue(tracker.takeFinished(completed, modelsReady = true).isEmpty())
    }

    @Test fun `100毫秒重放在显示延迟内提交内容 跳过骨架`() {
        settle()
        fragment.viewModel.setLatency(100)
        fragment.viewModel.replay()
        scheduler.runCurrent()
        repeat(3) { frame() }
        scheduler.advanceTimeBy(100)
        scheduler.runCurrent()
        val observed = mutableListOf<StateLayoutState>()
        repeat(10) { frame(); observed += stateLayout.renderedState }
        assertFalse(observed.contains(StateLayoutState.LOADING))
        assertEquals(StateLayoutState.CONTENT, stateLayout.renderedState)
    }

    @Test fun `500毫秒返回仍从骨架真实上屏保留至少600毫秒`() {
        settle()
        var shownAt = -1L
        var contentAt = -1L
        stateLayout.setOnRenderedStateChangedListener { _, _, new, _ ->
            if (new == StateLayoutState.LOADING) shownAt = android.os.SystemClock.uptimeMillis()
            if (new == StateLayoutState.CONTENT) contentAt = android.os.SystemClock.uptimeMillis()
        }
        fragment.viewModel.setLatency(500)
        fragment.viewModel.replay()
        scheduler.runCurrent()
        repeat(15) { frame() }
        assertTrue(shownAt >= 0)
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()
        repeat(70) { frame() }
        assertTrue("最短时长从真实呈现回调计算", contentAt - shownAt >= 600L)
    }

    @Test fun `切场景收尾旧动画 新View不重放历史动画`() {
        val tracker = FeedGestureTracker()
        val old = RequestToken(1, 1, RequestKind.APPEND, 2)
        tracker.bind(old)
        val next = RequestToken(2, 2, RequestKind.INITIAL, 1)
        assertEquals(CompletionStatus.CANCELLED, tracker.takeFinished(FeedUiState(datasetId = 2, activeRequest = next), false).single().status)
        assertTrue(tracker.takeFinished(FeedUiState(datasetId = 2, activeRequest = next), false).isEmpty())
        val fresh = FeedGestureTracker()
        assertTrue(fresh.takeFinished(FeedUiState(lastCompletion = RequestCompletion(old, CompletionStatus.FAILURE)), true).isEmpty())
    }
}
