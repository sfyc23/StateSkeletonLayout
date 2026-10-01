package com.sfyc.ssl.internal

import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.internal.time.Cancellable
import com.sfyc.ssl.internal.time.MonotonicClock
import com.sfyc.ssl.internal.time.TaskScheduler

/**
 * 纯 Kotlin 状态协调器：页面状态显示的唯一调度入口。
 *
 * # 设计目标
 *
 * 把“状态竞争、加载计时、延迟任务取消”这类复杂性收敛到一个
 * 不持有任何 Android View / Context / Bitmap / Animator 的纯逻辑单元，
 * 从而可以用 JVM 单元测试覆盖全部竞态分支。View 层只负责执行
 * [onPresent] 发出的呈现命令，不做任何时序决策。
 *
 * # 两个状态
 *
 * - [requestedState]：最近一次请求的目标状态（调用方意图）。
 * - [renderedState]：当前已经呈现给用户的状态（画面事实）。
 *
 * 区分两者是为了支持加载显示延迟与最短展示时间：请求成功不等于
 * 画面已经切换完成。公开文档与 README 必须向调用方明确这一区别。
 *
 * # 状态规则（与实现方案第 12.3 节一致）
 *
 * - 非 Loading → Loading：启动可选显示延迟（`loadingShowDelayMillis`）。
 * - Loading 尚未真正显示 → Content/Empty/Error：取消待显示任务，直接呈现目标。
 * - Loading 已显示且未满足最短时间 → Content/Empty/Error：保存最新目标，
 *   到期后提交；期间再收到其它非 Loading 状态则覆盖旧目标，不重复创建任务。
 * - 等待退出 Loading 期间再次收到 Loading：取消待退出任务，继续当前
 *   Loading，不重置开始时间。
 * - 请求相同状态：幂等，不重建 View、不重启动画、不重置计时。
 * - View detach：取消全部延迟任务并保留最新请求状态；attach 时若
 *   已呈现状态与最新请求不一致，则按最新请求重新走一遍调度
 *   （计时重新开始，进程重建后不继续旧的最短展示倒计时）。
 *
 * # Generation Token
 *
 * 每次调度延迟任务都分配递增的 generation；任务执行前先校验 token，
 * 防止已取消的旧任务覆盖新状态。任何 [request] / detach / attach
 * 调度路径都会先取消旧任务再推进 generation，因此同一时间最多只存在
 * 一个待执行任务（[hasPendingWork] 可用于测试断言）。
 *
 * 所有公开方法都必须在主线程调用（View 层保证），本类自身不做线程切换。
 */
