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
import com.sfyc.demo.ssl.databinding.FragmentDefaultsPartialBinding

/**
 * 部分覆盖示例：全局默认仅声明两个字段，未声明字段回落库内置默认；
 * 对照一个 setter 覆盖实例与一个全字段覆盖实例。
 */
class PartialOverrideFragment : Fragment() {

    private var _binding: FragmentDefaultsPartialBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDefaultsPartialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoConfigSummary.text =
            "partialOverride\n" + DemoSslDefaults.describe(DemoSslDefaults.partialOverride)

        StateSkeletonLayoutDefaults.install(DemoSslDefaults.partialOverride)
        log?.add("install(partialOverride)")

        val plain = attachInstance(binding.demoContainerL, "L")
        val withSetter = attachInstance(binding.demoContainerR, "R")
        withSetter.skeletonConfig = withSetter.skeletonConfig.copy(animationDurationMillis = 2500L)
        log?.add("实例 R 运行时 setter：animationDurationMillis = 2500")

        binding.demoCompare.text = buildString {
            appendLine("【实例 L｜仅 Global 两字段，其余库内置】")
            appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.partialOverride, plain))
            appendLine()
            appendLine("【实例 R｜Global + setter 覆盖 animationDurationMillis】")
            appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.partialOverride, withSetter))
        }

        binding.demoBtnFull.setOnClickListener {
            StateSkeletonLayoutDefaults.install(DemoSslDefaults.fullOverride)
            log?.add("install(fullOverride)，创建实例 C（L、R 应保持不变）")
            binding.demoConfigSummary.text =
                "fullOverride\n" + DemoSslDefaults.describe(DemoSslDefaults.fullOverride)
            val full = attachInstance(binding.demoContainerC, "C")
            binding.demoCompare.text = buildString {
                appendLine("【实例 C｜全字段 Global 覆盖】")
                appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.fullOverride, full))
                appendLine()
                appendLine("【实例 L｜创建于 partialOverride 期间，应保持不变】")
                appendLine(DemoSslDefaults.compareTable(DemoSslDefaults.partialOverride, plain))
            }
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
        return state
    }

    override fun onDestroyView() {
        DemoSslDefaults.installCanonical()
        _binding = null
        log = null
        super.onDestroyView()
    }
}
