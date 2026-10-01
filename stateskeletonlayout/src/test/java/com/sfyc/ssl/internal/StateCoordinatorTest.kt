package com.sfyc.ssl.internal

import com.sfyc.ssl.StateLayoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StateCoordinator] 状态时序测试（对应实现方案第 24.1 节）。
 *
 * 每组用例注释说明其业务意图：验证“最新状态优先、延迟与最短展示、
 * 任务取消、实例隔离”等不变量，而非实现细节。
 */
class StateCoordinatorTest {

    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeScheduler
    private lateinit var coordinator: StateCoordinator
    private lateinit var presented: MutableList<StateLayoutState>

    @Before
    fun setUp() {
        clock = FakeClock()
        scheduler = FakeScheduler(clock)
        coordinator = StateCoordinator(clock, scheduler)
        presented = mutableListOf()
        coordinator.onPresent = { state, commit ->
            presented += state
            commit()
        }
    }

    @Test
    fun `1-初始状态为Content且无待执行任务`() {
        // 业务意图：控件创建后默认展示业务内容，不应有悬挂的延迟任务。
        assertEquals(StateLayoutState.CONTENT, coordinator.requestedState)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertFalse(coordinator.hasPendingWork)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `2-无延迟时Loading立即显示并记录开始时间`() {
        // 业务意图：首次加载默认零延迟，立即给用户反馈。
        coordinator.request(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.LOADING, coordinator.requestedState)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        assertEquals(listOf(StateLayoutState.LOADING), presented)
        assertFalse(coordinator.hasPendingWork)
    }

    @Test
    fun `3-延迟到期前不显示Loading到期后显示`() {
        // 业务意图：短暂抖动不应闪出骨架；延迟到期后仍需要 Loading 才显示。
        coordinator.loadingShowDelayMillis = 200L
        coordinator.request(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertTrue(presented.isEmpty())
        assertTrue(coordinator.hasPendingWork)

        scheduler.advanceBy(199L)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertTrue(presented.isEmpty())

        scheduler.advanceBy(1L)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        assertEquals(listOf(StateLayoutState.LOADING), presented)
    }

    @Test
    fun `4-延迟期内完成请求则跳过Loading直接呈现目标`() {
        // 业务意图：100ms 级快速请求不应闪现骨架，直接呈现结果页。
        coordinator.loadingShowDelayMillis = 200L
        coordinator.request(StateLayoutState.LOADING)
        scheduler.advanceBy(50L)
        coordinator.request(StateLayoutState.CONTENT)

        scheduler.advanceBy(10_000L)

        assertEquals(
            "Loading 应被跳过，历史呈现中不应出现 LOADING",
            listOf(StateLayoutState.CONTENT),
            presented,
        )
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertFalse(coordinator.hasPendingWork)
    }

    @Test
    fun `5-最短展示时间内收到Content到期后才提交`() {
        // 业务意图：骨架一旦上屏至少停留 300ms，避免一闪而过。
        coordinator.request(StateLayoutState.LOADING)
        presented.clear()

        scheduler.advanceBy(100L)
        coordinator.request(StateLayoutState.CONTENT)

        // 最短时间未满足：仍停留在 Loading，且只有一个待退出任务。
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        assertTrue(presented.isEmpty())
        assertTrue(coordinator.hasPendingWork)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advanceBy(199L)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)

        scheduler.advanceBy(1L)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertEquals(listOf(StateLayoutState.CONTENT), presented)
    }

    @Test
    fun `6-最短展示时间内连续请求最终只呈现Error`() {
        // 业务意图：快速连续的状态翻转只呈现最终有效状态，不闪回中间态。
        coordinator.request(StateLayoutState.LOADING)
        presented.clear()

        scheduler.advanceBy(100L)
        coordinator.request(StateLayoutState.CONTENT)
        coordinator.request(StateLayoutState.EMPTY)
        coordinator.request(StateLayoutState.ERROR)

        // 多次覆盖不得重复创建任务。
        assertEquals(1, scheduler.pendingCount)
        assertTrue(presented.isEmpty())

        scheduler.advanceBy(10_000L)
        assertEquals(listOf(StateLayoutState.ERROR), presented)
        assertEquals(StateLayoutState.ERROR, coordinator.renderedState)
    }

    @Test
    fun `7-等待退出时重新请求Loading则取消退出并继续Loading`() {
        // 业务意图：新的加载意图到达时，取消待退出任务且不重置开始时间。
        coordinator.request(StateLayoutState.LOADING)
        presented.clear()
        val shownAt = clock.nowMillis

        scheduler.advanceBy(100L)
        coordinator.request(StateLayoutState.CONTENT)
        assertTrue(coordinator.hasPendingWork)

        coordinator.request(StateLayoutState.LOADING)
        assertFalse("待退出任务应被取消", coordinator.hasPendingWork)
        assertTrue("不应重复呈现 Loading", presented.isEmpty())

        // 开始时间不变：从 shownAt 起 300ms 后仍可立即退出。
        scheduler.advanceBy(150L) // shownAt + 250
        coordinator.request(StateLayoutState.EMPTY)
        assertTrue(coordinator.hasPendingWork)
        scheduler.advanceBy(50L) // shownAt + 300
        assertEquals(StateLayoutState.EMPTY, coordinator.renderedState)
        assertEquals(shownAt + 300L, clock.nowMillis)
    }

    @Test
    fun `8-重复提交相同状态不重置计时`() {
        // 业务意图：重复 render(LOADING) 不得延长等待，也不得重复回调。
        coordinator.loadingShowDelayMillis = 200L
        coordinator.request(StateLayoutState.LOADING)
        coordinator.request(StateLayoutState.LOADING)

        assertEquals(1, scheduler.pendingCount)
        scheduler.advanceBy(200L)
        assertEquals(listOf(StateLayoutState.LOADING), presented)

        coordinator.request(StateLayoutState.LOADING)
        assertEquals(listOf(StateLayoutState.LOADING), presented)
        assertFalse(coordinator.hasPendingWork)
    }

    @Test
    fun `9-已取消的旧任务即使被异常投递也不覆盖新状态`() {
        // 业务意图：generation token 是最后一道防线，调度器异常也无法闪回旧状态。
        coordinator.loadingShowDelayMillis = 200L
        coordinator.request(StateLayoutState.LOADING)
        val staleShowTask = scheduler.allEntries.single()

        coordinator.request(StateLayoutState.CONTENT)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        presented.clear()

        // 模拟不遵守取消约定的调度器，把已取消任务再投递一次。
        staleShowTask.invokeDirectly()

        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertTrue("旧任务不得产生新的呈现", presented.isEmpty())
    }

    @Test
    fun `10-detach取消任务attach后按最新目标恢复`() {
        // 业务意图：切后台不泄露延迟任务；返回后按最新请求恢复，不恢复过期中间态。
        coordinator.loadingShowDelayMillis = 200L
        coordinator.request(StateLayoutState.LOADING)
        assertTrue(coordinator.hasPendingWork)

        coordinator.onDetachedFromWindow()
        assertFalse(coordinator.hasPendingWork)
        scheduler.advanceBy(10_000L)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)

        coordinator.onAttachedToWindow()
        // attach 后计时重新开始：立即仍是 CONTENT，延迟到期后呈现 LOADING。
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        scheduler.advanceBy(200L)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
    }

