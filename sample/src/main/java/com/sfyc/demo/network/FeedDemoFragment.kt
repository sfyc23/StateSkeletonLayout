package com.sfyc.demo.network

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.sfyc.ssl.SkeletonNodeConfig
import com.sfyc.ssl.SkeletonShape
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentNetworkFeedBinding
import com.sfyc.demo.network.models.CarouselScrollStore
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.scwang.smart.refresh.layout.constant.RefreshState
import com.scwang.smart.refresh.layout.listener.ScrollBoundaryDecider
import com.scwang.smart.refresh.layout.simple.SimpleMultiListener
import kotlinx.coroutines.launch

class FeedDemoFragment : Fragment() {
    internal val viewModel: FeedViewModel by viewModels {
        viewModelFactory { initializer { FeedViewModel(createSavedStateHandle()) } }
    }
    private var binding: FragmentNetworkFeedBinding? = null
    private var log: DemoLog? = null
    private var controller: FeedController? = null
    private val carouselStore = CarouselScrollStore()
    private val gestures = FeedGestureTracker()
    private var modelListener: OnModelBuildFinishedListener? = null
    private var pendingDraw: OneShotPreDrawListener? = null
    private var viewSequence = 0L
    private var layoutSequence = 0L
    private var appliedLayout: FeedLayoutMode? = null
    private var submittedVersion = -1L
    private var submittedDataset = -1L
    private var readyVersion = -1L
    private var readyDataset = -1L
    private var lastRequestLog = -1L
    private var lastResultLog = -1L
    private var lastReplacement = -1L
    private var committedItems: List<FeedItem> = emptyList()
    private var pendingAnchor: ScrollAnchor? = null

