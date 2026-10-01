package com.sfyc.ssl

import android.view.View

/** 缓存移除后释放业务绑定的资源；回调中不得重新提交状态或修改配置。 */
fun interface OnStateViewReleasedListener {
    fun onStateViewReleased(layout: StateSkeletonLayout, state: StateLayoutState, view: View)
}
