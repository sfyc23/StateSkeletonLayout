package com.sfyc.demo.network.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewProfileCardBinding
import com.sfyc.demo.network.FeedItem

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class ProfileCardView(context: Context) : FrameLayout(context) {
    private val binding = ViewProfileCardBinding.inflate(LayoutInflater.from(context), this, true)
    private var itemId: String? = null

    @ModelProp
    fun setItem(item: FeedItem.Profile) {
        itemId = item.stableId
        binding.profileName.text = context.getString(R.string.network_profile_name, item.record.number)
        binding.profileSubtitle.text = context.getString(R.string.network_profile_subtitle, item.record.revision)
    }

    @CallbackProp
    fun setOnItemClick(callback: ((String) -> Unit)?) {
        binding.root.setOnClickListener(callback?.let { action -> View.OnClickListener { itemId?.let(action) } })
    }
}
