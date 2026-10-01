package com.sfyc.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sfyc.demo.ssl.R

/**
 * 首页列表示例适配器：纯展示示例目录，与库的骨架能力无关。
 */
class MainDemoAdapter(
    private val items: List<DemoCatalog.Entry>,
    private val onClick: (DemoCatalog.Entry) -> Unit,
) : RecyclerView.Adapter<MainDemoAdapter.Holder>() {

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.demo_item_title)
        val desc: TextView = itemView.findViewById(R.id.demo_item_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_demo, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.desc.text = item.description
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
