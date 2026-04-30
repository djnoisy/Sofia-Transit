package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemNearbyStopBinding
import bg.sofia.transit.util.LocationHelper.StopWithDistance

/**
 * Simple list adapter for the "Stops" tab — shows nearest stops sorted by
 * distance with a one-tap to open arrivals.
 */
class NearbyStopAdapter(
    private val onClick: (StopWithDistance) -> Unit
) : RecyclerView.Adapter<NearbyStopAdapter.VH>() {

    private val items = mutableListOf<StopWithDistance>()

    fun submitList(newItems: List<StopWithDistance>) {
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemNearbyStopBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: StopWithDistance) {
            b.tvStopName.text = item.stop.stopName
            b.tvDistance.text = "${item.distanceMetres.toInt()} м"
            b.root.contentDescription =
                "${item.stop.stopName}, на ${item.distanceMetres.toInt()} метра"
            b.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNearbyStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
