package com.sfyc.demo.resume.models

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewResumeHeaderSectionStateBinding
import com.sfyc.ssl.SkeletonNodeConfig
import com.sfyc.ssl.SkeletonShape
import com.sfyc.ssl.StateLayoutState

/**
 * 上半区域状态容器：内嵌 1 个 StateSkeletonLayout（CONTENT 取形占位）。
 * 仅处理 LOADING → CONTENT，恒成功，无空/错页。
 */
@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class HeaderSectionStateView(context: Context) : FrameLayout(context) {

    private val binding = ViewResumeHeaderSectionStateBinding.inflate(
        LayoutInflater.from(context),
        this,
        true,
    )

    private var onContentRendered: (() -> Unit)? = null

    init {
        val stateLayout = binding.headerStateLayout
        stateLayout.setSkeletonNodeConfig(
            R.id.resume_head_avatar,
            SkeletonNodeConfig(shape = SkeletonShape.CIRCLE),
        )
        stateLayout.setOnRenderedStateChangedListener { _, _, newState, _ ->
            if (newState == StateLayoutState.CONTENT) {
                onContentRendered?.invoke()
            }
        }
    }

    @ModelProp
    fun setTargetState(state: StateLayoutState) {
        val stateLayout = binding.headerStateLayout
        stateLayout.render(state)
        if (state == StateLayoutState.CONTENT && stateLayout.renderedState == StateLayoutState.CONTENT) {
            post {
                if (binding.headerStateLayout.requestedState == StateLayoutState.CONTENT &&
                    binding.headerStateLayout.renderedState == StateLayoutState.CONTENT
                ) {
                    onContentRendered?.invoke()
                }
            }
        }
    }

    @CallbackProp
    fun setOnContentRenderedCallback(callback: (() -> Unit)?) {
        onContentRendered = callback
    }
}
