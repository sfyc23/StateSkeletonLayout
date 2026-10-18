package com.sfyc.demo.resume

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentResumeDemoBinding
import com.sfyc.demo.wrapDemoPage
import kotlinx.coroutines.launch

/**
 * 简历局部骨架 Demo：单 EpoxyRecyclerView 整页滚动，
 * 上半 500ms 骨架→内容，下半按场景骨架→展开/空/错（方案一 A1 双实例）。
 */
class ResumeDemoFragment : Fragment() {

    private val viewModel: ResumeViewModel by viewModels {
        viewModelFactory { initializer { ResumeViewModel(createSavedStateHandle()) } }
    }

    private var binding: FragmentResumeDemoBinding? = null
    private var log: DemoLog? = null
    private var controller: ResumeController? = null
    private var lastHeaderPhase: String? = null
    private var lastWorkPhase: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val created = FragmentResumeDemoBinding.inflate(inflater, container, false)
        binding = created
        return wrapDemoPage(inflater, created.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        val b = checkNotNull(binding)
        log = DemoLog(b.resumeLog)
        val createdController = ResumeController(
            onHeaderClick = { id -> log?.add("CLICK header $id") },
            onSkillClick = { id -> log?.add("CLICK skills $id") },
            onWorkItemClick = { id -> log?.add("CLICK work $id") },
            onWorkRetry = { viewModel.retryWork() },
            onHeaderContentRendered = { datasetId, requestId ->
                viewModel.onHeaderContentRendered(datasetId, requestId)
            },
            onWorkSectionContentRendered = { datasetId, requestId ->
                viewModel.onWorkSectionContentRendered(datasetId, requestId)
            },
            sectionStatusText = { phase ->
                when (phase) {
                    is WorkSectionPhase.Loading,
                    is WorkSectionPhase.TransitioningToContent,
                    -> getString(R.string.resume_section_syncing)
                    is WorkSectionPhase.ContentExpanded ->
                        getString(R.string.resume_section_count, phase.experiences.size)
                    is WorkSectionPhase.Empty -> getString(R.string.resume_section_empty)
                    is WorkSectionPhase.Error -> getString(R.string.resume_section_error)
                }
            },
        )
        controller = createdController
        b.resumeEpoxy.layoutManager = LinearLayoutManager(requireContext())
        b.resumeEpoxy.setController(createdController)
        createdController.adapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        b.resumeCandidate.setOnClickListener { viewModel.switchCandidate() }
        b.resumeReload.setOnClickListener { viewModel.reloadWork() }
        b.resumeScenario.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            ResumeScenario.entries.map { getString(scenarioTitle(it)) },
        )
        b.resumeScenario.setSelection(viewModel.uiState.value.scenario.ordinal)
        b.resumeScenario.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.selectScenario(ResumeScenario.entries[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        b.resumeLatencyGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setWorkLatency(
                when (checkedId) {
                    R.id.resume_latency_100 -> 100L
                    R.id.resume_latency_600 -> 600L
                    else -> 1500L
                },
            )
        }

        viewModel.ensureInitialLoad()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(ui: ResumeUiState) {
        val b = binding ?: return
        controller?.setData(ui)

        val headerKey = ui.headerPhase.javaClass.simpleName
        if (lastHeaderPhase != headerKey) {
            lastHeaderPhase = headerKey
            log?.add("HEADER → $headerKey")
        }
        val workKey = ui.workPhase.javaClass.simpleName
        if (lastWorkPhase != workKey) {
            lastWorkPhase = workKey
            val extra = (ui.workPhase as? WorkSectionPhase.ContentExpanded)?.experiences?.size
                ?.let { "($it)" } ?: ""
            log?.add("WORK → $workKey$extra")
        }

        val profileName = when (val h = ui.headerPhase) {
            is HeaderPhase.Content -> h.profile.name
            is HeaderPhase.TransitioningToContent -> h.profile.name
            is HeaderPhase.Loading -> ui.candidateId
        }
        b.resumeCandidate.text = getString(R.string.resume_candidate, profileName)
        b.resumeStatus.text = getString(
            R.string.resume_status,
            getString(scenarioTitle(ui.scenario)),
            ui.workLatencyMillis,
            ui.datasetId,
            headerKey,
            workKey,
        )
        b.resumeReload.isEnabled = !ui.isWorkBusy
        when (ui.workLatencyMillis) {
            100L -> b.resumeLatencyGroup.check(R.id.resume_latency_100)
            600L -> b.resumeLatencyGroup.check(R.id.resume_latency_600)
            else -> b.resumeLatencyGroup.check(R.id.resume_latency_1500)
        }
        if (b.resumeScenario.selectedItemPosition != ui.scenario.ordinal) {
            b.resumeScenario.setSelection(ui.scenario.ordinal)
        }
    }

    private fun scenarioTitle(scenario: ResumeScenario): Int = when (scenario) {
        ResumeScenario.SUCCESS -> R.string.resume_scenario_success
        ResumeScenario.EMPTY -> R.string.resume_scenario_empty
        ResumeScenario.ERROR_ONCE -> R.string.resume_scenario_error_once
        ResumeScenario.ERROR_ALWAYS -> R.string.resume_scenario_error
    }

    override fun onDestroyView() {
        controller?.cancelPendingModelBuild()
        binding?.resumeEpoxy?.clear()
        controller = null
        binding = null
        log = null
        lastHeaderPhase = null
        lastWorkPhase = null
        super.onDestroyView()
    }
}
