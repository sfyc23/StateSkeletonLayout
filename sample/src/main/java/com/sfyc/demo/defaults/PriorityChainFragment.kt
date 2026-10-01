package com.sfyc.demo.defaults

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateSkeletonLayout
import com.sfyc.ssl.StateSkeletonLayoutDefaults
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentDefaultsPriorityBinding

/**
 * 优先级链示例：库内置 < Theme < Global < XML < 运行时 setter 逐步叠加对比。
 *
 * 探针字段（4 个）：effect / maskColor / cornerRadius / animationDurationMillis。
 * Theme 层经 [ContextThemeWrapper] 局部生效，不改应用级主题。
 */
class PriorityChainFragment : Fragment() {

    private var _binding: FragmentDefaultsPriorityBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var step: Int = 0
    private var xmlProbe: StateSkeletonLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDefaultsPriorityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoCompare.text = LEGEND
        recreateXmlProbe()
        binding.demoBtnNext.setOnClickListener { advanceStep() }
        log?.add("逐步叠加四层；XML 探针控件见上方，步骤 4、5 读取该控件")
    }

    private fun advanceStep() {
        when (step) {
            0 -> stepLibrary()
            1 -> stepTheme()
            2 -> stepGlobal()
            3 -> stepXml()
            4 -> stepSetter()
            else -> { resetSteps(); return }
        }
        step++
    }

    private fun stepLibrary() {
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults())
        val state = try { attachStepInstance(requireContext(), 1) }
            finally { DemoSslDefaults.installCanonical() }
        appendStep(
            title = "步骤 1｜仅库内置（普通 context，全局字段留空）",
            expected = "全部字段 = 库内置默认",
            state = state,
        )
        log?.add("步骤 1：普通 context 程序化创建")
    }

    private fun stepTheme() {
        val themed = ContextThemeWrapper(requireContext(), R.style.DemoSslThemeLayer)
        StateSkeletonLayoutDefaults.install(SslGlobalDefaults())
        val state = try { attachStepInstance(themed, 2) }
            finally { DemoSslDefaults.installCanonical() }
        appendStep(
            title = "步骤 2｜叠加 Theme（ContextThemeWrapper）",
            expected = "effect/maskColor/cornerRadius = Theme（DemoSslThemeDefaults）",
            state = state,
        )
        log?.add("步骤 2：Theme 层经 ContextThemeWrapper 生效")
    }

    private fun stepGlobal() {
        StateSkeletonLayoutDefaults.install(DemoSslDefaults.priorityProbe)
        log?.add("步骤 3：install(priorityProbe)")
        val themed = ContextThemeWrapper(requireContext(), R.style.DemoSslThemeLayer)
        val state = attachStepInstance(themed, 3)
        appendStep(
            title = "步骤 3｜叠加 Global（Global 胜出 Theme）",
            expected = "探针四字段 = Global（priorityProbe）",
            state = state,
        )
    }

    private fun stepXml() {
        StateSkeletonLayoutDefaults.install(DemoSslDefaults.priorityProbe)
        val xmlState = recreateXmlProbe()
        appendStep(
            title = "步骤 4｜安装 Global 后重新膨胀 XML（XML 胜出 Global）",
            expected = "探针四字段 = XML（shimmer / #FFE6A23C / 30dp / 800）",
            state = xmlState,
        )
        log?.add("步骤 4：使用同一 Theme / Global 冲突环境重新创建 XML 探针")
    }

    private fun stepSetter() {
        val xmlState = checkNotNull(xmlProbe)
        xmlState.skeletonConfig = xmlState.skeletonConfig.copy(animationDurationMillis = 1500L)
        log?.add("步骤 5：运行时 setter animationDurationMillis = 1500")
        appendStep(
            title = "步骤 5｜叠加运行时 setter（setter 胜出全部下层）",
            expected = "animationDurationMillis = 1500；其余保持步骤 4 胜出方",
            state = xmlState,
        )
    }

    private fun resetSteps() {
        DemoSslDefaults.installCanonical()
        recreateXmlProbe()
        step = 0
        binding.demoSteps.removeAllViews()
        binding.demoCompare.text = LEGEND
        log?.add("已复位：installCanonical()，可重新逐步演示")
    }

    private fun recreateXmlProbe(): StateSkeletonLayout {
        val themed = ContextThemeWrapper(requireContext(), R.style.DemoSslThemeLayer)
        val host = binding.demoXmlProbeHost
        host.removeAllViews()
        return (LayoutInflater.from(themed).inflate(R.layout.demo_priority_probe, host, false) as StateSkeletonLayout)
            .also { host.addView(it); xmlProbe = it }
    }

    private fun attachStepInstance(context: android.content.Context, index: Int): StateSkeletonLayout {
        val state = DemoSslDefaults.createStateLayout(context)
        val heightPx = (220 * resources.displayMetrics.density).toInt()
        state.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            heightPx,
        )
        val label = TextView(requireContext()).apply {
            text = getString(R.string.demo_defaults_step_instance, index)
            textSize = 12f
        }
        val box = FrameLayout(requireContext())
        box.addView(state)
        binding.demoSteps.addView(label)
        binding.demoSteps.addView(box)
        state.showLoading()
        return state
    }

    private fun appendStep(title: String, expected: String, state: StateSkeletonLayout) {
        binding.demoCompare.append("\n\n$title\n期望：$expected\n实际：${state.describeProbes()}")
        binding.demoSteps.addView(
            TextView(requireContext()).apply {
                text = "${state.describeProbes()}\n"
                textSize = 11f
            },
        )
    }

    override fun onDestroyView() {
        DemoSslDefaults.installCanonical()
        xmlProbe = null
        _binding = null
        log = null
        super.onDestroyView()
    }

    private companion object {
        val LEGEND: String = listOf(
            "探针字段各层取值：",
            "字段 | 库内置 | Theme | Global | XML | setter",
            "effect | SHIMMER | SOLID | PULSE | shimmer | —",
            "maskColor | #FFE3E6EA | #FFB39DDB | #FF80CBC4 | #FFE6A23C | —",
            "cornerRadius | 8dp | 20dp | 6px | 30dp | —",
            "animationDurationMillis | 1200 | （未声明） | 500 | 800 | 1500",
            "",
            "胜出规则：库内置 < Theme < Global < XML < 运行时 setter",
        ).joinToString("\n")
    }
}
