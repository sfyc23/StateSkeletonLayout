package com.sfyc.ssl

import android.view.View

/**
 * 状态布局首次 inflate 完成时回调一次。
 *
 * 适用场景：
 * - 绑定错误页 / 空页中的动态控件（如错误文案、插图）。
 * - 查找自定义加载布局中的 `LottieAnimationView` 并持有引用
 *   （库不直接依赖 Lottie，启停由加载可见性回调控制）。
 * - 设置不属于统一重试入口的页面级交互。
 *
 * 注意：回调实现不得持有 Activity / Fragment 的长期引用；
 * 页面销毁时解除监听。回调中只操作传入 View，不得提交状态或修改控件配置。
 */
fun interface OnStateViewCreatedListener {

    /**
     * @param layout 回调来源的 [StateSkeletonLayout]。
     * @param state 刚创建的 View 所属的状态（EMPTY、ERROR 或 LOADING 的自定义布局）。
     * @param view 刚 inflate 出的状态根 View。
     */
    fun onStateViewCreated(layout: StateSkeletonLayout, state: StateLayoutState, view: View)
}
