package com.sfyc.demo.network

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeFeedRepositoryTest {
    private fun request(scenario: FeedScenario = FeedScenario.SUCCESS_PAGED, page: Int = 1,
        kind: RequestKind = if (page == 1) RequestKind.INITIAL else RequestKind.APPEND,
        dataset: Long = 1, latency: Long = 0, revision: Int = 1,
    ) = FeedRequest(RequestToken(page.toLong(), dataset, kind, page), scenario, latency, revision = revision)

    @Test fun `三页包含27条业务和30个唯一模型 首屏覆盖六类型`() = runTest {
        val repo = FakeFeedRepository()
        val pages = (1..3).map { repo.loadPage(request(page = it)) }
        assertEquals(listOf(10, 10, 7), pages.map { it.records.size })
        assertEquals(listOf(2, 3, null), pages.map { it.nextPage })
        val records = pages.flatMap { it.records }
        assertEquals(12, records.count { it is FeedRecord.Profile })
        assertEquals(6, records.count { it is FeedRecord.Article })
        assertEquals(8, records.count { it is FeedRecord.Tile })
        assertEquals(6, (records.filterIsInstance<FeedRecord.Carousel>().single()).children.size)
        val models = pages.flatMapIndexed { index, page -> FeedDataFactory.items(page.records, index == 0) }
        assertEquals(30, models.size)
        assertEquals(30, models.map { it.stableId }.distinct().size)
        val first = FeedDataFactory.items(pages.first().records, true)
        assertEquals(13, first.size)
        assertEquals(6, first.map { it::class }.distinct().size)
        assertEquals(1, models.count { it is FeedItem.Banner })
        assertEquals(2, models.count { it is FeedItem.Section })
    }

    @Test fun `INITIAL和REFRESH第一页使用不同场景分支`() = runTest {
        val repo = FakeFeedRepository()
        for (scenario in listOf(FeedScenario.REFRESH_TO_EMPTY, FeedScenario.REFRESH_ERROR)) {
            assertEquals(10, repo.loadPage(request(scenario)).records.size)
            if (scenario == FeedScenario.REFRESH_TO_EMPTY) {
                assertEquals(FeedPage(emptyList(), null), repo.loadPage(request(scenario, kind = RequestKind.REFRESH)))
            } else try {
                repo.loadPage(request(scenario, kind = RequestKind.REFRESH))
                fail("REFRESH 必须失败")
            } catch (_: IOException) { }
        }
    }

    @Test fun `失败一次保留重试次数 新数据集重新注入故障`() = runTest {
        val repo = FakeFeedRepository()
        val req = request(FeedScenario.FIRST_ERROR_ONCE)
        try { repo.loadPage(req); fail("第一次必须失败") } catch (_: IOException) { }
        assertEquals(10, repo.loadPage(req).records.size)
        try { repo.loadPage(req.copy(token = req.token.copy(datasetId = 2))); fail("重放应再次失败") } catch (_: IOException) { }
    }

    @Test fun `持续首屏失败与第二页失败一次可重复验证`() = runTest {
        val repo = FakeFeedRepository()
        repeat(2) {
            try { repo.loadPage(request(FeedScenario.FIRST_ERROR)); fail("持续失败") } catch (_: IOException) { }
        }
        val append = request(FeedScenario.LOAD_MORE_ERROR_ONCE, page = 2)
        try { repo.loadPage(append); fail("第二页首次失败") } catch (_: IOException) { }
        assertEquals(10, repo.loadPage(append).records.size)
    }

    @Test fun `首屏空 满页无更多和空末页均以游标为准`() = runTest {
        val repo = FakeFeedRepository()
        assertEquals(FeedPage(emptyList(), null), repo.loadPage(request(FeedScenario.FIRST_EMPTY)))
        val full = repo.loadPage(request(FeedScenario.NO_MORE_IMMEDIATE))
        assertEquals(10, full.records.size)
        assertNull(full.nextPage)
        assertEquals(2, repo.loadPage(request(FeedScenario.EMPTY_LAST_PAGE)).nextPage)
        assertEquals(FeedPage(emptyList(), null), repo.loadPage(request(FeedScenario.EMPTY_LAST_PAGE, 2)))
    }

    @Test fun `延迟可控 取消不消耗失败次数`() = runTest {
        val repo = FakeFeedRepository()
        val req = request(FeedScenario.FIRST_ERROR_ONCE, latency = 1500)
        val job = async { repo.loadPage(req) }
        advanceTimeBy(1499)
        assertFalse(job.isCompleted)
        job.cancel()
        runCurrent()
        try { repo.loadPage(req.copy(latencyMillis = 0)); fail("取消不能消费故障") } catch (_: IOException) { }
    }

    @Test fun `刷新稳定ID相同但内容版本参与diff`() = runTest {
        val repo = FakeFeedRepository()
        val first = repo.loadPage(request())
        val refreshed = repo.loadPage(request(kind = RequestKind.REFRESH, revision = 2))
        assertEquals(first.records.map { it.stableId }, refreshed.records.map { it.stableId })
        assertNotEquals(first.records, refreshed.records)
    }
}
