package com.sfyc.demo.lottie

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentLottieBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lottie 加载示例：自定义加载布局中的动画由页面成对启停。
 *
 * - 状态 View 创建回调中查找并持有 LottieAnimationView。
 * - 加载页实际可见且页面 STARTED 时播放，隐藏 / onStop / 释放时停止。
 * - 库模块不感知 Lottie 类型，Lottie 仅存在于 demo 依赖。
 */
class LottieLoadingFragment : Fragment() {

    private var _binding: FragmentLottieBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var lottieView: LottieAnimationView? = null
    private var loadingJob: Job? = null
    private var loadingVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLottieBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnStateViewCreatedListener { _, created, createdView ->
            if (created == StateLayoutState.LOADING) {
                lottieView = createdView.findViewById(R.id.demo_lottie_view)
                log?.add("自定义加载布局已创建，持有 Lottie 引用")
            }
        }
        state.setOnRenderedStateChangedListener { _, old, new, _ -> log?.add("呈现：$old → $new") }
        state.setOnLoadingVisibilityChangedListener { _, visible, loadingView ->
            loadingVisible = visible
            if (visible) lottieView = loadingView?.findViewById(R.id.demo_lottie_view)
            updatePlayback()
        }
        state.setOnStateViewReleasedListener { _, released, releasedView ->
            if (released == StateLayoutState.LOADING) {
                val releasedLottie = releasedView.findViewById<LottieAnimationView>(R.id.demo_lottie_view)
                releasedLottie?.cancelAnimation()
                if (lottieView === releasedLottie) lottieView = null
            }
        }
        binding.demoBtnReplay.setOnClickListener { simulateLoad() }
        simulateLoad()
    }

    private fun updatePlayback() {
        val playing = loadingVisible && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (playing) lottieView?.playAnimation() else lottieView?.pauseAnimation()
        if (_binding != null) updateLottieLabel(playing)
    }

    override fun onStart() { super.onStart(); updatePlayback() }

    override fun onStop() {
        lottieView?.pauseAnimation()
        if (_binding != null) updateLottieLabel(false)
        super.onStop()
    }

    private fun updateLottieLabel(playing: Boolean) {
        binding.demoLottieState.text = getString(
            R.string.demo_lottie_state,
            if (playing) "播放" else "停止",
        )
    }

    private fun simulateLoad() {
        loadingJob?.cancel()
        binding.demoState.showLoading()
        log?.add("请求 LOADING（自定义布局）")
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(2500L)
            binding.demoState.showContent()
            log?.add("请求 CONTENT（Lottie 成对停止）")
        }
    }

    override fun onDestroyView() {
        loadingJob?.cancel()
        lottieView?.cancelAnimation()
        lottieView = null
        loadingVisible = false
        binding.demoState.setOnLoadingVisibilityChangedListener(null)
        binding.demoState.setOnStateViewCreatedListener(null)
        binding.demoState.setOnStateViewReleasedListener(null)
        binding.demoState.setOnRenderedStateChangedListener(null)
        _binding = null
        log = null
        super.onDestroyView()
    }
}
