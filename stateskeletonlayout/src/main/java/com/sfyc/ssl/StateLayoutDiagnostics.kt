package com.sfyc.ssl

/** 主线程上按需获取的诊断快照，不持有 View / Bitmap，也不上传数据。 */
data class StateLayoutDiagnostics(
    val requestedState: StateLayoutState,
    val renderedState: StateLayoutState,
    val effectiveUseSkeleton: Boolean,
    val maskWidth: Int,
    val maskHeight: Int,
    val maskBytes: Int,
    val maskBuildCount: Int,
    val fallbackReason: SkeletonFallbackReason,
    val effectRunning: Boolean,
    val hasPendingWork: Boolean,
    /**
     * 取快照时是否正处于状态页创建 / 绑定的中间态。
     *
     * 为 true 时 [StateLayoutState.EMPTY] / `ERROR` / `LOADING` 的布局尚未写入注册表，
     * 因此 `maskWidth` 等字段反映的是**旧**画面，`getStateView(新建中的状态)` 也会返回 null。
     * 在 `OnStateViewCreatedListener` 内部取快照时必定为 true——那里能拿到的
     * 是回调参数里的根 View，不要回查控件。
     */
    val inStateViewTransaction: Boolean = false,
)

enum class SkeletonFallbackReason {
    NONE, EMPTY_CONTENT, EMPTY_TEMPLATE_AT_CURRENT_SIZE, ALLOCATION_FAILED, PIXEL_BUDGET_EXCEEDED,
}
