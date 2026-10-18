package com.sfyc.demo.resume.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.resume.ResumeProfile
import com.sfyc.demo.ssl.databinding.ViewResumeHeaderBinding

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class ResumeHeaderView(context: Context) : FrameLayout(context) {
    private val binding = ViewResumeHeaderBinding.inflate(LayoutInflater.from(context), this, true)
    private var profileId: String? = null

    @ModelProp
    fun setProfile(profile: ResumeProfile) {
        profileId = profile.candidateId
        binding.resumeHeaderName.text = profile.name
        binding.resumeHeaderTitle.text = profile.roleTitle
        binding.resumeHeaderMeta.text = "${profile.location} · ${profile.statusBadge}"
        binding.resumeHeaderBio.text = profile.bio
    }

    @CallbackProp
    fun setOnHeaderClick(callback: ((String) -> Unit)?) {
        binding.root.setOnClickListener(callback?.let { action -> View.OnClickListener { profileId?.let(action) } })
    }
}
