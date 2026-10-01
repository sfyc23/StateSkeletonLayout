package com.sfyc.demo.defaults

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.ssl.StateSkeletonLayoutDefaults
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentDefaultsGlobalBinding

/**
 * 全局默认与程序化创建示例：install 全字段覆盖后程序化创建实例，
 * 再次 install 验证整体替换语义与「已创建实例不受影响」。
 */
class GlobalDefaultsFragment : Fragment() {

    private var _binding: FragmentDefaultsGlobalBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var instanceA: StateSkeletonLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDefaultsGlobalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoConfigSummary.text = "fullOverride\n" + DemoSslDefaults.describe(DemoSslDefaults.fullOverride)

        StateSkeletonLayoutDefaults.install(DemoSslDefaults.fullOverride)
        log?.add("install(fullOverride)")
        instanceA = attachInstance(binding.demoContainerA, "A")
        refreshCompare(installedLabel = "fullOverride")

        binding.demoBtnInstallB.setOnClickListener { installPartialAndCreateB() }
    }

    private fun installPartialAndCreateB() {
        StateSkeletonLayoutDefaults.install(DemoSslDefaults.partialOverride)
        log?.add("install(partialOverride)，创建实例 B（实例 A 应保持不变）")
        binding.demoConfigSummary.text = "partialOverride\n" + DemoSslDefaults.describe(DemoSslDefaults.partialOverride)
        val b = attachInstance(binding.demoContainerB, "B")
        val a = instanceA
        binding.demoCompare.text = buildString {
            appendLine("【实例 A｜install 前创建，应保持 fullOverride】")
            appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.fullOverride, a ?: return@buildString))
            appendLine()
            appendLine("【实例 B｜install 后创建，仅两字段为 Global，其余库内置】")
            appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.partialOverride, b))
        }
    }

    private fun refreshCompare(installedLabel: String) {
        val a = instanceA ?: return
        binding.demoCompare.text = buildString {
            appendLine("【实例 A｜当前安装：$installedLabel】")
            appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.fullOverride, a))
        }
    }

    private fun attachInstance(container: FrameLayout, tag: String): StateSkeletonLayout {
        container.removeAllViews()
        val state = DemoSslDefaults.createStateLayout(requireContext())
        state.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        container.addView(state)
        state.showLoading()
        log?.add("程序化创建实例 $tag 并 showLoading()")
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("实例 $tag 呈现：$old → $new")
        }
        return state
    }

    override fun onDestroyView() {
        DemoSslDefaults.installCanonical()
        instanceA = null
        _binding = null
        log = null
        super.onDestroyView()
    }
}
