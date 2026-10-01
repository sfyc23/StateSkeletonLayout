package com.sfyc.demo.network.models

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ViewBannerBinding
import com.sfyc.demo.network.FeedItem

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class BannerView(context: Context) : FrameLayout(context) {
    private val binding = ViewBannerBinding.inflate(LayoutInflater.from(context), this, true)
    private var itemId: String? = null

    @ModelProp
    fun setItem(item: FeedItem.Banner) {
        itemId = item.stableId
        binding.bannerTitle.text = context.getString(R.string.network_banner_title, item.revision)
    }

    @CallbackProp
    fun setOnItemClick(callback: ((String) -> Unit)?) {
        binding.root.setOnClickListener(callback?.let { action ->
            android.view.View.OnClickListener { itemId?.let(action) }
        })
    }
}
