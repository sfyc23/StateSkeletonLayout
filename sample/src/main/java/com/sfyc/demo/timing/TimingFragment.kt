package com.sfyc.demo.timing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentTimingBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 快速请求示例：显示延迟 150ms、最短展示 600ms（见布局 XML）。
 *
 * - 100ms 请求：延迟窗口内完成，骨架被跳过，直接呈现结果。
 * - 500ms 请求：骨架上屏但被最短展示兜住，到期后呈现。
 * - 1500ms 请求：完整展示骨架后呈现。
 */
class TimingFragment : Fragment() {

    private var _binding: FragmentTimingBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null
    private var skeletonShown: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTimingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            if (new == StateLayoutState.LOADING) skeletonShown = true
            log?.add("呈现：$old → $new")
        }
        binding.demoBtn100.setOnClickListener { simulate(100L) }
        binding.demoBtn500.setOnClickListener { simulate(500L) }
        binding.demoBtn1500.setOnClickListener { simulate(1500L) }
    }

    private fun simulate(costMillis: Long) {
        loadingJob?.cancel()
        skeletonShown = false
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING，模拟耗时 ${costMillis}ms")
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(costMillis)
            state.showContent()
            val skipped = !skeletonShown
            log?.add("请求 CONTENT；骨架${if (skipped) "被跳过（延迟窗口内完成）" else "已展示并满足最短展示"}")
        }
    }

    override fun onDestroyView() {
        loadingJob?.cancel()
        _binding = null
        log = null
        super.onDestroyView()
    }
}
