package com.sfyc.demo.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.ssl.SkeletonFallbackReason
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentDiagnosticsBinding

/**
 * 骨架诊断示例：diagnostics() 快照、maximumMaskPixels 像素预算降级、状态页缓存清理。
 *
 * - diagnostics() 每次调用返回一份新快照，不持有 View / Bitmap，也不上传任何数据。
 * - `maximumMaskPixels` 是 ALPHA_8 遮罩的像素上限：遮罩面积超过上限时静态降级，
 *   fallbackReason 变为 PIXEL_BUDGET_EXCEEDED、maskBytes 归零、动画不再启动。
 * - invalidateSkeleton() 主动重建遮罩（内容替换导致形状变化时使用）。
 * - clearStateViewCache() 释放非当前态缓存；当前可见态与过渡中的状态页会被拒绝。
 */
class DiagnosticsDemoFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    /** 很小的像素预算：远小于 280dp 高度的遮罩面积，用于触发 PIXEL_BUDGET_EXCEEDED。 */
    private val tinyBudget: Long = 1000L
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
            refreshDiagnostics()
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading() }
        binding.demoBtnContent.setOnClickListener { state.showContent() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty() }
        binding.demoBtnError.setOnClickListener { state.showError() }
        binding.demoBtnRefresh.setOnClickListener {
            log?.add("重新读取 diagnostics()")
            refreshDiagnostics()
        }
        binding.demoBtnBudgetUnlimited.setOnClickListener { applyBudget(Long.MAX_VALUE) }
        binding.demoBtnBudgetTiny.setOnClickListener { applyBudget(tinyBudget) }
        binding.demoBtnInvalidate.setOnClickListener {
            state.invalidateSkeleton()
            log?.add("invalidateSkeleton()：遮罩标记失效，下一帧重建")
            refreshDiagnostics()
        }
        binding.demoBtnClearEmpty.setOnClickListener { clearCache(StateLayoutState.EMPTY) }
        binding.demoBtnClearError.setOnClickListener { clearCache(StateLayoutState.ERROR) }
        state.showLoading()
        // 重建后恢复的状态可能就是 LOADING，showLoading() 是幂等空操作、状态回调不会触发，
        // 快照必须以当前状态主动渲染一次。
        refreshDiagnostics()
    }

    private fun applyBudget(budget: Long) {
        val state = binding.demoState
        state.skeletonConfig = state.skeletonConfig.copy(maximumMaskPixels = budget)
        log?.add(
            if (budget == Long.MAX_VALUE) {
                "maximumMaskPixels 恢复不限制"
            } else {
                "maximumMaskPixels = $budget（小于遮罩面积，预期静态降级）"
            },
        )
        refreshDiagnostics()
    }

    private fun clearCache(state: StateLayoutState) {
        try {
            binding.demoState.clearStateViewCache(state)
            log?.add("clearStateViewCache($state)：缓存已释放，下次显示重新 inflate")
        } catch (refused: IllegalStateException) {
            // 当前可见态与过渡中的状态页不允许清除，控件用异常明确拒绝而不是静默跳过。
            log?.add("clearStateViewCache($state) 被拒绝：${refused.message}")
        }
        refreshDiagnostics()
    }

    /** 把 diagnostics() 的全部字段展开成多行文本。 */
    private fun refreshDiagnostics() {
        if (_binding == null) return
        val snapshot = binding.demoState.diagnostics()
        binding.demoDiagnostics.text = buildString {
            appendLine("requestedState        = ${snapshot.requestedState}")
            appendLine("renderedState         = ${snapshot.renderedState}")
            appendLine("effectiveUseSkeleton  = ${snapshot.effectiveUseSkeleton}")
            appendLine("maskWidth             = ${snapshot.maskWidth}")
            appendLine("maskHeight            = ${snapshot.maskHeight}")
            appendLine("maskBytes             = ${snapshot.maskBytes}")
            appendLine("maskBuildCount        = ${snapshot.maskBuildCount}")
            appendLine("fallbackReason        = ${snapshot.fallbackReason}")
            appendLine("effectRunning         = ${snapshot.effectRunning}")
            appendLine("hasPendingWork        = ${snapshot.hasPendingWork}")
            appendLine("inStateViewTransaction= ${snapshot.inStateViewTransaction}")
            append("降级说明              = ").append(fallbackDescription(snapshot.fallbackReason))
        }
    }

    private fun fallbackDescription(reason: SkeletonFallbackReason): String = when (reason) {
        SkeletonFallbackReason.NONE -> getString(R.string.demo_diagnostics_fallback_none)
        SkeletonFallbackReason.EMPTY_CONTENT -> getString(R.string.demo_diagnostics_fallback_empty_content)
        SkeletonFallbackReason.EMPTY_TEMPLATE_AT_CURRENT_SIZE ->
            getString(R.string.demo_diagnostics_fallback_empty_template)
        SkeletonFallbackReason.ALLOCATION_FAILED -> getString(R.string.demo_diagnostics_fallback_allocation)
        SkeletonFallbackReason.PIXEL_BUDGET_EXCEEDED ->
            getString(R.string.demo_diagnostics_fallback_pixel_budget)
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
