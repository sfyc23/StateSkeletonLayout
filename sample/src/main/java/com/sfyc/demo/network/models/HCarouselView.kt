package com.sfyc.demo.network.models

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.AfterPropsSet
import com.airbnb.epoxy.CallbackProp
import com.airbnb.epoxy.ModelProp
import com.airbnb.epoxy.ModelView
import com.airbnb.epoxy.OnViewRecycled
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ItemHcarouselChildBinding
import com.sfyc.demo.ssl.databinding.ViewHcarouselBinding
import com.sfyc.demo.network.CarouselChild
import com.sfyc.demo.network.FeedItem

@ModelView(autoLayout = ModelView.Size.MATCH_WIDTH_WRAP_HEIGHT)
class HCarouselView(context: Context) : FrameLayout(context) {
    private val binding = ViewHcarouselBinding.inflate(LayoutInflater.from(context), this, true)
    private val manager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    private val childAdapter = ChildAdapter()
    private var item: FeedItem.Carousel? = null
    private var store: CarouselScrollStore? = null
    private var callback: ((String) -> Unit)? = null
    private var boundId: String? = null
    private var boundDataset = 0L
    private var boundChildren: List<String> = emptyList()

    init {
        binding.carouselList.layoutManager = manager
        binding.carouselList.adapter = childAdapter
        binding.carouselList.isNestedScrollingEnabled = false
        binding.carouselList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = saveScroll()
        })
    }

    @ModelProp
    fun setItem(item: FeedItem.Carousel) { this.item = item }

    @ModelProp(options = [ModelProp.Option.DoNotHash])
    fun setScrollStore(store: CarouselScrollStore) { this.store = store }

    @CallbackProp
    fun setOnItemClick(callback: ((String) -> Unit)?) { this.callback = callback }

    @AfterPropsSet
    fun bindChildren() {
        val current = checkNotNull(item)
        saveScroll()
        val children = current.record.children.toList()
        boundId = current.stableId
        boundDataset = store?.datasetId ?: 0L
        boundChildren = children.map { it.stableId }
        childAdapter.submit(children)
        store?.restore(current.stableId, boundChildren)?.let(manager::onRestoreInstanceState)
    }

    private fun saveScroll() {
        if (store?.datasetId != boundDataset) return
        boundId?.let { store?.save(it, boundChildren, manager.onSaveInstanceState()) }
    }

    @OnViewRecycled
    fun release() {
        saveScroll()
        callback = null
        item = null
        boundId = null
        boundChildren = emptyList()
        store = null
    }

    private inner class ChildAdapter : RecyclerView.Adapter<ChildHolder>() {
        private var children = emptyList<CarouselChild>()

        init { setHasStableIds(true) }

        fun submit(items: List<CarouselChild>) {
            if (children == items) return
            val previous = children
            val snapshot = items.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = snapshot.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].stableId == snapshot[newItemPosition].stableId
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == snapshot[newItemPosition]
            })
            children = snapshot
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemId(position: Int) = children[position].number.toLong()
        override fun getItemCount() = children.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChildHolder(
            ItemHcarouselChildBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun onBindViewHolder(holder: ChildHolder, position: Int) {
            val child = children[position]
            holder.binding.childTitle.text = context.getString(R.string.network_carousel_child, child.number)
            holder.itemView.contentDescription = context.getString(R.string.network_carousel_description, child.number)
            holder.itemView.setOnClickListener { callback?.invoke(child.stableId) }
        }

        override fun onViewRecycled(holder: ChildHolder) { holder.itemView.setOnClickListener(null) }
    }

    private class ChildHolder(val binding: ItemHcarouselChildBinding) : RecyclerView.ViewHolder(binding.root)
}
