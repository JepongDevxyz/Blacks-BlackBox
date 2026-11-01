package com.onebitmonochrome.blacksbbox.view.clones

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.data.CloneRecord

/** RecyclerView adapter for the unified clone list. */
class ClonesAdapter(
    private val onRowClick: (CloneRecord) -> Unit,
    private val onRowMenu: (View, CloneRecord) -> Unit
) : RecyclerView.Adapter<ClonesAdapter.CloneVH>() {

    private var rows: List<CloneRecord> = emptyList()

    fun submit(records: List<CloneRecord>) {
        rows = records
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloneVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clone, parent, false)
        return CloneVH(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: CloneVH, position: Int) {
        val record = rows[position]
        holder.name.text = record.customName ?: (record.packageName + " " + record.number)
        holder.pkg.text = record.packageName
        holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        holder.itemView.setOnClickListener { onRowClick(record) }
        holder.itemView.setOnLongClickListener {
            onRowMenu(holder.itemView, record)
            true
        }
        holder.overflow.setOnClickListener { onRowMenu(it, record) }
    }

    class CloneVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.cloneIcon)
        val name: TextView = itemView.findViewById(R.id.cloneName)
        val pkg: TextView = itemView.findViewById(R.id.clonePkg)
        val overflow: ImageButton = itemView.findViewById(R.id.cloneOverflow)
    }
}
