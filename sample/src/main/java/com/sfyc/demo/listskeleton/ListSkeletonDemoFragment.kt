package com.sfyc.demo.listskeleton

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sfyc.demo.wrapDemoPage
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentListSkeletonBinding

/**
 * 列表骨架整屏模板示例：一个 [com.sfyc.ssl.StateSkeletonLayout] 覆盖整屏多行。
 *
 * # 为什么推荐整屏模板而不是每个 item 一个实例
 *
 * - 每个实例都会按自己的尺寸构建**一张全尺寸 ALPHA_8 遮罩**：8 行列表用 8 个实例，
 *   就是 8 份逐像素的遮罩内存，行数越多越线性增长。
 * - 每个实例还各自订阅动画时钟。库已把动画改为共享时钟（同一进程内一轮动画只有
 *   一个时间源），但遮罩内存仍是每实例一份，这是实例数最直接的代价。
 * - 骨架挂在滚动容器外层时，列表 Adapter 完全不需要为了骨架改写：
 *   业务侧照常 `submitList` / `notifyDataSetChanged`，骨架只负责 Loading 那一屏。
 *
 * 本页用一个实例 + `skeleton_list_rows_template`（静态 8 行形状）达到同样的视觉效果，
 * 内存占用与列表长度无关。
 */
class ListSkeletonDemoFragment : Fragment() {

    private var _binding: FragmentListSkeletonBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentListSkeletonBinding.inflate(inflater, container, false)
        return wrapDemoPage(inflater, binding.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        log = DemoLog(binding.demoLog)
        binding.demoList.layoutManager = LinearLayoutManager(requireContext())
        binding.demoList.adapter = RowAdapter()
        val state = binding.demoState
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new（遮罩 ${state.diagnostics().maskWidth}x${state.diagnostics().maskHeight}）")
        }
        binding.demoBtnLoading.setOnClickListener { state.showLoading() }
        binding.demoBtnContent.setOnClickListener { state.showContent() }
        binding.demoBtnEmpty.setOnClickListener { state.showEmpty() }
        binding.demoBtnError.setOnClickListener { state.showError() }
        log?.add("整屏模板骨架：一个实例覆盖 8 行，列表 Adapter 不被替换")
        state.showLoading()
    }

    override fun onDestroyView() {
        _binding = null
        log = null
        super.onDestroyView()
    }

    /** 示例业务 Adapter：20 条本地假数据，骨架期间同样不被替换。 */
    private class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

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
