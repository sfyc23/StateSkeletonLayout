package com.sfyc.demo.resume.models

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewResumeWorkSectionStateBinding
import com.sfyc.ssl.SkeletonNodeConfig
import com.sfyc.ssl.SkeletonShape
import com.sfyc.ssl.StateLayoutState

/**
 * 下半区域状态容器：内嵌 1 个 StateSkeletonLayout（CONTENT 取形三段占位）。
 * 处理 LOADING / EMPTY / ERROR / CONTENT，局部重试仅重拉 Work。
 */
@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class WorkSectionStateView(context: Context) : FrameLayout(context) {

    private val binding = ViewResumeWorkSectionStateBinding.inflate(
        LayoutInflater.from(context),
        this,
        true,
    )

    private var onRetry: (() -> Unit)? = null
    private var onContentRendered: (() -> Unit)? = null

    init {
        val stateLayout = binding.workStateLayout
        intArrayOf(
            R.id.resume_work_logo_1,
            R.id.resume_work_logo_2,
            R.id.resume_work_logo_3,
        ).forEach { id ->
            stateLayout.setSkeletonNodeConfig(
                id,
                SkeletonNodeConfig(shape = SkeletonShape.CIRCLE),
            )
        }
        intArrayOf(
            R.id.resume_work_divider_1,
            R.id.resume_work_divider_2,
        ).forEach { id ->
            stateLayout.setSkeletonNodeConfig(id, SkeletonNodeConfig(excluded = true))
        }
        stateLayout.setOnRetryListener { _, _, _ ->
            onRetry?.invoke()
        }
        stateLayout.setOnRenderedStateChangedListener { _, _, newState, _ ->
            if (newState == StateLayoutState.CONTENT) {
                onContentRendered?.invoke()
            }
        }
    }

    @ModelProp
    fun setTargetState(state: StateLayoutState) {
        val stateLayout = binding.workStateLayout
        stateLayout.render(state)
        if (state == StateLayoutState.CONTENT &&
            stateLayout.renderedState == StateLayoutState.CONTENT
        ) {
            post {
                if (binding.workStateLayout.requestedState == StateLayoutState.CONTENT &&
                    binding.workStateLayout.renderedState == StateLayoutState.CONTENT
                ) {
                    onContentRendered?.invoke()
                }
            }
        }
    }

    @ModelProp
    fun setRetryEnabled(enabled: Boolean) {
        binding.workStateLayout.retryClicksEnabled = enabled
    }

    @ModelProp
    fun setErrorMessage(message: String?) {
        if (!message.isNullOrBlank()) {
            binding.workStateLayout.updateStateView(StateLayoutState.ERROR) { errorView ->
                errorView.findViewById<TextView>(R.id.resume_error_desc)?.text = message
            }
        }
    }

    @CallbackProp
    fun setOnRetryClick(callback: (() -> Unit)?) {
        onRetry = callback
    }

    @CallbackProp
    fun setOnContentRenderedCallback(callback: (() -> Unit)?) {
        onContentRendered = callback
    }
}
