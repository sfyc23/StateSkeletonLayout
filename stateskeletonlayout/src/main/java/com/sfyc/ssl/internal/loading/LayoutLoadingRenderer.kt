package com.sfyc.ssl.internal.loading

import android.view.View
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.internal.StateViewRegistry

/**
 * 自定义布局加载渲染器：管理 `ssl_loadingLayout` 状态页的显示与隐藏。
 *
 * - 首次显示时经由 [StateViewRegistry] 按需 inflate 并缓存，后续复用。
 * - 只负责容器层面的挂载状态；动画启停（如 Lottie 的 play/cancel）由调用方
 *   在状态 View 创建回调与呈现状态回调中成对控制，库不直接识别 Lottie 类型。
 */
internal class LayoutLoadingRenderer(
    private val registry: StateViewRegistry,
    /** 当前是否处于可见的自定义加载中（供生命周期恢复判断）。 */
    private var showing: Boolean = false,
    /** 状态容器可见性切换的实际执行者（主控件传入，避免渲染器直接操作 Host）。 */
    private val visibilityDelegate: VisibilityDelegate = VisibilityDelegate.NO_OP,
) : LoadingRenderer {

    /**
     * 状态容器可见性委托：主控件实现，渲染器只表达意图。
     *
     * 这样加载渲染器保持纯粹，不持有 ContentHost / StateHost 引用。
     */
    interface VisibilityDelegate {

        fun showLoadingView(view: View)

        fun hideLoadingView(view: View)

        companion object {
            val NO_OP: VisibilityDelegate = object : VisibilityDelegate {
                override fun showLoadingView(view: View) {
                    view.visibility = View.VISIBLE
                }

                override fun hideLoadingView(view: View) {
                    view.visibility = View.GONE
                }
            }
        }
    }

    override fun show(onShown: () -> Unit) {
        showing = true
        // 未配置 loadingLayout 时 registry.require 会抛明确异常，不静默空白。
        val view = registry.require(StateLayoutState.LOADING)
        visibilityDelegate.showLoadingView(view)
        onShown()
    }

    override fun hide() {
        showing = false
        registry.peek(StateLayoutState.LOADING)?.let(visibilityDelegate::hideLoadingView)
    }

    override fun onAttachedToWindow() {
        // 自定义布局自身无持续动画需要恢复；若 detach 前正在显示，
        // 重新挂载后按 showing 标记恢复可见性（可见性在 detach 期间保持不变，
        // 此处做防御性重申，避免宿主层面的可见性被外部改动）。
        if (showing) {
            registry.peek(StateLayoutState.LOADING)?.let {
                if (it.visibility != View.VISIBLE) {
                    visibilityDelegate.showLoadingView(it)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        // 自定义布局的动画资源归调用方管理（如 Lottie），库不代为启停；
        // detach 时仅标记，避免 attach 后误判。
    }

    override fun onAggregatedVisibilityChanged(isVisible: Boolean) {
        // 无库侧持续动画，无需处理。
    }
}
