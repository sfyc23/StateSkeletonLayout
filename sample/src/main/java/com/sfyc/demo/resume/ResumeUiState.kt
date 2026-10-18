package com.sfyc.demo.resume

/** 上半部分：个人介绍（FakeRepository 延迟 500ms 固定成功返回）。 */
data class ResumeProfile(
    val candidateId: String,
    val name: String,
    val roleTitle: String,
    val location: String,
    val statusBadge: String,
    val bio: String,
    val skills: List<String>,
    val highlights: List<ResumeHighlight>,
)

data class ResumeHighlight(
    val label: String,
    val value: String,
)

/** 下半部分：单段工作经历。 */
data class WorkExperience(
    val id: String,
    val company: String,
    val title: String,
    val period: String,
    val summary: String,
    val techStack: String,
)

enum class ResumeScenario {
    SUCCESS,
    EMPTY,
    ERROR_ONCE,
    ERROR_ALWAYS,
}

/** 上半区域阶段：仅 LOADING / 过渡 / CONTENT（恒成功，无空错页）。 */
sealed interface HeaderPhase {
    data object Loading : HeaderPhase

    /** 网络已回但库最短展示未满，仍挂 HeaderSectionState 容器等待 rendered==CONTENT。 */
    data class TransitioningToContent(val profile: ResumeProfile) : HeaderPhase

    data class Content(val profile: ResumeProfile) : HeaderPhase
}

/** 下半区域阶段：LOADING / 过渡 / 展开 / EMPTY / ERROR。 */
sealed interface WorkSectionPhase {
    data object Loading : WorkSectionPhase

    data class TransitioningToContent(
        val experiences: List<WorkExperience>,
        val updatedAtText: String,
    ) : WorkSectionPhase

    data class ContentExpanded(
        val experiences: List<WorkExperience>,
        val updatedAtText: String,
    ) : WorkSectionPhase

    data object Empty : WorkSectionPhase

    data class Error(val message: String) : WorkSectionPhase
}

data class ResumeUiState(
    val candidateId: String = CANDIDATE_LIN,
    val headerPhase: HeaderPhase = HeaderPhase.Loading,
    val workPhase: WorkSectionPhase = WorkSectionPhase.Loading,
    val scenario: ResumeScenario = ResumeScenario.SUCCESS,
    val workLatencyMillis: Long = 1500L,
    val datasetId: Long = 1L,
    val headerRequestId: Long = 0L,
    val workRequestId: Long = 0L,
    val workAttempt: Int = 0,
    val isWorkBusy: Boolean = false,
) {
    companion object {
        const val CANDIDATE_LIN = "wukong"
        const val CANDIDATE_SU = "bajie"
    }
}
