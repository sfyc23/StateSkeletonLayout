package com.sfyc.demo.template

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentTemplateBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模板取形示例：内容尚未具备尺寸时，使用独立模板描述骨架形状。
 */
class TemplateFragment : Fragment() {

    private var _binding: FragmentTemplateBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTemplateBinding.inflate(inflater, container, false)
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

    private fun simulateLoad() {
        loadingJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING（模板取形：skeleton_profile_template）")
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
