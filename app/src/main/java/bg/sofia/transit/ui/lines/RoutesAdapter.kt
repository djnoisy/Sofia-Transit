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
    private var subtitles: Map<String, String> = emptyMap()

    fun submitList(newItems: List<Route>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    /**
     * Sets the routeId → subtitle map (top-2 headsigns, e.g.
     * "Ж.К. ОВЧА КУПЕЛ-2 - СТУДЕНТСКИ ГРАД"). Used instead of CGM's
     * unreliable route_long_name so the list matches the directions screen.
     */
    fun setSubtitles(map: Map<String, String>) {
        if (map == subtitles) return
        subtitles = map
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    inner class VH(val b: ItemRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(route: Route) {
            try {
                val type = route.getTransportType()
                val subtitle = subtitles[route.routeId]
                    ?: route.routeLongName.ifBlank { type.labelBg }
                b.tvRouteNumber.text = route.routeShortName
                b.tvRouteName.text   = subtitle
                b.tvRouteType.text   = type.emoji
                b.root.contentDescription =
                    "${type.labelBg} линия ${route.routeShortName}: $subtitle. " +
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
