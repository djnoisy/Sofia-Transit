package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemScheduleRowBinding
import bg.sofia.transit.util.FileLogger

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {

    companion object { private const val TAG = "ScheduleAdapter" }

    data class Row(val hour: String, val minutes: List<String>)

    private val items = mutableListOf<Row>()

    fun submitList(newItems: List<Row>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemScheduleRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            try {
                b.tvHour.text    = "${row.hour}:"
                b.tvMinutes.text = row.minutes.joinToString("  ")
                val minutesSpoken = row.minutes.joinToString(", ")
                b.root.contentDescription =
                    "${row.hour.toIntOrNull() ?: row.hour} часа: $minutesSpoken минути"
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${row.hour}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScheduleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