    private data class ScrollAnchor(
        val stableId: String, val offset: Int, val dataset: Long, val replacement: Long,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle("feed_scroll")?.let { saved ->
            saved.getString("id")?.let { id -> pendingAnchor = ScrollAnchor(
                id, saved.getInt("offset"), saved.getLong("dataset"), saved.getLong("replacement"),
            ) }
        }
        carouselStore.readBundle(savedInstanceState?.getBundle("carousel_scroll"))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val created = FragmentNetworkFeedBinding.inflate(inflater, container, false)
        binding = created
        viewSequence++
        return created.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = checkNotNull(binding)
        log = DemoLog(b.controls.demoLog)
        submittedVersion = -1L
        submittedDataset = -1L
        readyVersion = -1L
        readyDataset = -1L
        lastRequestLog = -1L
        lastResultLog = -1L
        appliedLayout = null
        val current = viewModel.uiState.value
        // 新进程只恢复参数，旧数据的外层和横向锚点均不应用到新首屏。
        if (current.dataVersion == 0L) {
            pendingAnchor = null
            carouselStore.clear()
        }
        lastReplacement = pendingAnchor?.replacement ?: current.replacementVersion
        carouselStore.startDataset(current.datasetId)
        val createdController = FeedController({ id -> log?.add("CLICK $id") }, carouselStore)
        controller = createdController
        val callbackView = viewSequence
        modelListener = OnModelBuildFinishedListener {
            if (callbackView == viewSequence && binding != null) prepareCommittedModels()
        }.also(createdController::addModelBuildListener)
        b.feed.epoxyRecycler.setController(createdController)
        createdController.adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        configureLayout(current)
        intArrayOf(R.id.network_skeleton_avatar, R.id.network_skeleton_avatar_2,
            R.id.network_skeleton_avatar_3, R.id.network_skeleton_avatar_4, R.id.network_skeleton_avatar_5,
            R.id.network_skeleton_avatar_6, R.id.network_skeleton_avatar_7, R.id.network_skeleton_avatar_8).forEach {
            b.feed.stateLayout.setSkeletonNodeConfig(it, SkeletonNodeConfig(shape = SkeletonShape.CIRCLE))
        }
        b.feed.stateLayout.setOnRetryClickListener { viewModel.retry() }
        b.feed.stateLayout.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("RENDERED $old → $new")
            updateInputs()
        }
        b.feed.root.setScrollBoundaryDecider(object : ScrollBoundaryDecider {
            override fun canRefresh(content: View) = !b.feed.epoxyRecycler.canScrollVertically(-1)
            override fun canLoadMore(content: View) = !b.feed.epoxyRecycler.canScrollVertically(1)
        })
        b.feed.root.setOnRefreshListener { startGesture(RequestKind.REFRESH) }
        b.feed.root.setOnLoadMoreListener { startGesture(RequestKind.APPEND) }
        b.feed.root.setOnMultiListener(object : SimpleMultiListener() {
            override fun onStateChanged(refreshLayout: RefreshLayout, oldState: RefreshState, newState: RefreshState) {
                // 拉动阶段不能撤销 enable，否则手势到释放阈值时会被自身禁用。
                if (newState == RefreshState.None || newState.isFinishing) updateInputs()
            }
        })
        configureControls()
        viewModel.ensureInitialLoad()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun configureControls() {
        val controls = checkNotNull(binding).controls
        controls.scenarioSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            FeedScenario.entries.map { getString(it.titleRes) },
        )
        controls.scenarioSpinner.setSelection(viewModel.uiState.value.scenario.ordinal)
        controls.scenarioSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.selectScenario(FeedScenario.entries[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        controls.latencyGroup.setOnCheckedChangeListener { _, id ->
            viewModel.setLatency(when (id) {
                R.id.latency_100 -> 100L
                R.id.latency_500 -> 500L
                else -> 1500L
            })
        }
        controls.toggleLayout.setOnClickListener {
            viewModel.setLayoutMode(if (viewModel.uiState.value.layoutMode == FeedLayoutMode.LINEAR) {
                FeedLayoutMode.GRID
            } else FeedLayoutMode.LINEAR)
        }
        controls.replay.setOnClickListener { viewModel.replay() }
        controls.loadNext.setOnClickListener {
            if (canRequestContent(requireIdleContainer = true)) {
                viewModel.loadMore()
                render(viewModel.uiState.value)
            } else log?.add("IGNORE 控制区分页入口尚未就绪")
        }
    }

    private fun render(ui: FeedUiState) {
        val b = binding ?: return
        carouselStore.startDataset(ui.datasetId)
        if (appliedLayout != ui.layoutMode) configureLayout(ui)
        ui.activeRequest?.let { token ->
            if (lastRequestLog != token.requestId) {
                lastRequestLog = token.requestId
                log?.add("REQUEST #${token.requestId} ${token.kind} page=${token.page} dataset=${token.datasetId}")
            }
        }
        ui.lastCompletion?.let {
            if (lastResultLog != it.token.requestId) {
                lastResultLog = it.token.requestId
                log?.add("RESULT #${it.token.requestId} ${it.status}${it.message?.let { text -> "：$text" }.orEmpty()}")
            }
        }
        when (ui.pageState) {
            FeedPageState.LOADING -> {
                pendingDraw?.removeListener()
                pendingDraw = null
                readyVersion = -1L
                b.feed.stateLayout.showLoading()
            }
            FeedPageState.ERROR -> b.feed.stateLayout.showError()
            FeedPageState.CONTENT, FeedPageState.EMPTY -> {
                if (submittedVersion != ui.dataVersion || submittedDataset != ui.datasetId) {
                    readyVersion = -1L
                    submittedVersion = ui.dataVersion
                    submittedDataset = ui.datasetId
                    controller?.setData(ui.items.toList())
                }
                if (modelsReady(ui)) showFinalState(ui) else prepareCommittedModels()
            }
        }
        finishGestures(ui)
        updateInputs()
    }

    private fun configureLayout(ui: FeedUiState) {
        val b = binding ?: return
        if (appliedLayout != null) captureAnchor()?.let { pendingAnchor = it }
        pendingDraw?.removeListener()
        pendingDraw = null
        layoutSequence++
        readyVersion = -1L
        val grid = ui.layoutMode == FeedLayoutMode.GRID
        val activeController = checkNotNull(controller)
        activeController.spanCount = if (grid) 2 else 1
        b.feed.epoxyRecycler.layoutManager = if (grid) {
            GridLayoutManager(requireContext(), 2).apply { spanSizeLookup = activeController.spanSizeLookup }
        } else LinearLayoutManager(requireContext())
        b.feed.stateLayout.skeletonConfig = b.feed.stateLayout.skeletonConfig.copy(
            templateLayoutResId = if (grid) R.layout.skeleton_network_feed_grid else R.layout.skeleton_network_feed_linear,
        )
        b.feed.stateLayout.invalidateSkeleton()
        appliedLayout = ui.layoutMode
    }

    private fun prepareCommittedModels() {
        val b = binding ?: return
        val ui = viewModel.uiState.value
        if (ui.pageState != FeedPageState.CONTENT && ui.pageState != FeedPageState.EMPTY) return
        if (submittedDataset != ui.datasetId || submittedVersion != ui.dataVersion) return
        if (controller?.hasCommittedItems(ui.items) != true || modelsReady(ui)) return
        committedItems = ui.items
        restoreScroll(ui)
        pendingDraw?.removeListener()
        val expectedView = viewSequence
        val expectedLayout = layoutSequence
        pendingDraw = b.root.doOnPreDraw {
            pendingDraw = null
            val latest = viewModel.uiState.value
            if (binding !== b || expectedView != viewSequence || expectedLayout != layoutSequence ||
                ui.datasetId != latest.datasetId || ui.dataVersion != latest.dataVersion ||
                (latest.pageState != FeedPageState.CONTENT && latest.pageState != FeedPageState.EMPTY) ||
                controller?.hasCommittedItems(latest.items) != true
            ) return@doOnPreDraw
            readyDataset = latest.datasetId
            readyVersion = latest.dataVersion
            showFinalState(latest)
            finishGestures(latest)
            updateInputs()
        }
    }

    private fun showFinalState(ui: FeedUiState) {
        binding?.feed?.stateLayout?.render(if (ui.pageState == FeedPageState.EMPTY) StateLayoutState.EMPTY else StateLayoutState.CONTENT)
    }

    private fun modelsReady(ui: FeedUiState) = readyDataset == ui.datasetId && readyVersion == ui.dataVersion

    private fun canRequestContent(requireIdleContainer: Boolean): Boolean {
        val b = binding ?: return false
        val ui = viewModel.uiState.value
        return ui.pageState == FeedPageState.CONTENT && !ui.isBusy && modelsReady(ui) &&
            b.feed.stateLayout.renderedState == StateLayoutState.CONTENT &&
            (!requireIdleContainer || b.feed.root.state == RefreshState.None)
    }

    private fun startGesture(kind: RequestKind) {
        val token = if (canRequestContent(requireIdleContainer = false)) {
            if (kind == RequestKind.REFRESH) viewModel.refresh() else viewModel.loadMore()
        } else null
        if (token != null) gestures.bind(token) else if (!gestures.isBound(kind)) {
            finishAnimation(RequestCompletion(RequestToken(-1, -1, kind, 1), CompletionStatus.CANCELLED), viewModel.uiState.value)
            log?.add("IGNORE $kind 当前没有可接受的业务请求")
        }
        // 零延迟请求可能在返回令牌前完成；完成快照仍可匹配刚绑定的动画。
        render(viewModel.uiState.value)
    }

    private fun finishGestures(ui: FeedUiState) {
        gestures.takeFinished(ui, modelsReady(ui)).forEach { finishAnimation(it, ui) }
    }

    private fun finishAnimation(completion: RequestCompletion, ui: FeedUiState) {
        val refresh = binding?.feed?.root ?: return
        val success = completion.status == CompletionStatus.SUCCESS
        if (completion.token.kind == RequestKind.REFRESH) {
            refresh.finishRefresh(0, success, if (success) !ui.hasMore else null)
        } else if (completion.token.kind == RequestKind.APPEND) {
            if (success && !ui.hasMore) refresh.finishLoadMoreWithNoMoreData() else refresh.finishLoadMore(success)
        }
        log?.add("FINISH #${completion.token.requestId} ${completion.token.kind} ${completion.status}")
    }

    private fun updateInputs() {
        val b = binding ?: return
        val ui = viewModel.uiState.value
        val ready = canRequestContent(requireIdleContainer = true)
        b.feed.stateLayout.retryClicksEnabled = !ui.isBusy && ui.pageState == FeedPageState.ERROR
        // 不在请求刚开始时 resetNoMoreData；失败继续保留上次的 Footer 语义。
        if ((ui.pageState == FeedPageState.CONTENT || ui.pageState == FeedPageState.EMPTY) && modelsReady(ui)) {
            b.feed.root.setNoMoreData(!ui.hasMore)
        }
        b.feed.root.setEnableRefresh(ready)
        // 已无更多时仍启用 Footer 的展示能力，nextPage 和 noMoreData 共同阻止重复请求。
        b.feed.root.setEnableLoadMore(ready)
        b.controls.loadNext.isEnabled = ready && ui.hasMore
        b.controls.loadNext.setText(if (ui.isBusy) R.string.network_busy else R.string.network_load_next)
        b.controls.latencyGroup.check(when (ui.latencyMillis) {
            100L -> R.id.latency_100
            500L -> R.id.latency_500
            else -> R.id.latency_1500
        })
        val request = ui.activeRequest?.let { "${it.kind} #${it.requestId}" } ?: getString(R.string.network_idle)
        val status = getString(R.string.network_status, getString(ui.scenario.titleRes), ui.layoutMode.name,
            ui.latencyMillis, request, ui.nextPage?.toString() ?: "null", ui.recordCount, ui.items.size)
        b.controls.networkStatus.text = ui.errorMessage?.let {
            getString(R.string.network_error_status, status, it.ifBlank { getString(R.string.network_error_fallback) })
        } ?: status
    }

    private fun captureAnchor(): ScrollAnchor? {
        val b = binding ?: return null
        val manager = b.feed.epoxyRecycler.layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        val item = committedItems.getOrNull(first) ?: return null
        val child = manager.findViewByPosition(first) ?: return null
        return ScrollAnchor(item.stableId, manager.getDecoratedTop(child) - b.feed.epoxyRecycler.paddingTop,
            readyDataset, lastReplacement)
    }

    private fun restoreScroll(ui: FeedUiState) {
        val manager = binding?.feed?.epoxyRecycler?.layoutManager as? LinearLayoutManager ?: return
        val anchor = pendingAnchor?.takeIf { it.dataset == ui.datasetId && it.replacement == ui.replacementVersion }
        if (anchor != null) {
            val index = ui.items.indexOfFirst { it.stableId == anchor.stableId }
            if (index >= 0) manager.scrollToPositionWithOffset(index, anchor.offset)
        } else if (lastReplacement != ui.replacementVersion) {
            manager.scrollToPositionWithOffset(0, 0)
        }
        pendingAnchor = null
        lastReplacement = ui.replacementVersion
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (captureAnchor() ?: pendingAnchor)?.let { anchor -> outState.putBundle("feed_scroll", Bundle().apply {
            putString("id", anchor.stableId)
            putInt("offset", anchor.offset)
            putLong("dataset", anchor.dataset)
            putLong("replacement", anchor.replacement)
        }) }
        outState.putBundle("carousel_scroll", carouselStore.toBundle())
    }

    override fun onStop() {
        clearGestures()
        super.onStop()
    }

    private fun clearGestures() {
        gestures.clear().forEach { token -> finishAnimation(RequestCompletion(token, CompletionStatus.CANCELLED), viewModel.uiState.value) }
        binding?.feed?.root?.closeHeaderOrFooter()
    }

    override fun onDestroyView() {
        captureAnchor()?.let { pendingAnchor = it }
        clearGestures()
        pendingDraw?.removeListener()
        pendingDraw = null
        modelListener?.let { controller?.removeModelBuildListener(it) }
        controller?.cancelPendingModelBuild()
        binding?.let { b ->
            b.feed.stateLayout.setOnRetryClickListener(null)
            b.feed.stateLayout.setOnRenderedStateChangedListener(null)
            b.feed.root.setOnRefreshListener(null)
            b.feed.root.setOnLoadMoreListener(null)
            b.feed.root.setOnMultiListener(null)
            b.feed.root.setScrollBoundaryDecider(null)
            b.controls.scenarioSpinner.onItemSelectedListener = null
            b.feed.epoxyRecycler.clear()
        }
        modelListener = null
        controller = null
        committedItems = emptyList()
        binding = null
        log = null
        viewSequence++
        super.onDestroyView()
    }
}
