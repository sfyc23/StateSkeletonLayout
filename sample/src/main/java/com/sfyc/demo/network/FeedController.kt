package com.sfyc.demo.network

import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.TypedEpoxyController
import com.sfyc.demo.network.models.BannerViewModel_
import com.sfyc.demo.network.models.CarouselScrollStore
import com.sfyc.demo.network.models.GridTileViewModel_
import com.sfyc.demo.network.models.HCarouselViewModel_
import com.sfyc.demo.network.models.ProfileCardViewModel_
import com.sfyc.demo.network.models.SectionHeaderViewModel_
import com.sfyc.demo.network.models.TextImageViewModel_

class FeedController(
    private val onItemClick: (String) -> Unit,
    private val carouselScrollStore: CarouselScrollStore,
) : TypedEpoxyController<List<FeedItem>>() {
    // 检查已通知 Adapter 的真实模型，不能把旧 diff 的回调当作最新 setData 已完成。
    fun hasCommittedItems(items: List<FeedItem>): Boolean {
        val models = adapter.copyOfModels
        if (models.size != items.size) return false
        return models.zip(items).all { (model, item) ->
            when (model) {
                is BannerViewModel_ -> model.item() == item
                is SectionHeaderViewModel_ -> model.item() == item
                is ProfileCardViewModel_ -> model.item() == item
                is TextImageViewModel_ -> model.item() == item
                is GridTileViewModel_ -> model.item() == item
                is HCarouselViewModel_ -> model.item() == item
                else -> false
            }
        }
    }

    override fun buildModels(items: List<FeedItem>?) {
        items.orEmpty().forEach { item ->
            val model: EpoxyModel<*> = when (item) {
                is FeedItem.Banner -> BannerViewModel_().id(item.stableId).item(item).onItemClick(onItemClick)
                is FeedItem.Section -> SectionHeaderViewModel_().id(item.stableId).item(item).onItemClick(onItemClick)
                is FeedItem.Profile -> ProfileCardViewModel_().id(item.stableId).item(item).onItemClick(onItemClick)
                is FeedItem.Article -> TextImageViewModel_().id(item.stableId).item(item).onItemClick(onItemClick)
                is FeedItem.Tile -> GridTileViewModel_().id(item.stableId).item(item).onItemClick(onItemClick)
                is FeedItem.Carousel -> HCarouselViewModel_().id(item.stableId).item(item)
                    .scrollStore(carouselScrollStore).onItemClick(onItemClick)
            }
            model.spanSizeOverride { totalSpanCount, _, _ -> if (item is FeedItem.Tile) 1 else totalSpanCount }
            model.addTo(this)
        }
    }
}
