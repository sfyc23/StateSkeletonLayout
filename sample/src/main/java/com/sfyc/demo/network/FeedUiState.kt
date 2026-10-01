package com.sfyc.demo.network

enum class FeedPageState { LOADING, CONTENT, EMPTY, ERROR }
enum class CompletionStatus { SUCCESS, FAILURE, CANCELLED }

data class RequestToken(
    val requestId: Long,
    val datasetId: Long,
    val kind: RequestKind,
    val page: Int,
)

data class RequestCompletion(
    val token: RequestToken,
    val status: CompletionStatus,
    val message: String? = null,
)

data class FeedUiState(
    val pageState: FeedPageState = FeedPageState.LOADING,
    val datasetId: Long = 0,
    val items: List<FeedItem> = emptyList(),
    val recordCount: Int = 0,
    val nextPage: Int? = null,
    val dataVersion: Long = 0,
    val activeRequest: RequestToken? = null,
    val lastCompletion: RequestCompletion? = null,
    val errorMessage: String? = null,
    val scenario: FeedScenario = FeedScenario.SUCCESS_PAGED,
    val latencyMillis: Long = 1500L,
    val layoutMode: FeedLayoutMode = FeedLayoutMode.LINEAR,
    // 替换成功时递增，追加和失败不改变回顶依据。
    val replacementVersion: Long = 0,
) {
    val hasMore get() = pageState == FeedPageState.CONTENT && nextPage != null
    val isBusy get() = activeRequest != null
}
