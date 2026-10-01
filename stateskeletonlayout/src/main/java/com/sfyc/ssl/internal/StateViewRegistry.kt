package com.sfyc.ssl.internal

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.sfyc.ssl.StateLayoutState

/** 状态页按需创建与缓存；内部包装层负责交互，公开回调始终得到原始布局根节点。 */
internal class StateViewRegistry(
    private val context: Context,
    private val host: FrameLayout,
    private val onViewCreated: (StateLayoutState, View) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)
    private val layoutResIds = mutableMapOf<StateLayoutState, Int>()
    private val cachedViews = mutableMapOf<StateLayoutState, InteractionHost>()

    /** 正在呈现的布局先创建候选；创建或绑定失败时保留原资源和缓存。 */
    fun setLayout(state: StateLayoutState, layoutResId: Int, prepare: Boolean = false): View? {
        checkResource(state, layoutResId)
        if (layoutResIds[state] == layoutResId) return null
        val candidate = if (prepare) create(state, layoutResId) else null
        val old = cachedViews.remove(state)
        layoutResIds[state] = layoutResId
        if (candidate != null) cachedViews[state] = candidate
        (old?.parent as? ViewGroup)?.removeView(old)
        return old?.getChildAt(0)
    }

    fun setInitialLayout(state: StateLayoutState, layoutResId: Int) {
        if (layoutResId != 0) layoutResIds[state] = layoutResId
    }

    fun layoutResource(state: StateLayoutState): Int = layoutResIds[state] ?: 0
    fun peek(state: StateLayoutState): View? = cachedViews[state]
    fun content(state: StateLayoutState): View? = cachedViews[state]?.getChildAt(0)
    fun hasLayout(state: StateLayoutState): Boolean = layoutResource(state) != 0

    fun validate(state: StateLayoutState) {
        val resId = layoutResource(state)
        require(resId != 0) {
            "请求 ${state.name} 状态但未配置对应布局：请设置 ssl_${state.name.lowercase()}Layout 或调用 setStateViewLayout"
        }
        checkResource(state, resId)
    }

    fun require(state: StateLayoutState): View {
        cachedViews[state]?.let { return it }
        validate(state)
        return create(state, layoutResource(state)).also { cachedViews[state] = it }
    }

    /** 移除缓存而保留资源声明，下次使用时重新创建。 */
    fun clear(state: StateLayoutState): View? {
        val old = cachedViews.remove(state) ?: return null
        (old.parent as? ViewGroup)?.removeView(old)
        return old.getChildAt(0)
    }

    /** SavedState 的资源为 0 时恢复「未配置」，不继承重建实例的新声明。 */
    fun clearLayout(state: StateLayoutState): View? {
        layoutResIds.remove(state)
        return clear(state)
    }

    fun checkResource(state: StateLayoutState, layoutResId: Int) {
        require(state != StateLayoutState.CONTENT) { "CONTENT 请通过 XML 子 View 或 setContentView 设置" }
        require(layoutResId != 0 && context.resources.getResourceTypeName(layoutResId) == "layout") {
            "layoutResId 必须指向布局资源，当前=$layoutResId"
        }
    }

    private fun create(state: StateLayoutState, resId: Int): InteractionHost {
        val wrapper = InteractionHost(context)
        val view = inflater.inflate(resId, host, false)
        wrapper.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        wrapper.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
        // 回调成功之后才允许缓存候选，不保留半初始化状态页。
        onViewCreated(state, view)
        return wrapper
    }
}
