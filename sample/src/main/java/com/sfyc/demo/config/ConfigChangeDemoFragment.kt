package com.sfyc.demo.config

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.sfyc.demo.DemoLog
import com.sfyc.demo.defaults.toHexColor
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentConfigChangeBinding

/**
 * 配置变更示例：昼夜 / 字体缩放 / RTL / locale 四项 Configuration 变化下的表现。
 *
 * 观察点：
 * - 摘要区展示当前 `resources.configuration` 的四个维度与骨架当前的 maskColor。
 * - 「重建 Activity」触发完整重建，摘要与骨架都按新 Configuration 重新取值。
 * - 库的 [com.sfyc.ssl.StateSkeletonLayout] 已实现 `onConfigurationChanged`：
 *   三项颜色 / 圆角全部来自资源（未被 XML / Theme / Global 显式覆盖）的实例，
 *   会在配置变化时就地重新解析 `ssl_mask_default` 等资源，并清空状态页缓存，
 *   下次显示时按新 Configuration 重新 inflate——即不重建也能拿到新主题色。
 *
 * 摘要的刷新挂在 Activity 的配置变更回调上：宿主若在清单里声明了 `configChanges`
 * 而走「不重建」路径，摘要也不会停留在旧值。
 */
class ConfigChangeDemoFragment : Fragment() {

    private var _binding: FragmentConfigChangeBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    /** Activity 不重建时的配置变更回调；onStart / onStop 成对注册与注销。 */
    private val configurationListener = { _: Configuration -> refreshSummary() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConfigChangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        log = DemoLog(binding.demoLog)
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
            refreshSummary()
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading(); refreshSummary() }
        binding.demoBtnContent.setOnClickListener { state.showContent(); refreshSummary() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty(); refreshSummary() }
        binding.demoBtnError.setOnClickListener { state.showError(); refreshSummary() }
        binding.demoBtnToggleNight.setOnClickListener { toggleNightMode() }
        binding.demoBtnRecreate.setOnClickListener { recreateActivity() }
        log?.add("本页 View 已创建（重建后会重新出现这一行）")
        state.showLoading()
        // 无条件刷新一次：重建后恢复的状态可能就是 LOADING，showLoading() 是幂等空操作，
        // 状态回调不会触发，摘要必须以当前 Configuration 主动渲染一次。
        refreshSummary()
    }

    override fun onStart() {
        super.onStart()
        (activity as? ComponentActivity)?.addOnConfigurationChangedListener(configurationListener)
    }

    override fun onStop() {
        (activity as? ComponentActivity)?.removeOnConfigurationChangedListener(configurationListener)
        super.onStop()
    }

    private fun toggleNightMode() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val target = if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        log?.add("setDefaultNightMode(${if (night) "NO" else "YES"})，AppCompat 会自动重建 Activity")
        AppCompatDelegate.setDefaultNightMode(target)
    }

    private fun recreateActivity() {
        log?.add("activity.recreate()：完整重建，观察摘要与骨架颜色是否跟随新 Configuration")
        activity?.recreate()
    }

    /** 摘要区：四项 Configuration 维度 + 骨架当前取到的资源派生值。 */
    private fun refreshSummary() {
        if (_binding == null) return
        val configuration = resources.configuration
        val state = binding.demoState
        val config = state.skeletonConfig
        binding.demoConfigSummary.text = buildString {
            appendLine("uiMode          = ${nightLabel(configuration)}")
            appendLine("fontScale       = ${configuration.fontScale}")
            appendLine("layoutDirection = ${layoutDirectionLabel(configuration)}")
            appendLine("locale          = ${configuration.locales[0]}")
            appendLine("--")
            appendLine("renderedState   = ${state.renderedState}")
            appendLine("useSkeleton     = ${state.useSkeleton}")
            appendLine("maskColor       = ${config.maskColor.toHexColor()}")
            appendLine("cornerRadius    = ${config.cornerRadius}px")
        }
    }

    private fun nightLabel(configuration: Configuration): String =
        if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
            getString(R.string.demo_config_night)
        } else {
            getString(R.string.demo_config_not_night)
        }

    private fun layoutDirectionLabel(configuration: Configuration): String =
        if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            getString(R.string.demo_config_rtl)
        } else {
            getString(R.string.demo_config_ltr)
        }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }
}
