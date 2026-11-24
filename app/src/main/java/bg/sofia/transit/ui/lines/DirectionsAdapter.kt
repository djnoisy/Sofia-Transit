package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.databinding.ItemDirectionBinding
import bg.sofia.transit.databinding.ItemDirectionHeadingBinding
import bg.sofia.transit.util.FileLogger

/**
 * A line's directions: the two main ones, then — under "Други направления" —
 * the others running today (the owner, 24 Nov 2026: no bus leaving a stop is
 * to be lost from the Lines screen). [onClick] gets the direction and whether
 * it is one of the others.
 */
class DirectionsAdapter(
    private val onClick: (Trip, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TAG = "DirectionsAdapter"
        private const val DIRECTION = 0
        private const val HEADING = 1
        private const val OTHERS_HEADING = "Други направления"
    }

    /** A direction, and whether it is one of the others; null for the heading over them. */
    private val items = mutableListOf<Pair<Trip, Boolean>?>()

    fun submitList(main: List<Trip>, others: List<Trip>) {
        FileLogger.i(TAG, "submitList ${main.size} + ${others.size} other (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(main.map { it to false })
        if (others.isNotEmpty()) {
            items.add(null)
            items.addAll(others.map { it to true })
        }
        if (items.isNotEmpty()) notifyItemRangeInserted(0, items.size)
    }

    inner class VH(val b: ItemDirectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(trip: Trip, other: Boolean) {
            try {
                val headsign = trip.tripHeadsign?.ifBlank { "—" } ?: "—"
                b.tvHeadsign.text = headsign
                b.root.contentDescription =
                    "Направление към $headsign. Натиснете за списък със спирки."
                b.root.setOnClickListener { onClick(trip, other) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${trip.tripId}", e)
            }
        }
    }

    class HeadingVH(val b: ItemDirectionHeadingBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int = if (items[position] == null) HEADING else DIRECTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADING) HeadingVH(ItemDirectionHeadingBinding.inflate(inflater, parent, false))
        else VH(ItemDirectionBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val item = items[pos]
        when {
            holder is VH && item != null -> holder.bind(item.first, item.second)
            holder is HeadingVH -> holder.b.tvHeading.text = OTHERS_HEADING
        }
    }

    override fun getItemCount(): Int = items.size
}
