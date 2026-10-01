package com.sfyc.ssl.internal

import com.sfyc.ssl.internal.time.Cancellable
import com.sfyc.ssl.internal.time.MonotonicClock
import com.sfyc.ssl.internal.time.TaskScheduler

/**
 * 状态协调器单元测试的可手动推进时间 helper。
 *
 * 用确定性 Fake 代替主线程 Handler：测试通过 [advanceBy] / [runDue] 精确控制
 * 延迟任务的触发时机，覆盖显示延迟、最短展示、任务取消等全部竞态分支。
 */
internal class FakeClock(var nowMillis: Long = 10_000L) : MonotonicClock {

    override fun nowMillis(): Long = nowMillis
}

/**
 * 可手动推进的 Fake 调度器。
 *
 * - [postDelayed] 按当前 Fake 时间计算触发点并记录，不自动执行。
 * - [advanceBy] 推进时钟并按触发顺序执行到期任务。
 * - 已取消条目永不执行；测试可直接调用捕获到的 task 模拟
 *   “不遵守取消约定的调度器”，验证 generation token 防线。
 */
internal class FakeScheduler(private val clock: FakeClock) : TaskScheduler {

    inner class Entry(
        val runAtMillis: Long,
        val task: () -> Unit,
    ) {
        var cancelled: Boolean = false
            private set

        fun invokeDirectly() = task()

        fun cancelEntry() {
            cancelled = true
        }
    }

    private val entries = mutableListOf<Entry>()

    /** 全部已调度的条目（含已取消），供测试取 task 做异常投递模拟。 */
    val allEntries: List<Entry>
        get() = entries.toList()

    /** 尚未取消也未执行的条目数。 */
    val pendingCount: Int
        get() = entries.count { !it.cancelled }

    override fun postDelayed(delayMillis: Long, task: () -> Unit): Cancellable {
        val entry = Entry(clock.nowMillis + delayMillis.coerceAtLeast(0L), task)
        entries += entry
        return Cancellable { entry.cancelEntry() }
    }

    /** 推进 [millis] 毫秒，期间到期的任务按触发点顺序执行。 */
    fun advanceBy(millis: Long) {
        require(millis >= 0) { "advanceBy 不接受负数" }
        clock.nowMillis += millis
        runDue()
    }

    /** 执行当前时刻所有到期任务（含任务内新调度的零延迟任务）。 */
    fun runDue() {
        while (true) {
            val due = entries
                .filter { !it.cancelled && it.runAtMillis <= clock.nowMillis }
                .minByOrNull { it.runAtMillis }
                ?: break
            entries.remove(due)
            due.task()
        }
    }
}
