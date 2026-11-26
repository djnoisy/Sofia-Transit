package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.databinding.ItemNearbyStopBinding

/**
 * The stops found (Спирки → "Търсене"), as the stops near us are shown
 * (NearbyStopAdapter): name and code, and how far, when where we are is
 * known — "350 м", "1,2 км"; read as one by TalkBack ("…, код 0169, на 1,2
 * километра").
 */
class SearchResultAdapter(
    private val onClick: (Stop) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<Stop>()
    private var distanceOf: (Stop) -> Double? = { null }

    /**
     * The stops found, and their distance from us (null when not known). The
     * same stops again — a new distance — changed in place: the cards keep
     * their views, and TalkBack its focus (as the arrivals board).
     */
    fun submitList(newItems: List<Stop>, distance: (Stop) -> Double?) {
        distanceOf = distance
        if (newItems.map { it.stopId } == items.map { it.stopId }) {
            // Their data anew too: a data update may have renamed or moved one.
            items.clear()
            items.addAll(newItems)
            if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
            return
        }
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemNearbyStopBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(stop: Stop) {
            val code = stop.stopCode?.takeIf { it.isNotBlank() }
            b.tvStopName.text = stop.stopName + (code?.let { " ($it)" } ?: "")
            val m = distanceOf(stop)
            b.tvDistance.visibility = if (m == null) View.GONE else View.VISIBLE
            b.tvDistance.text = m?.let { shown(it) } ?: ""
            b.root.contentDescription = stop.stopName + (code?.let { ", код $it" } ?: "") + (m?.let { ", на ${said(it)}" } ?: "")
            b.root.setOnClickListener { onClick(stop) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNearbyStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size

    private companion object {
        /** "350 м", "1,2 км", "12 км". */
        fun shown(m: Double): String = when {
            m < 1000 -> "${m.toInt()} м"
            m < 10_000 -> "%.1f км".format(java.util.Locale("bg"), m / 1000)
            else -> "${(m / 1000).toInt()} км"
        }

        /** "350 метра", "1,2 километра", "12 километра". */
        fun said(m: Double): String = when {
            m < 1000 -> "${m.toInt()} метра"
            m < 10_000 -> "%.1f километра".format(java.util.Locale("bg"), m / 1000)
            else -> "${(m / 1000).toInt()} километра"
        }
    }
}
