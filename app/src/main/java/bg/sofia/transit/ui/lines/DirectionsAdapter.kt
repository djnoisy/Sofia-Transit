package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.data.repository.OtherDirection
import bg.sofia.transit.databinding.ItemDirectionBinding
import bg.sofia.transit.databinding.ItemDirectionHeadingBinding
import bg.sofia.transit.util.FileLogger

/**
 * A line's directions: the two main ones, then — under "Други направления" —
 * the others running today and the further routes a direction needs, told
 * apart by where they start or a stop on the way (the owner, 24 Nov 2026: no
 * bus leaving a stop is to be lost from the Lines screen). [onClick] gets the direction, whether
 * it is one of the others, and what tells it apart.
 */
class DirectionsAdapter(
    private val onClick: (Trip, Boolean, String?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TAG = "DirectionsAdapter"
        private const val DIRECTION = 0
        private const val HEADING = 1
        private const val OTHERS_HEADING = "Други направления"
    }

    /**
     * A direction: its trip, whether it is under "Други направления", and
     * what tells one more route of a direction apart ("от ДЕПО ИСКЪР").
     */
    class Row(val trip: Trip, val other: Boolean, val via: String? = null)

    /** The rows; null for the heading over the others. */
    private val items = mutableListOf<Row?>()

    fun submitList(main: List<Trip>, others: List<OtherDirection>) {
        FileLogger.i(TAG, "submitList ${main.size} + ${others.size} other (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(main.map { Row(it, false) })
        if (others.isNotEmpty()) {
            items.add(null)
            items.addAll(others.map { Row(it.trip, true, it.via) })
        }
        if (items.isNotEmpty()) notifyItemRangeInserted(0, items.size)
    }

    inner class VH(val b: ItemDirectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            val trip = row.trip
            try {
                val headsign = trip.tripHeadsign?.ifBlank { "—" } ?: "—"
                // "ГАРА ПОДУЯНЕ (от ДЕПО ИСКЪР)"; TalkBack: "…, от ДЕПО ИСКЪР."
                b.tvHeadsign.text = row.via?.let { "$headsign ($it)" } ?: headsign
                b.root.contentDescription =
                    "Направление към $headsign${row.via?.let { ", $it" } ?: ""}. Натиснете за списък със спирки."
                b.root.setOnClickListener { onClick(trip, row.other, row.via) }
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
            holder is VH && item != null -> holder.bind(item)
            holder is HeadingVH -> holder.b.tvHeading.text = OTHERS_HEADING
        }
    }

    override fun getItemCount(): Int = items.size
}
