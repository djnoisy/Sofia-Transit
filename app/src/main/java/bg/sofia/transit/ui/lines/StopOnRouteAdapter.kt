package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.ItemStopOnRouteBinding
import bg.sofia.transit.util.FileLogger

/**
 * A route's stops: number, name, minutes from the start. [here]: the stop a
 * board's route was opened from (RouteStopsFragment), marked "тази спирка";
 * the minutes then from it on, none before it (the owner, 25 Nov 2026).
 */
class StopOnRouteAdapter(
    private val here: String? = null,
    private val onClick: (StopWithSequence) -> Unit
) : RecyclerView.Adapter<StopOnRouteAdapter.VH>() {

    companion object { private const val TAG = "StopOnRouteAdapter" }

    private val items = mutableListOf<StopWithSequence>()

    /** The position of [here] in the list (its first, on a loop); -1 when none. */
    private var hereAt = -1

    fun submitList(newItems: List<StopWithSequence>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        hereAt = here?.let { h -> items.indexOfFirst { it.stopId == h } } ?: -1
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemStopOnRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(stop: StopWithSequence, pos: Int) {
            try {
                b.tvSequence.text = "${stop.stopSequence}."
                b.tvStopName.text = stop.stopName
                val isHere = hereAt >= 0 && pos == hereAt
                b.tvHere.visibility = if (isHere) android.view.View.VISIBLE else android.view.View.GONE

                // Minutes of travel from the first stop — or, on a board's
                // route, from its stop on, none at it and before it.
                val mins = when {
                    hereAt < 0 -> stop.minutesFromStart ?: 0
                    pos > hereAt -> ((stop.minutesFromStart ?: 0) - (items[hereAt].minutesFromStart ?: 0)).coerceAtLeast(0)
                    else -> null
                }
                b.tvTime.text = mins?.let { "$it мин" } ?: ""
                b.tvTime.visibility = if (mins == null) android.view.View.GONE else android.view.View.VISIBLE
                b.root.contentDescription = "Спирка ${stop.stopSequence}: ${stop.stopName}" +
                    (if (isHere) ", тази спирка" else "") + (mins?.let { ", $it минути" } ?: "")
                b.root.setOnClickListener { onClick(stop) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${stop.stopId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStopOnRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos], pos)

    override fun getItemCount(): Int = items.size
}