    @Test
    fun `10b-等待退出时detachattach后直接提交最新目标`() {
        // 业务意图：退出等待被 detach 中断后，attach 时若最短时间已过则直接提交。
        coordinator.request(StateLayoutState.LOADING)
        scheduler.advanceBy(100L)
        coordinator.request(StateLayoutState.ERROR)
        assertTrue(coordinator.hasPendingWork)

        coordinator.onDetachedFromWindow()
        scheduler.advanceBy(10_000L)
        coordinator.onAttachedToWindow()

        assertEquals(StateLayoutState.ERROR, coordinator.renderedState)
        assertEquals(
            listOf(StateLayoutState.LOADING, StateLayoutState.ERROR),
            presented,
        )
    }

    @Test
    fun `11-两个协调器实例互不影响`() {
        // 业务意图：同一页面的多个控件各自独立计时，不共享全局状态。
        val other = StateCoordinator(clock, scheduler).also {
            it.loadingShowDelayMillis = 500L
        }
        val otherPresented = mutableListOf<StateLayoutState>()
        other.onPresent = { state, commit ->
            otherPresented += state
            commit()
        }

        coordinator.request(StateLayoutState.LOADING)
        other.request(StateLayoutState.LOADING)
        scheduler.advanceBy(300L)

        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        assertEquals(StateLayoutState.CONTENT, other.renderedState)
        assertTrue(otherPresented.isEmpty())

        scheduler.advanceBy(200L)
        assertEquals(StateLayoutState.LOADING, other.renderedState)
    }

    @Test
    fun `12-最短展示为0时离开Loading即时切换`() {
        // 业务意图：minimumLoadingDurationMillis=0 关闭保底，状态直通。
        coordinator.minimumLoadingDurationMillis = 0L
        coordinator.request(StateLayoutState.LOADING)
        presented.clear()

        coordinator.request(StateLayoutState.EMPTY)

        assertFalse(coordinator.hasPendingWork)
        assertEquals(listOf(StateLayoutState.EMPTY), presented)
        assertEquals(StateLayoutState.EMPTY, coordinator.renderedState)
    }

