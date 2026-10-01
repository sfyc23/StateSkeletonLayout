package com.sfyc.demo.network

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 请求令牌是业务并发边界；库自己的显示令牌不参与网络结果校验。 */
class FeedViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: FeedRepository = FakeFeedRepository(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(FeedUiState(
        scenario = FeedScenario.entries.firstOrNull { it.name == savedStateHandle.get<String>("scenario") }
            ?: FeedScenario.SUCCESS_PAGED,
        latencyMillis = savedStateHandle.get<Long>("latency")?.coerceAtLeast(0L) ?: 1500L,
        layoutMode = FeedLayoutMode.entries.firstOrNull { it.name == savedStateHandle.get<String>("layout") }
            ?: FeedLayoutMode.LINEAR,
    ))
    val uiState = mutableState.asStateFlow()
    private var initialized = false
    private var requestSequence = 0L
    private var requestJob: Job? = null
    private var revisionSequence = 0
    private var committedRevision = 0

    fun ensureInitialLoad() {
        if (!initialized) replay()
    }

    fun replay(): RequestToken {
        initialized = true
        return start(RequestKind.INITIAL, newDataset = true)
    }

    fun selectScenario(scenario: FeedScenario) {
        if (scenario == uiState.value.scenario) return
        savedStateHandle["scenario"] = scenario.name
        mutableState.update { it.copy(scenario = scenario) }
        replay()
    }

    fun setLatency(latencyMillis: Long) {
        require(latencyMillis >= 0)
        savedStateHandle["latency"] = latencyMillis
        mutableState.update { it.copy(latencyMillis = latencyMillis) }
    }

    fun setLayoutMode(mode: FeedLayoutMode) {
        savedStateHandle["layout"] = mode.name
        mutableState.update { it.copy(layoutMode = mode) }
    }

    fun retry(): RequestToken? = if (!uiState.value.isBusy && uiState.value.pageState == FeedPageState.ERROR) {
        start(RequestKind.INITIAL)
    } else null

    fun refresh(): RequestToken? = if (canRequestContent()) start(RequestKind.REFRESH) else null

    fun loadMore(): RequestToken? = if (canRequestContent() && uiState.value.hasMore) {
        start(RequestKind.APPEND)
    } else null

    private fun canRequestContent() = !uiState.value.isBusy && uiState.value.pageState == FeedPageState.CONTENT

    private fun start(kind: RequestKind, newDataset: Boolean = false): RequestToken {
        val previous = uiState.value
        val token = RequestToken(
            ++requestSequence,
            previous.datasetId + if (newDataset) 1 else 0,
            kind,
            if (kind == RequestKind.APPEND) checkNotNull(previous.nextPage) else 1,
        )
        val request = FeedRequest(
            token, previous.scenario, previous.latencyMillis,
            revision = if (kind == RequestKind.APPEND) committedRevision else ++revisionSequence,
        )
        // 同步登记新令牌后再取消旧 Job；旧 finally 看不到自己的有效令牌。
        mutableState.update { it.copy(
            datasetId = token.datasetId,
            pageState = if (kind == RequestKind.INITIAL) FeedPageState.LOADING else it.pageState,
            recordCount = if (newDataset) 0 else it.recordCount,
            nextPage = if (newDataset) null else it.nextPage,
            activeRequest = token,
            lastCompletion = previous.activeRequest?.let { old ->
                RequestCompletion(old, CompletionStatus.CANCELLED)
            } ?: previous.lastCompletion,
            errorMessage = null,
        ) }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val page = repository.loadPage(request)
                if (isCurrent(token)) commitSuccess(request, page)
            } catch (cancelled: CancellationException) {
                if (isCurrent(token)) mutableState.update { it.copy(
                    activeRequest = null,
                    lastCompletion = RequestCompletion(token, CompletionStatus.CANCELLED),
                ) }
                throw cancelled
            } catch (failure: IOException) {
                if (isCurrent(token)) mutableState.update { it.copy(
                    pageState = if (kind == RequestKind.INITIAL) FeedPageState.ERROR else it.pageState,
                    activeRequest = null,
                    errorMessage = failure.message,
                    lastCompletion = RequestCompletion(token, CompletionStatus.FAILURE, failure.message),
                ) }
            } finally {
                // 未知编程错误仍抛出；只能清理当前请求，不能清掉更新一轮的忙碌标志。
                if (isCurrent(token)) mutableState.update { it.copy(activeRequest = null) }
            }
        }
        return token
    }

    private fun isCurrent(token: RequestToken) = uiState.value.activeRequest == token &&
        uiState.value.datasetId == token.datasetId

    private fun commitSuccess(request: FeedRequest, page: FeedPage) {
        val token = request.token
        if (page.nextPage != null && page.nextPage != token.page + 1) {
            throw FeedProtocolException("分页游标必须是下一页或 null")
        }
        if (page.records.isEmpty() && page.nextPage != null) {
            throw FeedProtocolException("空页不能继续分页")
        }
        if (page.records.size > request.pageSize) throw FeedProtocolException("业务记录超过 pageSize")
        val appended = token.kind == RequestKind.APPEND
        val old = uiState.value
        val added = FeedDataFactory.items(page.records.toList(), includeDecorations = !appended)
        val items = if (appended) (old.items + added).toList() else added
        if (items.map { it.stableId }.toSet().size != items.size) {
            throw FeedProtocolException("列表存在重复的稳定 ID")
        }
        val count = (if (appended) old.recordCount else 0) + page.records.size
        if (!appended) committedRevision = request.revision
        mutableState.update { it.copy(
            pageState = if (count == 0) FeedPageState.EMPTY else FeedPageState.CONTENT,
            items = items,
            recordCount = count,
            nextPage = page.nextPage,
            dataVersion = it.dataVersion + 1,
            replacementVersion = it.replacementVersion + if (appended) 0 else 1,
            activeRequest = null,
            lastCompletion = RequestCompletion(token, CompletionStatus.SUCCESS),
            errorMessage = null,
        ) }
    }
}
