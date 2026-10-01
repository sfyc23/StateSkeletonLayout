package com.sfyc.demo.defaults

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.ssl.SkeletonSource
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateSkeletonLayoutDefaults
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentDefaultsInvalidBinding

/**
 * 非法值校验示例：安装非法全局默认后创建实例，观察含属性名的 IllegalArgumentException。
 *
 * 校验发生在实例构造合并阶段（SkeletonConfig.init），install 本身不校验。
 */
class InvalidValueFragment : Fragment() {

    private var _binding: FragmentDefaultsInvalidBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDefaultsInvalidBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)

        binding.demoBtnRadius.setOnClickListener {
            verify("cornerRadius 为负", SslGlobalDefaults(cornerRadius = -1f), "cornerRadius")
        }
        binding.demoBtnDuration.setOnClickListener {
            verify("animationDurationMillis 为 0", SslGlobalDefaults(animationDurationMillis = 0L), "animationDurationMillis")
        }
        binding.demoBtnPulse.setOnClickListener {
            verify(
                "pulseMinAlpha 大于 pulseMaxAlpha",
                SslGlobalDefaults(pulseMinAlpha = 0.9f, pulseMaxAlpha = 0.2f),
                "pulse",
            )
        }
        binding.demoBtnTemplate.setOnClickListener {
            verify(
                "TEMPLATE 无模板",
                SslGlobalDefaults(skeletonSource = SkeletonSource.TEMPLATE),
                "TEMPLATE",
            )
        }
    }

    private fun verify(title: String, defaults: SslGlobalDefaults, expectName: String) {
        log?.add("install($title) 后创建实例")
        var message = "未抛出异常"
        val result = runCatching {
            DemoSslDefaults.createStateLayout(requireContext())
        }
        result.onSuccess {
            message = "未抛出异常（不符合期望）"
        }.onFailure { error ->
            message = "${error::class.java.simpleName}: ${error.message}"
            log?.add("抛出 ${error::class.java.simpleName}")
        }
        binding.demoResult.text = buildString {
            appendLine("操作：$title")
            appendLine("结果：$message")
            appendLine("校验发生在实例构造合并阶段（SkeletonConfig.init），install 本身不校验。")
            if (!result.isFailure) {
                appendLine("期望信息应包含：$expectName")
            } else {
                appendLine("期望信息包含：$expectName → ${if (message.contains(expectName)) "命中" else "未命中（请核对异常信息）"}")
            }
        }
        // 每次演示后立即恢复基准，不把非法配置带入后续操作。
        DemoSslDefaults.installCanonical()
        log?.add("installCanonical() 复位")
    }

    override fun onDestroyView() {
        DemoSslDefaults.installCanonical()
        _binding = null
        log = null
        super.onDestroyView()
    }
}