internal class StateCoordinator(
    private val clock: MonotonicClock,
    private val scheduler: TaskScheduler,
    initialState: StateLayoutState = StateLayoutState.CONTENT,
    loadingShowDelayMillis: Long = DEFAULT_LOADING_SHOW_DELAY_MILLIS,
    minimumLoadingDurationMillis: Long = DEFAULT_MINIMUM_LOADING_DURATION_MILLIS,
) {

    /**
     * 请求 Loading 后延迟多久才真正显示加载页（毫秒）。
     *
     * 默认 0（首次加载立即反馈）。延迟窗口内若收到非 Loading 状态，
     * Loading 将直接被跳过，避免快速请求引起闪烁。
     */
    var loadingShowDelayMillis: Long = loadingShowDelayMillis
        set(value) {
            require(value >= 0) { "loadingShowDelayMillis 不得小于 0，当前=$value" }
            field = value
        }

    /**
     * Loading 一旦显示，至少保留多久（毫秒）。
     *
     * 默认 300。窗口内收到的非 Loading 目标会被暂存，到期后只提交最新者。
     */
    var minimumLoadingDurationMillis: Long = minimumLoadingDurationMillis
        set(value) {
            require(value >= 0) { "minimumLoadingDurationMillis 不得小于 0，当前=$value" }
            field = value
        }

    /** 最近一次请求的目标状态。 */
    var requestedState: StateLayoutState = initialState
        private set

    /** 当前已经呈现给用户的状态。 */
    var renderedState: StateLayoutState = initialState
        private set

    /**
     * 呈现命令出口：View 层完成画面提交后调用第二个参数 [commit]。
     *
     * 呈现可以同步完成，也可以等待 View 布局后再提交；在 [commit] 前
     * [renderedState] 始终表示旧画面。若回调同步抛出异常，协调器回滚
     * [requestedState]，调用方修正配置后可重新请求同一目标。
     */
    var onPresent: ((state: StateLayoutState, commit: () -> Unit) -> Unit)? = null

    /** View 成功提交并推进 [renderedState] 后触发。 */
    var onPresented: ((StateLayoutState) -> Unit)? = null

    // 待执行延迟任务携带的 generation；任务执行前必须校验，防止旧任务覆盖新状态。
    private var generation: Int = 0

    // 同一时间最多存在一个待执行任务；新调度前一律先取消旧任务。
    private var pendingTask: Cancellable? = null

    // 等待退出 Loading 时暂存的最新非 Loading 目标；其余情况下为 null。
    private var pendingTarget: StateLayoutState? = null

    // 等待 View 层异步提交的目标；不计入 renderedState，且会被新请求的 token 取消。
    private var pendingPresentation: StateLayoutState? = null

    // 构造后保持兼容的可调度状态；第一次 detach 后严格禁止创建新任务，直到 attach。
    private var attached: Boolean = true

    // Loading 最近一次真正呈现的单调时间；-1 表示尚未呈现过（初始恢复场景）。
    private var loadingShownAtMillis: Long = -1L

    init {
        require(loadingShowDelayMillis >= 0) { "loadingShowDelayMillis 不得小于 0" }
        require(minimumLoadingDurationMillis >= 0) { "minimumLoadingDurationMillis 不得小于 0" }
    }

    /** 是否存在尚未执行的延迟任务（主要供测试断言与调试使用）。 */
    val hasPendingWork: Boolean
        get() = pendingTask != null || pendingPresentation != null

    /**
     * 提交目标状态。相同状态重复提交为幂等空操作。
     */
    fun request(state: StateLayoutState) {
        if (state == requestedState) {
            // 幂等：不重建、不重启动画、不重置计时。
            // 唯一例外：正在等待退出 Loading 时重新请求 Loading，
            // 取消待退出任务并继续当前 Loading（开始时间不变）。
            if (state == StateLayoutState.LOADING && renderedState == StateLayoutState.LOADING) {
                cancelPending()
            }
            return
        }
        if (state == StateLayoutState.LOADING && renderedState == StateLayoutState.LOADING) {
            // 等待退出期间的新 Loading 意图：只取消待退出任务，不重新呈现、
            // 不重置开始时间（避免骨架动画重启与计时延长）。
            cancelPending()
            requestedState = state
            return
        }
        requestedState = state
        if (!attached) {
            cancelPending()
            return
        }
        dispatch(state)
    }

    /**
     * View detach 时调用：停止全部延迟任务，但保留最新请求状态。
     *
     * 不重置 [requestedState] / [renderedState]，attach 后可据此恢复。
     */
    fun onDetachedFromWindow() {
        attached = false
        cancelPending()
        loadingShownAtMillis = -1L
    }

    /**
     * View attach 时调用：若已呈现状态落后于最新请求，按最新请求重新调度。
     *
     * 计时重新开始（不恢复 detach 前的剩余倒计时）；进程重建后的首次
     * attach 同理，View 层会先以无动画方式恢复结构状态。
     */
    fun onAttachedToWindow() {
        attached = true
        if (renderedState == StateLayoutState.LOADING && requestedState == renderedState && loadingShownAtMillis < 0L) {
            loadingShownAtMillis = clock.nowMillis()
        }
        if (renderedState != requestedState) {
            dispatch(requestedState)
        }
    }

    /**
     * 状态恢复入口（SavedState）：无回调地将请求与呈现状态同步为 [state]。
     *
     * 最短展示计时不恢复（[loadingShownAtMillis] 置空），进程重建后不继续
     * 旧的倒计时；ViewModel 随后提交的新状态优先级更高。
     */
    internal fun restore(state: StateLayoutState) {
        cancelPending()
        requestedState = state
        renderedState = state
        loadingShownAtMillis = -1L
    }

    /** 初始态 / 恢复态也走真实提交入口，Loading 计时从遮罩或布局就绪后开始。 */
    internal fun restoreRequested(state: StateLayoutState) {
        cancelPending()
        requestedState = state
        loadingShownAtMillis = -1L
        if (attached) present(state)
    }

    // 按当前规则把目标状态“立即呈现”或“延迟调度”，调用前已保证 requestedState 为最新值。
    private fun dispatch(state: StateLayoutState) {
        cancelPending()
        if (!attached) return
        if (state == StateLayoutState.LOADING) {
            if (loadingShowDelayMillis <= 0L) {
                present(StateLayoutState.LOADING)
            } else {
                val token = nextGeneration()
                pendingTarget = null
                pendingTask = scheduler.postDelayed(loadingShowDelayMillis) {
                    if (!isCurrent(token)) return@postDelayed
                    pendingTask = null
                    // 二次确认：防止不遵守取消约定的调度器把过期任务投递进来。
                    if (requestedState == StateLayoutState.LOADING) {
                        present(StateLayoutState.LOADING)
                    }
                }
            }
            return
        }
        // 非 Loading 目标。
        val now = clock.nowMillis()
        if (renderedState == StateLayoutState.LOADING && !isMinimumDurationElapsed(now)) {
            // Loading 已显示但最短时间未满足：暂存最新目标，到期提交。
            val token = nextGeneration()
            pendingTarget = state
            pendingTask = scheduler.postDelayed(remainingMinimumMillis(now)) {
                if (!isCurrent(token)) return@postDelayed
                pendingTask = null
                val latest = pendingTarget
                pendingTarget = null
                // token 有效意味着期间没有新请求，latest 即最新目标；
                // 防御性地以 requestedState 为准，两者正常情况下一致。
                val target = if (latest != null && requestedState == latest) latest else requestedState
                if (target != StateLayoutState.LOADING) {
                    present(target)
                } else {
                    // 理论不可达：等待退出期间重新请求 Loading 会走 request() 的取消路径。
                    // 若调度器异常投递，回到安全状态：保持 Loading。
                    present(StateLayoutState.LOADING)
                }
            }
        } else {
            present(state)
        }
    }

    // 请求 View 层呈现；只有 View 明确 commit 后才推进画面事实与 Loading 计时。
    private fun present(state: StateLayoutState) {
        val token = nextGeneration()
        pendingPresentation = state
        var committed = false
        val commit = commit@{
            if (!isCurrent(token) || pendingPresentation != state) return@commit
            pendingPresentation = null
            renderedState = state
            if (state == StateLayoutState.LOADING) {
                loadingShownAtMillis = clock.nowMillis()
            } else {
                loadingShownAtMillis = -1L
            }
            committed = true
            onPresented?.invoke(state)
        }
        try {
            val presenter = onPresent
            if (presenter == null) {
                commit()
            } else {
                presenter(state, commit)
            }
        } catch (failure: Throwable) {
            if (!committed && isCurrent(token)) {
                pendingPresentation = null
                requestedState = renderedState
                generation++
            }
            throw failure
        }
    }

    private fun isMinimumDurationElapsed(now: Long): Boolean {
        if (minimumLoadingDurationMillis <= 0L) return true
        if (loadingShownAtMillis < 0L) return true
        return now - loadingShownAtMillis >= minimumLoadingDurationMillis
    }

    private fun remainingMinimumMillis(now: Long): Long {
        if (minimumLoadingDurationMillis <= 0L) return 0L
        if (loadingShownAtMillis < 0L) return 0L
        return (minimumLoadingDurationMillis - (now - loadingShownAtMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    // 取消待执行任务并推进 generation，使已取消任务的 token 全部失效。
    private fun cancelPending() {
        pendingTask?.cancel()
        pendingTask = null
        pendingTarget = null
        pendingPresentation = null
        generation++
    }

    private fun nextGeneration(): Int {
        generation++
        return generation
    }

    private fun isCurrent(token: Int): Boolean = token == generation

    companion object {
        /** 默认加载显示延迟：0ms，首次加载立即反馈。 */
        const val DEFAULT_LOADING_SHOW_DELAY_MILLIS: Long = 0L

        /** 默认最短展示时间：300ms，避免快速请求引起闪烁。 */
        const val DEFAULT_MINIMUM_LOADING_DURATION_MILLIS: Long = 300L
    }
}
