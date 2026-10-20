package com.sfyc.ssl

import android.view.View

/**
 * 状态容器重试的统一语义回调。
 *
 * - [layout] 为事件所属控件，便于同页多个局部状态容器共用处理逻辑。
 * - [state] 为接受事件时的 EMPTY / ERROR，避免从 View ID 或异步状态反推来源。
 * - [clickedView] 为实际点击的图片、文字或按钮；一次接受的点击只调用一次。
 *
 * 库只派发用户的重试意图，不自动切换状态、不发起请求；
 * 是否请求数据、是否允许请求由宿主的 ViewModel / State 决定。
 */
fun interface OnRetryListener {
    fun onRetry(layout: StateSkeletonLayout, state: StateLayoutState, clickedView: View)
}