    @Test
    fun `相同非Loading状态重复提交为幂等空操作`() {
        // 业务意图：重复 render 同一结果页不重建、不重回调。
        coordinator.request(StateLayoutState.EMPTY)
        presented.clear()

        coordinator.request(StateLayoutState.EMPTY)

        assertTrue(presented.isEmpty())
        assertFalse(coordinator.hasPendingWork)
        assertEquals(StateLayoutState.EMPTY, coordinator.renderedState)
    }

    @Test
    fun `View提交完成前不推进renderedState且从实际提交时刻计算最短展示`() {
        var commitLoading: (() -> Unit)? = null
        coordinator.onPresent = { state, commit ->
            presented += state
            if (state == StateLayoutState.LOADING) {
                commitLoading = commit
            } else {
                commit()
            }
        }

        coordinator.request(StateLayoutState.LOADING)
        scheduler.advanceBy(1_000L)

        assertEquals(StateLayoutState.LOADING, coordinator.requestedState)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertTrue(coordinator.hasPendingWork)

        commitLoading!!.invoke()
        scheduler.advanceBy(100L)
        coordinator.request(StateLayoutState.CONTENT)

        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        scheduler.advanceBy(199L)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        scheduler.advanceBy(1L)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
    }

    @Test
    fun `呈现失败不改变状态且修正后可重试同一目标`() {
        coordinator.onPresent = { _, _ -> error("模拟 View 提交失败") }

        try {
            coordinator.request(StateLayoutState.EMPTY)
            throw AssertionError("呈现失败应同步抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("模拟 View 提交失败", expected.message)
        }

        assertEquals(StateLayoutState.CONTENT, coordinator.requestedState)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertFalse(coordinator.hasPendingWork)

        coordinator.onPresent = { state, commit ->
            presented += state
            commit()
        }
        coordinator.request(StateLayoutState.EMPTY)

        assertEquals(StateLayoutState.EMPTY, coordinator.requestedState)
        assertEquals(StateLayoutState.EMPTY, coordinator.renderedState)
    }

    @Test
    fun `detach后请求只记录最终意图且attach时只调度一次`() {
        coordinator.loadingShowDelayMillis = 200L
        coordinator.onDetachedFromWindow()

        coordinator.request(StateLayoutState.LOADING)
        coordinator.request(StateLayoutState.EMPTY)
        coordinator.request(StateLayoutState.LOADING)

        assertEquals(StateLayoutState.LOADING, coordinator.requestedState)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        assertFalse(coordinator.hasPendingWork)
        assertEquals(0, scheduler.pendingCount)

        coordinator.onAttachedToWindow()

        assertEquals(1, scheduler.pendingCount)
        scheduler.advanceBy(200L)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        assertEquals(listOf(StateLayoutState.LOADING), presented)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负数显示延迟直接抛异常`() {
        coordinator.loadingShowDelayMillis = -1L
    }

    @Test fun `恢复初始Loading也在真实commit后开始最短展示计时`() {
        var complete: (() -> Unit)? = null
        coordinator.onPresent = { state, commit ->
            if (state == StateLayoutState.LOADING) complete = commit else commit()
        }
        coordinator.restoreRequested(StateLayoutState.LOADING)
        scheduler.advanceBy(1000)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
        complete!!.invoke()
        coordinator.request(StateLayoutState.CONTENT)
        scheduler.advanceBy(299)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        scheduler.advanceBy(1)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
    }

    @Test fun `重新挂载继续同一Loading时重新建立最短展示窗口`() {
        coordinator.request(StateLayoutState.LOADING)
        scheduler.advanceBy(1000)
        coordinator.onDetachedFromWindow()
        scheduler.advanceBy(1000)
        coordinator.onAttachedToWindow()
        coordinator.request(StateLayoutState.CONTENT)
        scheduler.advanceBy(299)
        assertEquals(StateLayoutState.LOADING, coordinator.renderedState)
        scheduler.advanceBy(1)
        assertEquals(StateLayoutState.CONTENT, coordinator.renderedState)
    }

    @Test fun `超长最短展示时间的剩余时长计算不发生加法溢出`() {
        var delay = -1L
        val recordingScheduler = object : com.sfyc.ssl.internal.time.TaskScheduler {
            override fun postDelayed(delayMillis: Long, task: () -> Unit): com.sfyc.ssl.internal.time.Cancellable {
                delay = delayMillis
                return com.sfyc.ssl.internal.time.Cancellable { }
            }
        }
        val longCoordinator = StateCoordinator(clock, recordingScheduler, minimumLoadingDurationMillis = Long.MAX_VALUE)
        longCoordinator.request(StateLayoutState.LOADING)
        clock.nowMillis += 100
        longCoordinator.request(StateLayoutState.CONTENT)
        assertEquals(Long.MAX_VALUE - 100, delay)
        assertEquals(StateLayoutState.LOADING, longCoordinator.renderedState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负数最短展示直接抛异常`() {
        coordinator.minimumLoadingDurationMillis = -1L
    }
}
