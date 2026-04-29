package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.databinding.ItemArrivalBinding

/**
 * Simple RecyclerView adapter for arrival info.
 *
 * Originally used ListAdapter with DiffUtil for incremental updates, but
 * that introduced a race condition where the AsyncListDiffer would silently
 * drop the first submitList() call if the RecyclerView had not been laid
 * out yet. Switched to a plain RecyclerView.Adapter with notifyDataSetChanged
 * for guaranteed visibility.
 */
class ArrivalAdapter : RecyclerView.Adapter<ArrivalAdapter.VH>() {

    private val items = mutableListOf<ArrivalInfo>()

    fun submitList(newItems: List<ArrivalInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemArrivalBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(info: ArrivalInfo) {
            b.tvRoute.text    = "Линия ${info.routeShortName}"
            b.tvHeadsign.text = info.headsign
            b.tvTimes.text    = info.arrivals.joinToString("  •  ")

            val timesDesc = when (info.arrivals.size) {
                0 -> "без информация"
                1 -> "в ${info.arrivals[0]}"
                else -> info.arrivals.take(3).joinToString(", ") { "в $it" }
            }
            b.root.contentDescription =
                "Линия ${info.routeShortName} към ${info.headsign}: $timesDesc"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArrivalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
