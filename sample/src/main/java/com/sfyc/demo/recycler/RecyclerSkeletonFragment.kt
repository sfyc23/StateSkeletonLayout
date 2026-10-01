package com.sfyc.demo.recycler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.DemoLog
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentRecyclerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 列表骨架示例：Linear / Grid 共用模板骨架思路，业务 Adapter 永不替换。
 *
 * Grid 切换时同步更换为按列数设计的模板，滚动位置与 Adapter 状态不受影响。
 */
class RecyclerSkeletonFragment : Fragment() {

    private var _binding: FragmentRecyclerBinding? = null
    private val binding get() = _binding!!
    private var log: DemoLog? = null
    private var loadingJob: Job? = null
    private var isGrid: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecyclerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log = DemoLog(binding.demoLog)
        binding.demoContentList.layoutManager = LinearLayoutManager(requireContext())
        binding.demoContentList.adapter = ProfileRowAdapter()
        binding.demoState.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
        }
        binding.demoBtnToggle.setOnClickListener { toggleLayout() }
        binding.demoBtnReplay.setOnClickListener { simulateLoad() }
        simulateLoad()
    }

    private fun toggleLayout() {
        isGrid = !isGrid
        val state = binding.demoState
        binding.demoContentList.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 2).also {
                state.skeletonConfig = state.skeletonConfig.copy(
                    templateLayoutResId = R.layout.skeleton_grid_template,
                )
            }
        } else {
            LinearLayoutManager(requireContext()).also {
                state.skeletonConfig = state.skeletonConfig.copy(
                    templateLayoutResId = R.layout.skeleton_list_template,
                )
            }
        }
        log?.add(if (isGrid) "切换为 Grid（模板同步更换为双列）" else "切换为 Linear")
        state.invalidateSkeleton()
    }

    private fun simulateLoad() {
        loadingJob?.cancel()
        val state = binding.demoState
        state.showLoading()
        log?.add("请求 LOADING（列表模板骨架，不替换 Adapter）")
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
