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
)

enum class SkeletonFallbackReason {
    NONE, EMPTY_CONTENT, EMPTY_TEMPLATE_AT_CURRENT_SIZE, ALLOCATION_FAILED, PIXEL_BUDGET_EXCEEDED,
}
