package com.sfyc.demo.accessibility

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.databinding.FragmentAccessibilityBinding

/**
 * 无障碍示例：状态播报开关、焦点隔离说明、动画关闭降级。
 *
 * - 进入 Loading / Empty / Error 时播报一次，重复提交不重复播报。
 * - 骨架层不进焦点顺序；隐藏内容不可被读屏访问。
 * - 开发者选项中把动画比例设为 0x，可观察静态骨架降级。
 */
class AccessibilityDemoFragment : Fragment() {

    private var _binding: FragmentAccessibilityBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAccessibilityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new（已播报一次）")
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty() }
        binding.demoBtnError.setOnClickListener { state.showError() }
        binding.demoSwitchAnnounce.setOnCheckedChangeListener { _, checked ->
            state.announceStateChanges = checked
            log?.add("播报开关：${if (checked) "开" else "关"}")
        }
        state.showLoading()
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
