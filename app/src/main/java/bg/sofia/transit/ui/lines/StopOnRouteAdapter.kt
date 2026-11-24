package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.ItemStopOnRouteBinding
import bg.sofia.transit.util.FileLogger

/**
 * A route's stops: number, name, minutes from the start. [here]: the stop a
 * board's route was opened from (RouteStopsFragment), marked "тази спирка".
 */
class StopOnRouteAdapter(
    private val here: String? = null,
    private val onClick: (StopWithSequence) -> Unit
) : RecyclerView.Adapter<StopOnRouteAdapter.VH>() {

    companion object { private const val TAG = "StopOnRouteAdapter" }

    private val items = mutableListOf<StopWithSequence>()

    fun submitList(newItems: List<StopWithSequence>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemStopOnRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(stop: StopWithSequence) {
            try {
                b.tvSequence.text = "${stop.stopSequence}."
                b.tvStopName.text = stop.stopName
                val isHere = here != null && stop.stopId == here
                b.tvHere.visibility = if (isHere) android.view.View.VISIBLE else android.view.View.GONE

                // Show cumulative minutes of travel from the first stop.
                val mins = stop.minutesFromStart ?: 0
                b.tvTime.text = "$mins мин"
                b.root.contentDescription =
                    "Спирка ${stop.stopSequence}: ${stop.stopName}${if (isHere) ", тази спирка" else ""}, $mins минути"
                b.root.setOnClickListener { onClick(stop) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${stop.stopId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStopOnRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
