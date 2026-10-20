package com.sfyc.demo.retry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.demo.DemoLog
import com.sfyc.demo.defaults.DemoSslDefaults
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentMultiRetryBinding
import com.sfyc.demo.wrapDemoPage
import com.sfyc.ssl.SslGlobalDefaults
import com.sfyc.ssl.StateLayoutState
import com.sfyc.ssl.StateRetryConfig
import com.sfyc.ssl.StateSkeletonLayoutDefaults

/**
 * 多重试入口示例：多个入口、独立局部实例、禁用、替换和回调计数。
 *
 * 示例全局配置通过 [DemoSslDefaults.canonical.copy] 一次安装后创建演示控件；
 * 离开示例恢复 canonical，已有控件仍维持自身快照。
 * 宿主在 `onStop()` 关闭事件，在 `onStart()` 按当前演示状态恢复可用性。
 */
class MultiRetryDemoFragment : Fragment() {

    private var binding: FragmentMultiRetryBinding? = null
    private var log: DemoLog? = null
    private var countA: Int = 0
    private var countB: Int = 0
    private var enabled: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // 一次安装演示专用全局默认：之后创建的控件自动继承。
        StateSkeletonLayoutDefaults.install(
            DemoSslDefaults.canonical.copy(
                retryConfig = StateRetryConfig(
                    emptyViewIds = listOf(R.id.demo_empty_image, R.id.demo_empty_text),
                    errorViewIds = listOf(R.id.demo_error_image, R.id.demo_error_retry),
                ),
            ),
        )
        val created = FragmentMultiRetryBinding.inflate(inflater, container, false)
        binding = created
        return wrapDemoPage(inflater, created.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        val b = checkNotNull(binding)
        log = DemoLog(b.demoLog)
        // A 继承全局默认；B 在 XML 中显式关闭 ERROR，验证实例隔离。
        b.demoStateA.setOnRetryListener { layout, state, clicked ->
            countA++
            renderCounts()
            log?.add("A onRetry state=$state view=${resources.getResourceEntryName(clicked.id)} layout=$layout")
        }
        b.demoStateB.setOnRetryListener { _, state, clicked ->
            countB++
            renderCounts()
            log?.add("B onRetry state=$state view=${resources.getResourceEntryName(clicked.id)}")
        }
        b.demoBtnEmpty.setOnClickListener {
            b.demoStateA.showEmpty()
            b.demoStateB.showEmpty()
        }
        b.demoBtnError.setOnClickListener {
            b.demoStateA.showError()
            b.demoStateB.showError()
        }
        b.demoBtnToggle.setOnClickListener {
            enabled = !enabled
            applyEnabled()
        }
        renderCounts()
        applyEnabled()
        b.demoStateA.showEmpty()
        b.demoStateB.showEmpty()
        log?.add("全局安装一次 + 每页注册一次回调；A 全开，B 的 ERROR 已显式关闭")
    }

    override fun onStart() {
        super.onStart()
        // 按当前演示状态恢复可用性，同时渲染按钮可用性。
        applyEnabled()
    }

    override fun onStop() {
        // 离开时关闭事件，避免后台点击。
        binding?.demoStateA?.retryClicksEnabled = false
        binding?.demoStateB?.retryClicksEnabled = false
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.demoStateA?.setOnRetryListener(null)
        binding?.demoStateB?.setOnRetryListener(null)
        binding = null
        log = null
        // 恢复基准全局默认，不让其他示例继承演示专用 ID。
        DemoSslDefaults.installCanonical()
        super.onDestroyView()
    }

    private fun applyEnabled() {
        val b = binding ?: return
        b.demoStateA.retryClicksEnabled = enabled
        // B 的 ERROR 已关闭：只控制 EMPTY 的可用性，ERROR 保持关闭语义。
        // 这里仍整体设置开关，关闭列表为空的 ERROR 本就无入口，不受影响。
        b.demoStateB.retryClicksEnabled = enabled
        b.demoBtnToggle.text = getString(
            if (enabled) R.string.demo_multi_btn_disable else R.string.demo_multi_btn_enable,
        )
        log?.add(if (enabled) "重试已启用" else "重试已禁用")
    }

    private fun renderCounts() {
        binding?.demoRetryCount?.text = getString(R.string.demo_multi_retry_count, countA, countB)
    }
}
