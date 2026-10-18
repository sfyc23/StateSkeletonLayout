package com.sfyc.demo.resume.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.resume.WorkExperience
import com.sfyc.demo.ssl.databinding.ViewWorkExperienceItemBinding

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class WorkExperienceItemView(context: Context) : FrameLayout(context) {
    private val binding = ViewWorkExperienceItemBinding.inflate(LayoutInflater.from(context), this, true)
    private var workId: String? = null

    @ModelProp
    fun setExperience(experience: WorkExperience) {
        workId = experience.id
        binding.resumeWorkCompany.text = experience.company
        binding.resumeWorkPeriod.text = experience.period
        binding.resumeWorkTitle.text = experience.title
        binding.resumeWorkSummary.text = experience.summary
        binding.resumeWorkStack.text = experience.techStack
    }

    @CallbackProp
    fun setOnWorkClick(callback: ((String) -> Unit)?) {
        binding.root.setOnClickListener(callback?.let { action -> View.OnClickListener { workId?.let(action) } })
    }
}
