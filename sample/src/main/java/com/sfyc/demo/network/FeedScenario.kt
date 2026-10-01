package com.sfyc.demo.network

import androidx.annotation.StringRes
import com.sfyc.demo.ssl.R

enum class RequestKind { INITIAL, REFRESH, APPEND }

enum class FeedScenario(@get:StringRes val titleRes: Int) {
    SUCCESS_PAGED(R.string.network_scenario_success),
    FIRST_EMPTY(R.string.network_scenario_empty),
    FIRST_ERROR_ONCE(R.string.network_scenario_error_once),
    FIRST_ERROR(R.string.network_scenario_error),
    REFRESH_TO_EMPTY(R.string.network_scenario_refresh_empty),
    REFRESH_ERROR(R.string.network_scenario_refresh_error),
    LOAD_MORE_ERROR_ONCE(R.string.network_scenario_append_error),
    NO_MORE_IMMEDIATE(R.string.network_scenario_no_more),
    EMPTY_LAST_PAGE(R.string.network_scenario_empty_last),
}

enum class FeedLayoutMode { LINEAR, GRID }

data class FeedRequest(
    val token: RequestToken,
    val scenario: FeedScenario,
    val latencyMillis: Long,
    val pageSize: Int = 10,
    val revision: Int = 1,
)

data class FeedPage(val records: List<FeedRecord>, val nextPage: Int?)

fun interface FeedRepository {
    suspend fun loadPage(request: FeedRequest): FeedPage
}
