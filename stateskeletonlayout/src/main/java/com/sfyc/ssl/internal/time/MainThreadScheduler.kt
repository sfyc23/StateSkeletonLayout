package com.sfyc.ssl.internal.time

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 可取消的延迟任务句柄。
 *
 * 由 [TaskScheduler.postDelayed] 返回；调用 [cancel] 后，
 * 对应的任务必须不再执行（状态协调器依赖该语义取消过期任务）。
 */
internal fun interface Cancellable {

    /** 取消尚未执行的任务；已执行或已取消时调用无副作用。 */
    fun cancel()
}

/**
 * 延迟任务调度器：状态协调器与 Android 主线程 Handler 之间的接缝。
 *
 * 生产环境使用 [MainThreadScheduler]；单元测试使用可手动推进时间的
 * Fake 实现，从而在 JVM 上确定性验证全部状态竞争逻辑。
 */
internal interface TaskScheduler {

    /**
     * 在 [delayMillis] 毫秒后执行 [task]。
     *
     * @return 可取消句柄；调用方必须在任务过期时取消，避免旧任务覆盖新状态。
     */
    fun postDelayed(delayMillis: Long, task: () -> Unit): Cancellable
}

/**
 * 基于主线程 [Handler] 的生产调度器实现。
 *
 * 使用 [SystemClock.uptimeMillis] 与 [MonotonicClock] 保持同一时间基准。
 */
internal class MainThreadScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TaskScheduler {

    override fun postDelayed(delayMillis: Long, task: () -> Unit): Cancellable {
        // lateinit 避免可空 var 被闭包捕获时的智能转换限制；
        // 任务执行后 cancel 再调用 removeCallbacks 是无副作用的空操作。
        lateinit var runnable: Runnable
        runnable = Runnable { task() }
        val now = SystemClock.uptimeMillis()
        val safeDelay = delayMillis.coerceIn(0L, Long.MAX_VALUE - now)
        handler.postAtTime(runnable, now + safeDelay)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * 基于 [SystemClock.uptimeMillis] 的生产单调时钟实现。
 */
internal class UptimeMonotonicClock : MonotonicClock {

    override fun nowMillis(): Long = SystemClock.uptimeMillis()
}
