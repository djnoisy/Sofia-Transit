package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.databinding.ItemRouteBinding
import bg.sofia.transit.util.FileLogger

class RoutesAdapter(
    private val onClick: (Route) -> Unit
) : RecyclerView.Adapter<RoutesAdapter.VH>() {

    companion object { private const val TAG = "RoutesAdapter" }

    private val items = mutableListOf<Route>()

    fun submitList(newItems: List<Route>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(route: Route) {
            try {
                val type = route.getTransportType()
                b.tvRouteNumber.text = route.routeShortName
                b.tvRouteName.text   = route.routeLongName.ifBlank { type.labelBg }
                b.tvRouteType.text   = type.emoji
                b.root.contentDescription =
                    "${type.labelBg} линия ${route.routeShortName}: ${route.routeLongName}. " +
                    "Натиснете за направления."
                b.root.setOnClickListener { onClick(route) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${route.routeId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
