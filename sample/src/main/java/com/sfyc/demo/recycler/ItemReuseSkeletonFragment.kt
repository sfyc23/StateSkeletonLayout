package com.sfyc.demo.recycler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sfyc.demo.wrapDemoPage
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentItemReuseSkeletonBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 条目复用骨架示例（T2）：直接复用成功态 `item_profile_row` 拼装模板骨架。
 *
 * - XML 只声明形状（`ssl_skeletonItemLayout` + `ssl_skeletonItemCount`，单类型）；
 *   `wrap_content` 的标题 / 副标题由 [applyItemReuse] 的 binder 填充占位文本，
 *   否则离屏 item 量不出文本行高度。
 * - 「切换」按钮在 T2（条目复用 + 占位绑定）与 T1（手写 `skeleton_list_template`）
 *   之间对比：同一 `RecyclerView`、同一份成功布局，骨架对齐度一致；
 *   业务 Adapter 全程不被替换。
 */
class ItemReuseSkeletonFragment : Fragment() {

    private var _binding: FragmentItemReuseSkeletonBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null
    private var isItemReuse: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentItemReuseSkeletonBinding.inflate(inflater, container, false)
        return wrapDemoPage(inflater, binding.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        log = DemoLog(binding.demoLog)
        binding.demoContentList.layoutManager = LinearLayoutManager(requireContext())
        binding.demoContentList.adapter = ProfileRowAdapter()
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        binding.demoBtnToggle.setOnClickListener { toggleTemplate() }
        binding.demoBtnReplay.setOnClickListener { simulateLoad() }
        applyItemReuse()
        simulateLoad()
    }

    private fun applyItemReuse() {
        isItemReuse = true
        binding.demoState.setSkeletonItemTemplate(
            layoutResId = R.layout.item_profile_row,
            itemCount = 8,
        ) { itemView, _ ->
            itemView.findViewById<TextView>(R.id.demo_row_title)?.text =
                getString(R.string.demo_item_reuse_placeholder_title)
            itemView.findViewById<TextView>(R.id.demo_row_subtitle)?.text =
                getString(R.string.demo_item_reuse_placeholder_subtitle)
        }
        log?.add("T2 条目复用：item_profile_row × 8 + 占位绑定")
    }

    private fun applyHandwrittenTemplate() {
        isItemReuse = false
        val state = binding.demoState
        state.clearSkeletonItems()
        state.skeletonConfig = state.skeletonConfig.copy(
            templateLayoutResId = R.layout.skeleton_list_template,
        )
        state.invalidateSkeleton()
        log?.add("T1 手写模板：skeleton_list_template")
    }

    private fun toggleTemplate() {
        if (isItemReuse) applyHandwrittenTemplate() else applyItemReuse()
        log?.add(if (isItemReuse) "已切回 T2 条目复用" else "已切到 T1 手写模板")
        // 切换模板后若正处 Loading，主动刷新遮罩以便即时对比。
        binding.demoState.invalidateSkeleton()
    }

    private fun simulateLoad() {
        loadingJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING（条目复用模板骨架，不替换 Adapter）")
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(2000L)
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

    /** 示例业务 Adapter：20 条本地假数据，演示期间不被骨架替换。 */
    private class ProfileRowAdapter : RecyclerView.Adapter<ProfileRowAdapter.Holder>() {

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.demo_row_title)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.title.text = holder.itemView.context.getString(R.string.demo_row_title, position + 1)
        }

        override fun getItemCount(): Int = 20
    }
}
