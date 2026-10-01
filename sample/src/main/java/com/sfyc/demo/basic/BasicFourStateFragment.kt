package com.sfyc.demo.basic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentBasicBinding

/**
 * 基础四态示例：手动切换 Content / Loading / Empty / Error，验证重试单向事件。
 */
class BasicFourStateFragment : Fragment() {

    private var _binding: FragmentBasicBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var retryCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBasicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        state.setOnRetryClickListener {
            retryCount++
            binding.demoRetryCount.text = getString(R.string.demo_retry_count, retryCount)
            log?.add("重试点击（状态保持 ERROR，由页面决定下一步）")
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading() }
        binding.demoBtnContent.setOnClickListener { state.showContent() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty() }
        binding.demoBtnError.setOnClickListener { state.showError() }
        binding.demoRetryCount.text = getString(R.string.demo_retry_count, 0)
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
