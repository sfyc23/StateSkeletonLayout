package com.sfyc.ssl

import android.view.View

/** 加载页在窗口中开始 / 停止可见时通知；同为 LOADING 的表现替换也会通知。 */
fun interface OnLoadingVisibilityChangedListener {
    fun onLoadingVisibilityChanged(layout: StateSkeletonLayout, visible: Boolean, loadingView: View?)
}
