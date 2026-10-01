package com.sfyc.demo.effects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.ssl.SkeletonDirection
import com.sfyc.ssl.SkeletonEffect
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentEffectsBinding

/**
 * 骨架效果示例：运行时整体替换 SkeletonConfig，切换互斥效果与流光方向。
 */
class EffectsFragment : Fragment() {

    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEffectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        state.showLoading()

        binding.demoBtnSolid.setOnClickListener { applyEffect(SkeletonEffect.SOLID) }
        binding.demoBtnShimmer.setOnClickListener { applyEffect(SkeletonEffect.SHIMMER) }
        binding.demoBtnPulse.setOnClickListener { applyEffect(SkeletonEffect.PULSE) }
        binding.demoBtnDirection.setOnClickListener {
            val current = state.skeletonConfig
            val next = if (current.direction == SkeletonDirection.START_TO_END) {
                SkeletonDirection.END_TO_START
            } else {
                SkeletonDirection.START_TO_END
            }
            state.skeletonConfig = current.copy(direction = next)
            log?.add("方向切换为 $next（RTL 下自动镜像）")
        }
    }

    private fun applyEffect(effect: SkeletonEffect) {
        val state = binding.demoState
        state.skeletonConfig = state.skeletonConfig.copy(effect = effect)
        log?.add("效果切换为 $effect（整体替换配置，遮罩不重建、动画即时切换）")
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
