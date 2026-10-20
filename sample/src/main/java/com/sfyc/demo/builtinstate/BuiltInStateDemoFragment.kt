package com.sfyc.demo.builtinstate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sfyc.demo.wrapDemoPage
import com.sfyc.ssl.StateLayoutState
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentBuiltinStateBinding
import com.sfyc.ssl.R as SslR

/**
 * 内置状态页示例：XML 完全不配置 `ssl_emptyLayout` / `ssl_errorLayout` / 重试 ID 数组，
 * 验证 EMPTY / ERROR 仍能直接显示库内置兜底页面。
 *
 * 内置布局的三个固定 ID（库 `ids.xml` 声明，宿主可直接引用）：
 * - `com.sfyc.ssl.R.id.ssl_builtin_state_root`：根节点
 * - `com.sfyc.ssl.R.id.ssl_builtin_state_message`：文案 TextView
 * - `com.sfyc.ssl.R.id.ssl_builtin_state_retry`：重试按钮
 *
 * 未显式配置重试入口时，控件会自动把内置重试按钮接成默认错误入口，
 * 宿主只需 `setOnRetryListener` 就能收到事件。想改主题文案时用
 * `getStateView(ERROR)` 拿到根节点后 `findViewById` 替换即可。
 */
class BuiltInStateDemoFragment : Fragment() {

    private var _binding: FragmentBuiltinStateBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var retryCount: Int = 0
    private var replaced: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBuiltinStateBinding.inflate(inflater, container, false)
        return wrapDemoPage(inflater, binding.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        state.setOnRetryListener { _, retryState, clicked ->
            retryCount++
            binding.demoRetryCount.text = getString(R.string.demo_builtin_retry_count, retryCount)
            log?.add("收到内置重试事件：state=$retryState view=${clicked.id}(${SslR.id.ssl_builtin_state_retry})")
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading() }
        binding.demoBtnContent.setOnClickListener { state.showContent() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty() }
        binding.demoBtnError.setOnClickListener { state.showError() }
        binding.demoBtnReplaceMessage.setOnClickListener { replaceBuiltInMessage() }
        binding.demoRetryCount.text = getString(R.string.demo_builtin_retry_count, 0)
        log?.add("页面未配置任何状态页布局：EMPTY / ERROR 走库内置兜底，重试入口由库自动接管")
        state.showLoading()
    }

    /**
     * 用 `getStateView(ERROR)` 拿内置错误页根节点，再替换文案。
     *
     * 先切到 ERROR 再改，保证状态页已按需创建；`getStateView` 只查询缓存，不会创建页面。
     */
    private fun replaceBuiltInMessage() {
        val state = binding.demoState
        state.showError()
        val root = state.getStateView(StateLayoutState.ERROR)
        if (root == null) {
            log?.add("getStateView(ERROR) 返回 null，先切到 ERROR 再试")
            return
        }
        replaced = !replaced
        val message = root.findViewById<TextView>(SslR.id.ssl_builtin_state_message)
        message?.text = if (replaced) {
            getString(R.string.demo_builtin_replaced_message)
        } else {
            getString(R.string.demo_builtin_origin_message)
        }
        val retry = root.findViewById<Button>(SslR.id.ssl_builtin_state_retry)
        log?.add(
            "getStateView(ERROR).findViewById：root=${SslR.id.ssl_builtin_state_root} " +
                "message=${message != null} retry=${retry != null}，文案已替换为「${message?.text}」",
        )
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
