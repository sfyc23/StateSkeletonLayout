package com.sfyc.demo.network.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewSectionHeaderBinding
import com.sfyc.demo.network.FeedItem

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class SectionHeaderView(context: Context) : FrameLayout(context) {
    private val binding = ViewSectionHeaderBinding.inflate(LayoutInflater.from(context), this, true)
    private var itemId: String? = null

    @ModelProp
    fun setItem(item: FeedItem.Section) {
        itemId = item.stableId
        binding.sectionTitle.setText(if (item.group == 1) R.string.network_section_featured else R.string.network_section_records)
    }

    @CallbackProp
    fun setOnItemClick(callback: ((String) -> Unit)?) {
        binding.root.setOnClickListener(callback?.let { action -> View.OnClickListener { itemId?.let(action) } })
    }
}
