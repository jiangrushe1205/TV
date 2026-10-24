package cn.v65.live.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.v65.live.R
import cn.v65.live.data.BeijingTime
import cn.v65.live.data.Program

data class ProgramRow(val program: Program, val current: Boolean)

class ProgramAdapter : ListAdapter<ProgramRow, ProgramAdapter.Holder>(object : DiffUtil.ItemCallback<ProgramRow>() {
    override fun areItemsTheSame(oldItem: ProgramRow, newItem: ProgramRow) = oldItem.program.id == newItem.program.id && oldItem.program.startsAt == newItem.program.startsAt
    override fun areContentsTheSame(oldItem: ProgramRow, newItem: ProgramRow) = oldItem == newItem
}) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_program, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.itemView.apply {
            isSelected = row.current
            nextFocusLeftId = R.id.webButton
            findViewById<TextView>(R.id.programTime).text = BeijingTime.clock(row.program.startsAt) + if (row.current) "  正在播出" else ""
            findViewById<TextView>(R.id.programName).text = row.program.name
        }
    }
}
