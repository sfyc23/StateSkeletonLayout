package com.sfyc.demo.resume

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 简历双域 ViewModel：上下各一条 Job，双 requestId 做迟到丢弃。
 *
 * 上半 500ms 恒成功（Loading → Transitioning → Content）；
 * 下半按场景与延迟档进入 Loading / Transitioning / ContentExpanded / Empty / Error。
 */
class ResumeViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: FakeResumeRepository = FakeResumeRepository(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ResumeUiState(
            candidateId = savedStateHandle.get<String>("candidateId")
                ?: ResumeUiState.CANDIDATE_LIN,
            scenario = savedStateHandle.get<String>("scenario")?.let {
                runCatching { ResumeScenario.valueOf(it) }.getOrNull()
            } ?: ResumeScenario.SUCCESS,
            workLatencyMillis = savedStateHandle.get<Long>("workLatency") ?: 1500L,
        ),
    )
    val uiState = mutableState.asStateFlow()

    private var initialized = false
    private var datasetSequence = 1L
    private var headerRequestSequence = 0L
    private var workRequestSequence = 0L
    private var headerJob: Job? = null
    private var workJob: Job? = null

    fun ensureInitialLoad() {
        if (initialized) return
        initialized = true
        val current = mutableState.value
        datasetSequence = current.datasetId
        startHeader()
        startWork()
    }

    fun switchCandidate() {
        val next = if (mutableState.value.candidateId == ResumeUiState.CANDIDATE_LIN) {
            ResumeUiState.CANDIDATE_SU
        } else {
            ResumeUiState.CANDIDATE_LIN
        }
        savedStateHandle["candidateId"] = next
        datasetSequence += 1
        mutableState.update {
            it.copy(
                candidateId = next,
                datasetId = datasetSequence,
                headerPhase = HeaderPhase.Loading,
                workPhase = WorkSectionPhase.Loading,
                workAttempt = 0,
                isWorkBusy = false,
            )
        }
        startHeader()
        startWork()
    }

    fun selectScenario(scenario: ResumeScenario) {
        if (scenario == mutableState.value.scenario) return
        savedStateHandle["scenario"] = scenario.name
        // 场景只重拉下半，上半保持不动。
        mutableState.update { it.copy(scenario = scenario, workPhase = WorkSectionPhase.Loading, workAttempt = 0, isWorkBusy = false) }
        startWork()
    }

    fun setWorkLatency(latencyMillis: Long) {
        require(latencyMillis >= 0)
        if (latencyMillis == mutableState.value.workLatencyMillis) return
        savedStateHandle["workLatency"] = latencyMillis
        mutableState.update { it.copy(workLatencyMillis = latencyMillis) }
        // 延迟档改变后重拉下半，便于直接观察 100/600/1500 三档。
        mutableState.update { it.copy(workPhase = WorkSectionPhase.Loading, workAttempt = 0, isWorkBusy = false) }
        startWork()
    }

    fun reloadWork() {
        mutableState.update { it.copy(workPhase = WorkSectionPhase.Loading, workAttempt = 0, isWorkBusy = false) }
        startWork()
    }

    fun retryWork() {
        val current = mutableState.value
        if (current.workPhase !is WorkSectionPhase.Error || current.isWorkBusy) return
        mutableState.update { it.copy(workPhase = WorkSectionPhase.Loading) }
        startWork(keepAttempt = true)
    }

    fun onHeaderContentRendered(datasetId: Long, requestId: Long) {
        val current = mutableState.value
        val transitioning = current.headerPhase as? HeaderPhase.TransitioningToContent ?: return
        if (current.datasetId != datasetId || current.headerRequestId != requestId) return
        mutableState.update { it.copy(headerPhase = HeaderPhase.Content(transitioning.profile)) }
    }

    fun onWorkSectionContentRendered(datasetId: Long, requestId: Long) {
        val current = mutableState.value
        val transitioning = current.workPhase as? WorkSectionPhase.TransitioningToContent ?: return
        if (current.datasetId != datasetId || current.workRequestId != requestId) return
        mutableState.update {
            it.copy(
                workPhase = WorkSectionPhase.ContentExpanded(
                    transitioning.experiences,
                    transitioning.updatedAtText,
                ),
            )
        }
    }

    private fun startHeader() {
        val datasetId = mutableState.value.datasetId
        val candidateId = mutableState.value.candidateId
        val requestId = ++headerRequestSequence
        mutableState.update { it.copy(headerRequestId = requestId, headerPhase = HeaderPhase.Loading) }
        headerJob?.cancel()
        headerJob = viewModelScope.launch {
            try {
                val profile = repository.getProfile(candidateId)
                val latest = mutableState.value
                if (latest.datasetId != datasetId || latest.headerRequestId != requestId) return@launch
                mutableState.update { it.copy(headerPhase = HeaderPhase.TransitioningToContent(profile)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun startWork(keepAttempt: Boolean = false) {
        val snapshot = mutableState.value
        val datasetId = snapshot.datasetId
        val requestId = ++workRequestSequence
        val attempt = if (keepAttempt) snapshot.workAttempt + 1 else 0
        mutableState.update { it.copy(workRequestId = requestId, workAttempt = attempt, isWorkBusy = true) }
        val scenario = snapshot.scenario
        val latency = snapshot.workLatencyMillis
        val candidateId = snapshot.candidateId
        workJob?.cancel()
        workJob = viewModelScope.launch {
            try {
                val items = repository.getWorkExperiences(candidateId, scenario, latency, attempt)
                val latest = mutableState.value
                if (latest.datasetId != datasetId || latest.workRequestId != requestId) return@launch
                if (items.isEmpty()) {
                    mutableState.update { it.copy(workPhase = WorkSectionPhase.Empty, isWorkBusy = false) }
                } else {
                    mutableState.update {
                        it.copy(
                            workPhase = WorkSectionPhase.TransitioningToContent(items, nowText()),
                            isWorkBusy = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                val latest = mutableState.value
                if (latest.datasetId != datasetId || latest.workRequestId != requestId) return@launch
                mutableState.update {
                    it.copy(
                        workPhase = WorkSectionPhase.Error(failure.message ?: "模拟请求失败"),
                        isWorkBusy = false,
                    )
                }
            } finally {
                val latest = mutableState.value
                if (latest.datasetId == datasetId && latest.workRequestId == requestId) {
                    mutableState.update { it.copy(isWorkBusy = false) }
                }
            }
        }
    }

    private fun nowText(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
}
