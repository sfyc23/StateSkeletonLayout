package com.sfyc.demo.content

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentContentBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 内容取形示例：资料卡片完成布局后进入 Loading，骨架按真实叶子 View 取形。
 */
class ContentShapeFragment : Fragment() {

    private var _binding: FragmentContentBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        binding.demoBtnReplay.setOnClickListener { simulateLoad() }
        simulateLoad()
    }

    // 本地延迟模拟网络：延迟任务绑定 Fragment View 生命周期，页面销毁后取消。
    private fun simulateLoad() {
        loadingJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING（内容取形）")
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1500L)
            state.showContent()
            log?.add("请求 CONTENT")
        }
    }

    override fun onDestroyView() {
        loadingJob?.cancel()
        _binding = null
        log = null
        super.onDestroyView()
    }
}
