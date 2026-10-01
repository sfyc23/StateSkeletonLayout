package com.sfyc.demo.network.models

import android.os.Bundle
import android.os.Parcelable

/** 仅保存布局值，不持有 View；由当前 Fragment 拥有并随配置变化保存。 */
class CarouselScrollStore {
    private data class Snapshot(val children: List<String>, val layout: Parcelable?)
    private val snapshots = mutableMapOf<String, Snapshot>()
    var datasetId: Long = 0
        private set

    fun startDataset(id: Long) {
        if (id != datasetId) {
            clear()
            datasetId = id
        }
    }

    fun save(id: String, children: List<String>, layout: Parcelable?) {
        snapshots[id] = Snapshot(children.toList(), layout)
    }

    fun restore(id: String, children: List<String>): Parcelable? =
        snapshots[id]?.takeIf { it.children == children }?.layout

    fun clear() = snapshots.clear()

    fun toBundle() = Bundle().apply {
        putLong("_dataset", datasetId)
        snapshots.forEach { (id, snapshot) -> putBundle(id, Bundle().apply {
            putStringArrayList("children", ArrayList(snapshot.children))
            putParcelable("layout", snapshot.layout)
        }) }
    }

    @Suppress("DEPRECATION")
    fun readBundle(bundle: Bundle?) {
        datasetId = bundle?.getLong("_dataset") ?: 0L
        bundle?.keySet()?.filter { it != "_dataset" }?.forEach { id ->
            bundle.getBundle(id)?.let {
                snapshots[id] = Snapshot(it.getStringArrayList("children").orEmpty(), it.getParcelable("layout"))
            }
        }
    }
}
