package com.sfyc.ssl

import android.view.View

/**
 * 画面完成状态提交时回调。
 *
 * 调用时机固定为“视图层级与交互状态完成提交之后”，而不是等待装饰性
 * 淡入动画结束。调用方可据此：
 * - 记录业务状态提交；自定义动画启停请使用 [OnLoadingVisibilityChangedListener]。
 * - 在 UI 测试中等待目标状态呈现完成。
 * - 执行一次性无障碍播报（相同状态重复提交不会重复回调）。
 *
 * 注意：回调实现不得持有 Activity / Fragment 的长期引用。
 */
fun interface OnRenderedStateChangedListener {

    /**
     * @param layout 回调来源的 [StateSkeletonLayout]。
     * @param oldState 上一次呈现的状态。
     * @param newState 本次呈现的状态。
     * @param stateView 新状态对应的原始根 View；CONTENT 为业务内容，骨架 Loading 为装饰层。
     */
    fun onRenderedStateChanged(
        layout: StateSkeletonLayout,
        oldState: StateLayoutState,
        newState: StateLayoutState,
        stateView: View?,
    )
}
