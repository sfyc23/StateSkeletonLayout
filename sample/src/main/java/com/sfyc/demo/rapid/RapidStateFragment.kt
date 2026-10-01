package com.sfyc.demo.rapid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentRapidBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 连续状态示例：Loading 后 100ms 内连续提交 Content、Empty、Error。
 *
 * 最短展示 800ms 兜住 Loading，到期后只呈现最终的 Error，
 * 中间态不会闪回（观察日志中的呈现序列）。
 */
class RapidStateFragment : Fragment() {

    private var _binding: FragmentRapidBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var fireJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRapidBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        binding.demoBtnFire.setOnClickListener { fireSequence() }
    }

    private fun fireSequence() {
        fireJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING")
        fireJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)
            state.showContent()
            state.showEmpty()
            state.showError()
            log?.add("100ms 内连续请求 CONTENT → EMPTY → ERROR（只呈现最终态）")
        }
    }

    override fun onDestroyView() {
        fireJob?.cancel()
        _binding = null
        log = null
        super.onDestroyView()
    }
}
