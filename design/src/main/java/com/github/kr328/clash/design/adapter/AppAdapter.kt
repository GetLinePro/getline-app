package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterAppBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppAdapter(
    private val context: Context,
    private val selected: MutableSet<String>,
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    class Holder(val binding: AdapterAppBinding) : RecyclerView.ViewHolder(binding.root)

    var apps: List<AppInfo> = emptyList()

    /**
     * Whether picking apps does anything. False in
     * [com.github.kr328.clash.service.model.AccessControlMode.AcceptAll], where the
     * stored list is not applied to the tunnel at all: a tap there would write a
     * selection with no effect, which is what the control appears to promise.
     *
     * The stored selection itself is untouched — switching to a selective mode
     * brings back exactly what was ticked before.
     */
    var selectable: Boolean = true

    fun rebindAll() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]

        holder.binding.app = current
        holder.binding.selected = current.packageName in selected
        holder.binding.selectable = selectable
        holder.binding.root.setOnClickListener {
            if (!selectable) return@setOnClickListener

            if (holder.binding.selected) {
                selected.remove(current.packageName)
                holder.binding.selected = false
            } else {
                selected.add(current.packageName)
                holder.binding.selected = true
            }
        }
    }

    override fun getItemCount(): Int {
        return apps.size
    }
}