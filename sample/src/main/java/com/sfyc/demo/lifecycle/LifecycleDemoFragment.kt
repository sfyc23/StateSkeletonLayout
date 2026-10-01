package com.sfyc.demo.lifecycle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentLifecycleBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 生命周期示例：切后台、切页面、旋转设备后的行为观察。
 *
 * - 界面实际不可见：骨架动画暂停；状态请求仍由页面自身的协程生命周期管理。
 * - 返回前台：按最新请求状态恢复呈现。
 * - 旋转设备：SavedState 恢复请求状态，无动画重建。
 * - 页面销毁：延迟任务随 View 生命周期取消，无残留回调。
 */
class LifecycleDemoFragment : Fragment() {

    private var _binding: FragmentLifecycleBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLifecycleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        binding.demoBtnReplay.setOnClickListener { simulateLoad() }
        if (savedInstanceState == null) {
            log?.add("首次创建")
            simulateLoad()
        } else {
            log?.add("配置变化后重建，状态由 SavedState 恢复")
        }
    }

    override fun onPause() {
        super.onPause()
        log?.add("onPause：界面不可见时骨架动画暂停")
    }

    override fun onResume() {
        super.onResume()
        log?.add("onResume：返回前台，按最新状态恢复")
    }

    private fun simulateLoad() {
        loadingJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING")
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
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
