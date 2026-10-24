package cn.v65.live.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.v65.live.R
import cn.v65.live.data.Channel

data class ChannelRow(val channel: Channel, val number: Int, val selected: Boolean, val favorite: Boolean)

class ChannelAdapter(private val rightFocusId: Int = R.id.fullscreenButton, private val choose: (Channel) -> Unit) : ListAdapter<ChannelRow, ChannelAdapter.Holder>(object : DiffUtil.ItemCallback<ChannelRow>() {
    override fun areItemsTheSame(oldItem: ChannelRow, newItem: ChannelRow) = oldItem.channel.id == newItem.channel.id
    override fun areContentsTheSame(oldItem: ChannelRow, newItem: ChannelRow) = oldItem == newItem
}) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.itemView.apply {
            isSelected = row.selected
            contentDescription = row.channel.name + if (row.favorite) "，已收藏" else ""
            nextFocusRightId = rightFocusId
            findViewById<TextView>(R.id.channelNumber).text = row.number.toString().padStart(2, '0')
            findViewById<TextView>(R.id.channelName).text = row.channel.name
            findViewById<TextView>(R.id.channelBadge).apply {
                text = listOfNotNull(row.channel.badge.takeIf { it.isNotBlank() }, "已收藏".takeIf { row.favorite }).joinToString(" · ")
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            setOnClickListener { choose(row.channel) }
        }
    }
}
