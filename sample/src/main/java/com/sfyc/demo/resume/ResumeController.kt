package com.sfyc.demo.resume

import com.airbnb.epoxy.TypedEpoxyController
import com.sfyc.demo.resume.models.HeaderSectionStateViewModel_
import com.sfyc.demo.resume.models.ResumeFooterViewModel_
import com.sfyc.demo.resume.models.ResumeHeaderViewModel_
import com.sfyc.demo.resume.models.ResumeHighlightsViewModel_
import com.sfyc.demo.resume.models.ResumeSectionHeaderViewModel_
import com.sfyc.demo.resume.models.WorkExperienceItemViewModel_
import com.sfyc.demo.resume.models.WorkSectionStateViewModel_
import com.sfyc.ssl.StateLayoutState

class ResumeController(
    private val onHeaderClick: (String) -> Unit,
    private val onSkillClick: (String) -> Unit,
    private val onWorkItemClick: (String) -> Unit,
    private val onWorkRetry: () -> Unit,
    private val onHeaderContentRendered: (datasetId: Long, requestId: Long) -> Unit,
    private val onWorkSectionContentRendered: (datasetId: Long, requestId: Long) -> Unit,
    private val sectionStatusText: (WorkSectionPhase) -> String,
) : TypedEpoxyController<ResumeUiState>() {

    override fun buildModels(state: ResumeUiState?) {
        val ui = state ?: return

        when (val header = ui.headerPhase) {
            is HeaderPhase.Loading,
            is HeaderPhase.TransitioningToContent,
            -> {
                val capturedDataset = ui.datasetId
                val capturedRequest = ui.headerRequestId
                HeaderSectionStateViewModel_()
                    .id("resume-header-state")
                    .targetState(StateLayoutState.LOADING.takeIf { header is HeaderPhase.Loading }
                        ?: StateLayoutState.CONTENT)
                    .onContentRenderedCallback {
                        onHeaderContentRendered(capturedDataset, capturedRequest)
                    }
                    .addTo(this)
            }
            is HeaderPhase.Content -> {
                ResumeHeaderViewModel_()
                    .id("resume-header")
                    .profile(header.profile)
                    .onHeaderClick(onHeaderClick)
                    .addTo(this)
                ResumeHighlightsViewModel_()
                    .id("resume-highlights")
                    .profile(header.profile)
                    .onSkillClick(onSkillClick)
                    .addTo(this)
            }
        }

        ResumeSectionHeaderViewModel_()
            .id("resume-work-section-header")
            .statusText(sectionStatusText(ui.workPhase))
            .addTo(this)

        when (val phase = ui.workPhase) {
            is WorkSectionPhase.Loading,
            is WorkSectionPhase.TransitioningToContent,
            is WorkSectionPhase.Empty,
            is WorkSectionPhase.Error,
            -> {
                val capturedDataset = ui.datasetId
                val capturedRequest = ui.workRequestId
                WorkSectionStateViewModel_()
                    .id("resume-work-section-state")
                    .targetState(
                        when (phase) {
                            is WorkSectionPhase.Loading,
                            is WorkSectionPhase.TransitioningToContent,
                            -> StateLayoutState.LOADING.takeIf { phase is WorkSectionPhase.Loading }
                                ?: StateLayoutState.CONTENT
                            is WorkSectionPhase.Empty -> StateLayoutState.EMPTY
                            is WorkSectionPhase.Error -> StateLayoutState.ERROR
                            is WorkSectionPhase.ContentExpanded -> StateLayoutState.CONTENT
                        },
                    )
                    .retryEnabled(!ui.isWorkBusy && phase is WorkSectionPhase.Error)
                    .errorMessage((phase as? WorkSectionPhase.Error)?.message)
                    .onRetryClick(onWorkRetry)
                    .onContentRenderedCallback {
                        onWorkSectionContentRendered(capturedDataset, capturedRequest)
                    }
                    .addTo(this)
            }
            is WorkSectionPhase.ContentExpanded -> {
                phase.experiences.forEach { experience ->
                    WorkExperienceItemViewModel_()
                        .id("work-exp-${experience.id}")
                        .experience(experience)
                        .onWorkClick(onWorkItemClick)
                        .addTo(this)
                }
                ResumeFooterViewModel_()
                    .id("resume-footer")
                    .updatedAtText(phase.updatedAtText)
                    .addTo(this)
            }
        }
    }
}
