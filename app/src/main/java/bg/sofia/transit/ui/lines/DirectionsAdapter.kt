package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.databinding.ItemDirectionBinding
import bg.sofia.transit.util.FileLogger

class DirectionsAdapter(
    private val onClick: (Trip) -> Unit
) : RecyclerView.Adapter<DirectionsAdapter.VH>() {

    companion object { private const val TAG = "DirectionsAdapter" }

    private val items = mutableListOf<Trip>()

    fun submitList(newItems: List<Trip>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemDirectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(trip: Trip) {
            try {
                val headsign = trip.tripHeadsign?.ifBlank { "—" } ?: "—"
                b.tvDirection.text = headsign
                b.root.contentDescription =
                    "Направление към $headsign. Натиснете за списък със спирки."
                b.root.setOnClickListener { onClick(trip) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${trip.tripId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDirectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
