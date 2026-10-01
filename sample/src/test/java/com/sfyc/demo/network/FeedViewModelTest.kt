package com.sfyc.demo.network

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FeedViewModel>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun model(scenario: FeedScenario = FeedScenario.SUCCESS_PAGED,
        repository: FeedRepository = FakeFeedRepository(), handle: SavedStateHandle? = null,
    ) = FeedViewModel(handle ?: SavedStateHandle(mapOf("scenario" to scenario.name, "latency" to 100L)), repository)
        .also { models += it }

    private class CountingRepository : FeedRepository {
        val requests = mutableListOf<FeedRequest>()
        private val delegate = FakeFeedRepository()
        override suspend fun loadPage(request: FeedRequest): FeedPage {
            requests += request
            return delegate.loadPage(request)
        }
    }

    private class UncooperativeRepository : FeedRepository {
        val pending = mutableListOf<Pair<FeedRequest, CompletableDeferred<FeedPage>>>()
        override suspend fun loadPage(request: FeedRequest): FeedPage = withContext(NonCancellable) {
            val result = CompletableDeferred<FeedPage>()
            pending += request to result
            result.await()
        }
    }

    @Test fun `初始化幂等 接受时同步登记 请求期间互斥`() = runTest(dispatcher) {
        val repo = CountingRepository()
        val vm = model(repository = repo)
        vm.ensureInitialLoad()
        assertNotNull(vm.uiState.value.activeRequest)
        repeat(4) { vm.ensureInitialLoad(); assertNull(vm.refresh()); assertNull(vm.loadMore()); assertNull(vm.retry()) }
        advanceUntilIdle()
        assertEquals(1, repo.requests.size)
        assertEquals(FeedPageState.CONTENT, vm.uiState.value.pageState)
        val refresh = vm.refresh()
        assertEquals(refresh, vm.uiState.value.activeRequest)
        assertNull(vm.loadMore())
        assertNull(vm.refresh())
        advanceUntilIdle()
        assertEquals(2, repo.requests.size)
    }

    @Test fun `分页成功才推进 满页也可以无更多`() = runTest(dispatcher) {
        val vm = model()
        vm.ensureInitialLoad(); advanceUntilIdle()
        assertEquals(13, vm.uiState.value.items.size)
        val original = vm.uiState.value.items
        assertEquals(2, vm.loadMore()?.page); advanceUntilIdle()
        assertEquals(original, vm.uiState.value.items.take(13))
        assertEquals(3, vm.loadMore()?.page); advanceUntilIdle()
        assertEquals(27, vm.uiState.value.recordCount)
        assertEquals(30, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.hasMore)
        assertNull(vm.loadMore())
        val full = model(FeedScenario.NO_MORE_IMMEDIATE)
        full.ensureInitialLoad(); advanceUntilIdle()
        assertEquals(10, full.uiState.value.recordCount)
        assertFalse(full.uiState.value.hasMore)
        assertNull(full.loadMore())
    }

    @Test fun `首屏空与持续失败各自清理忙碌标志`() = runTest(dispatcher) {
        for (scenario in listOf(FeedScenario.FIRST_EMPTY, FeedScenario.FIRST_ERROR)) {
            val vm = model(scenario)
            vm.ensureInitialLoad(); advanceUntilIdle()
            assertEquals(if (scenario == FeedScenario.FIRST_EMPTY) FeedPageState.EMPTY else FeedPageState.ERROR, vm.uiState.value.pageState)
            assertFalse(vm.uiState.value.isBusy)
            assertNull(vm.refresh()); assertNull(vm.loadMore())
            if (scenario == FeedScenario.FIRST_ERROR) {
                assertNotNull(vm.retry()); advanceUntilIdle()
                assertEquals(FeedPageState.ERROR, vm.uiState.value.pageState)
                assertFalse(vm.uiState.value.isBusy)
            }
        }
    }

    @Test fun `首屏重试同数据集成功 重放重置故障`() = runTest(dispatcher) {
        val vm = model(FeedScenario.FIRST_ERROR_ONCE)
        vm.ensureInitialLoad(); advanceUntilIdle()
        val dataset = vm.uiState.value.datasetId
        assertEquals(FeedPageState.ERROR, vm.uiState.value.pageState)
        assertNotNull(vm.retry()); advanceUntilIdle()
        assertEquals(dataset, vm.uiState.value.datasetId)
        assertEquals(FeedPageState.CONTENT, vm.uiState.value.pageState)
        vm.replay(); advanceUntilIdle()
        assertEquals(dataset + 1, vm.uiState.value.datasetId)
        assertEquals(FeedPageState.ERROR, vm.uiState.value.pageState)
    }

    @Test fun `刷新替换首屏 版本变化而ID保持 成功为空清空列表`() = runTest(dispatcher) {
        val vm = model()
        vm.ensureInitialLoad(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        val old = vm.uiState.value
        vm.refresh()
        assertEquals(old.items, vm.uiState.value.items)
        assertEquals(FeedPageState.CONTENT, vm.uiState.value.pageState)
        advanceUntilIdle()
        assertEquals(old.items.take(13).map { it.stableId }, vm.uiState.value.items.map { it.stableId })
        assertNotEquals(old.items.take(13), vm.uiState.value.items)
        assertEquals(2, vm.uiState.value.nextPage)
        val empty = model(FeedScenario.REFRESH_TO_EMPTY)
        empty.ensureInitialLoad(); advanceUntilIdle()
        empty.refresh(); advanceUntilIdle()
        assertEquals(FeedPageState.EMPTY, empty.uiState.value.pageState)
        assertEquals(emptyList<FeedItem>(), empty.uiState.value.items)
        assertEquals(0, empty.uiState.value.recordCount)
        assertNull(empty.uiState.value.nextPage)
    }

    @Test fun `刷新失败保留已有数据游标和无更多状态`() = runTest(dispatcher) {
        val vm = model(FeedScenario.REFRESH_ERROR)
        vm.ensureInitialLoad(); advanceUntilIdle()
        repeat(2) { vm.loadMore(); advanceUntilIdle() }
        val before = vm.uiState.value
        vm.refresh(); advanceUntilIdle()
        assertEquals(before.items, vm.uiState.value.items)
        assertEquals(before.nextPage, vm.uiState.value.nextPage)
        assertEquals(before.dataVersion, vm.uiState.value.dataVersion)
        assertEquals(FeedPageState.CONTENT, vm.uiState.value.pageState)
        assertEquals(CompletionStatus.FAILURE, vm.uiState.value.lastCompletion?.status)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test fun `分页失败不丢数据不推进 重试同页只追加一次`() = runTest(dispatcher) {
        val vm = model(FeedScenario.LOAD_MORE_ERROR_ONCE)
        vm.ensureInitialLoad(); advanceUntilIdle()
        val before = vm.uiState.value
        vm.loadMore(); advanceUntilIdle()
        assertEquals(before.items, vm.uiState.value.items)
        assertEquals(before.nextPage, vm.uiState.value.nextPage)
        assertEquals(before.dataVersion, vm.uiState.value.dataVersion)
        assertEquals(2, vm.loadMore()?.page); advanceUntilIdle()
        assertEquals(20, vm.uiState.value.recordCount)
        assertEquals(23, vm.uiState.value.items.size)
        assertEquals(3, vm.uiState.value.nextPage)
    }

    @Test fun `空末页保留Content且结束分页`() = runTest(dispatcher) {
        val vm = model(FeedScenario.EMPTY_LAST_PAGE)
        vm.ensureInitialLoad(); advanceUntilIdle()
        val items = vm.uiState.value.items
        vm.loadMore(); advanceUntilIdle()
        assertEquals(items, vm.uiState.value.items)
        assertEquals(FeedPageState.CONTENT, vm.uiState.value.pageState)
        assertNull(vm.uiState.value.nextPage)
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test fun `参数为请求快照 延迟与布局变更不重发网络`() = runTest(dispatcher) {
        val repo = CountingRepository()
        val vm = model(repository = repo)
        vm.ensureInitialLoad()
        vm.setLatency(1500)
        vm.setLayoutMode(FeedLayoutMode.GRID)
        advanceUntilIdle()
        assertEquals(100L, repo.requests.single().latencyMillis)
        assertEquals(FeedLayoutMode.GRID, vm.uiState.value.layoutMode)
        val version = vm.uiState.value.dataVersion
        vm.setLayoutMode(FeedLayoutMode.LINEAR)
        assertEquals(version, vm.uiState.value.dataVersion)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1500L, repo.requests.last().latencyMillis)
    }

    @Test fun `取消后的旧成功和finally不能覆盖新请求`() = runTest(dispatcher) {
        val repo = UncooperativeRepository()
        val vm = model(repository = repo)
        vm.ensureInitialLoad(); runCurrent()
        val newest = vm.replay(); runCurrent()
        repo.pending[0].second.complete(FeedPage(listOf(FeedRecord.Profile(9, 9)), null))
        runCurrent()
        assertEquals(newest, vm.uiState.value.activeRequest)
        assertEquals(0, vm.uiState.value.dataVersion)
        repo.pending[1].second.complete(FeedPage(listOf(FeedRecord.Profile(1, 1)), null))
        runCurrent()
        assertEquals(1, vm.uiState.value.recordCount)
        assertEquals(newest, vm.uiState.value.lastCompletion?.token)
    }

    @Test fun `新结果已完成时 旧失败不能改错误和完成记录`() = runTest(dispatcher) {
        val repo = UncooperativeRepository()
        val vm = model(repository = repo)
        vm.ensureInitialLoad(); runCurrent()
        vm.selectScenario(FeedScenario.FIRST_EMPTY); runCurrent()
        repo.pending[1].second.complete(FeedPage(emptyList(), null)); runCurrent()
        val committed = vm.uiState.value
        repo.pending[0].second.completeExceptionally(IOException("过期失败")); runCurrent()
        assertEquals(committed, vm.uiState.value)
    }

    @Test fun `取消异常不转换为网络Error`() = runTest(dispatcher) {
        val vm = model(repository = FeedRepository { throw CancellationException("主动取消") })
        vm.ensureInitialLoad(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
        assertNotEquals(FeedPageState.ERROR, vm.uiState.value.pageState)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(CompletionStatus.CANCELLED, vm.uiState.value.lastCompletion?.status)
    }

    @Test fun `重复ID 空页带游标和错误游标均按当前请求失败处理`() = runTest(dispatcher) {
        val responses = listOf(
            FeedPage(listOf(FeedRecord.Profile(1, 1)), null),
            FeedPage(emptyList(), 3),
            FeedPage(listOf(FeedRecord.Profile(2, 1)), 2),
        )
        for (bad in responses) {
            val vm = model(repository = FeedRepository { request ->
                if (request.token.kind == RequestKind.INITIAL) FeedPage(listOf(FeedRecord.Profile(1, 1)), 2) else bad
            })
            vm.ensureInitialLoad(); advanceUntilIdle()
            val before = vm.uiState.value
            vm.loadMore(); advanceUntilIdle()
            assertEquals(before.items, vm.uiState.value.items)
            assertEquals(before.nextPage, vm.uiState.value.nextPage)
            assertEquals(before.dataVersion, vm.uiState.value.dataVersion)
            assertEquals(CompletionStatus.FAILURE, vm.uiState.value.lastCompletion?.status)
            assertFalse(vm.uiState.value.isBusy)
        }
    }

    @Test fun `进程重建只恢复参数并重新初始化 不恢复历史请求和全量数据`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val old = model(handle = handle)
        old.selectScenario(FeedScenario.NO_MORE_IMMEDIATE)
        old.setLatency(500)
        old.setLayoutMode(FeedLayoutMode.GRID)
        advanceUntilIdle()
        val fresh = model(handle = handle)
        assertEquals(FeedScenario.NO_MORE_IMMEDIATE, fresh.uiState.value.scenario)
        assertEquals(500L, fresh.uiState.value.latencyMillis)
        assertEquals(FeedLayoutMode.GRID, fresh.uiState.value.layoutMode)
        assertEquals(0, fresh.uiState.value.dataVersion)
        assertTrue(fresh.uiState.value.items.isEmpty())
        assertNull(fresh.uiState.value.activeRequest)
        assertNull(fresh.uiState.value.lastCompletion)
        fresh.ensureInitialLoad(); advanceUntilIdle()
        assertEquals(10, fresh.uiState.value.recordCount)
    }
}
