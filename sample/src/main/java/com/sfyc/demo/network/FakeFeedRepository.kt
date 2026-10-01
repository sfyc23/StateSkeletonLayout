package com.sfyc.demo.network

import java.io.IOException
import kotlinx.coroutines.delay

class FeedProtocolException(message: String) : IOException(message)

/** 可取消的确定性模拟；故障随数据集重置，重试与旋转保留已消耗次数。 */
class FakeFeedRepository : FeedRepository {
    private data class FailureKey(val datasetId: Long, val kind: RequestKind, val page: Int)
    private val attempts = mutableMapOf<FailureKey, Int>()
    private var newestDataset = 0L

    override suspend fun loadPage(request: FeedRequest): FeedPage {
        require(request.token.page >= 1 && request.pageSize > 0 && request.latencyMillis >= 0)
        delay(request.latencyMillis)
        val token = request.token
        if (token.datasetId > newestDataset) {
            newestDataset = token.datasetId
            attempts.clear()
        }
        val key = FailureKey(token.datasetId, token.kind, token.page)
        val attempt = attempts.getOrDefault(key, 0) + 1
        attempts[key] = attempt
        val initial = token.kind == RequestKind.INITIAL
        val refreshing = token.kind == RequestKind.REFRESH
        val shouldFail = when (request.scenario) {
            FeedScenario.FIRST_ERROR -> initial
            FeedScenario.FIRST_ERROR_ONCE -> initial && attempt == 1
            FeedScenario.REFRESH_ERROR -> refreshing
            FeedScenario.LOAD_MORE_ERROR_ONCE -> token.kind == RequestKind.APPEND && token.page == 2 && attempt == 1
            else -> false
        }
        if (shouldFail) throw IOException("模拟请求失败：${token.kind}，第 ${token.page} 页")
        if ((initial && request.scenario == FeedScenario.FIRST_EMPTY) ||
            (refreshing && request.scenario == FeedScenario.REFRESH_TO_EMPTY) ||
            (token.kind == RequestKind.APPEND && request.scenario == FeedScenario.EMPTY_LAST_PAGE)
        ) return FeedPage(emptyList(), null)

        val all = FeedDataFactory.records(request.revision)
        val start = (token.page.toLong() - 1) * request.pageSize
        val records = if (start >= all.size) emptyList() else {
            all.subList(start.toInt(), (start + request.pageSize).coerceAtMost(all.size.toLong()).toInt()).toList()
        }
        val nextPage = when {
            request.scenario == FeedScenario.NO_MORE_IMMEDIATE -> null
            start + records.size >= all.size -> null
            else -> token.page + 1
        }
        return FeedPage(records, nextPage)
    }
}
