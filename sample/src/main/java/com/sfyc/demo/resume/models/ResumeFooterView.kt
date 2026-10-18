package com.sfyc.demo.resume.models

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.databinding.ViewResumeFooterBinding

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class ResumeFooterView(context: Context) : FrameLayout(context) {
    private val binding = ViewResumeFooterBinding.inflate(LayoutInflater.from(context), this, true)

    @ModelProp
    fun setUpdatedAtText(text: String) {
        binding.resumeFooterText.text = text
    }
}
