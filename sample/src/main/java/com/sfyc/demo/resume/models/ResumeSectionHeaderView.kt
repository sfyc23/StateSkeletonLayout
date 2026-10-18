package com.sfyc.demo.resume.models

import android.content.Context
import android.widget.FrameLayout
import android.view.LayoutInflater
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.databinding.ViewResumeSectionHeaderBinding

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class ResumeSectionHeaderView(context: Context) : FrameLayout(context) {
    private val binding = ViewResumeSectionHeaderBinding.inflate(LayoutInflater.from(context), this, true)

    /**
     * 直接收已格式化的副标题文案（String 天然 hashable）。
     * 不直接收 WorkSectionPhase：Epoxy 要求 ModelProp 声明类型实现 hashCode，
     * sealed interface 过不了 HashCodeValidator（见 epoxy-processor HashCodeValidator.kt，
     * 仅 data class / enum / value class / CharSequence 等放行）。
     * phase→文案的映射由 Controller 构造时传入的 sectionStatusText 完成。
     */
    @ModelProp
    fun setStatusText(text: String) {
        binding.resumeSectionStatus.text = text
    }
}
