package com.sfyc.ssl.internal.loading

/**
 * 加载渲染器内部接缝：骨架加载与自定义布局加载的统一启停接口。
 *
 * 设立该接口是因为首版至少存在两个真实实现
 * （[SkeletonLoadingRenderer] 与 [LayoutLoadingRenderer]）；
 * 状态协调器与主控件只控制加载呈现的开始与结束，
 * 不了解骨架遮罩、Lottie 等具体实现。
 *
 * 首版不公开自定义 LoadingRenderer 插件接口，
 * 未来确有需求时再单独设计（见实现方案第 29 节）。
 */
internal interface LoadingRenderer {

    /** 开始呈现加载；实际可见后必须且只调用一次 [onShown]。 */
    fun show(onShown: () -> Unit)

    /** 停止呈现加载并清理可见状态（离开 LOADING 时调用）。 */
    fun hide()

    /** View 挂载：恢复当前 Loading 所需动画与观察器。 */
    fun onAttachedToWindow()

    /** View 分离：停止动画、移除观察器、释放绘制资源（必须与挂载成对）。 */
    fun onDetachedFromWindow()

    /**
     * 聚合可见性变化：控件实际不可见时暂停动画，重新可见且仍在
     * Loading 时恢复，避免后台耗电。
     */
    fun onAggregatedVisibilityChanged(isVisible: Boolean)
}
