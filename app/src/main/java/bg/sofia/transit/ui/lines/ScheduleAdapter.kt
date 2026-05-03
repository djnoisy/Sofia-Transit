package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemScheduleRowBinding

class ScheduleAdapter : ListAdapter<ScheduleAdapter.Row, ScheduleAdapter.VH>(DIFF) {

    data class Row(val hour: String, val minutes: List<String>)

    inner class VH(val b: ItemScheduleRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            b.tvHour.text    = "${row.hour}:"
            b.tvMinutes.text = row.minutes.joinToString("  ")

            // Friendly TalkBack readout: "07 часа: 05, 20, 35, 50 минути"
            val minutesSpoken = row.minutes.joinToString(", ")
            b.root.contentDescription =
                "${row.hour.toIntOrNull() ?: row.hour} часа: $minutesSpoken минути"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScheduleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.hour == b.hour
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
