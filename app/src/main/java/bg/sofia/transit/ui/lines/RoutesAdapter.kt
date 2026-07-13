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
    private var trolleyRouteIds: Set<String> = emptySet()

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

    /**
     * Returns the adapter position of the route with the given id, or -1.
     * Used to restore scroll/accessibility focus to the last-opened line
     * when the user navigates back to the list.
     */
    fun positionOf(routeId: String): Int =
        items.indexOfFirst { it.routeId == routeId }

    /**
     * Sets which route_ids are real trolleys — for labelling rows within
     * CGM's mixed route_type=11 group. Determined in the repository by
     * checking whether all of the line's stops are trolley stops.
     */
    fun setTrolleyRouteIds(ids: Set<String>) {
        if (ids == trolleyRouteIds) return
        trolleyRouteIds = ids
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    inner class VH(val b: ItemRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(route: Route) {
            try {
                val type = route.getTransportType()
                val subtitle = subtitles[route.routeId]
                    ?: route.routeLongName.ifBlank { type.labelBg }
                // Vehicle label in singular for the row: "Автобус 84",
                // "Тролей 2", "Електробус 73", "Трамвай 5". Matches the
                // Stops screen. Within route_type=11 we split by number
                // range because CGM lumps trolleys and electric buses
                // together (see vehicleLabelFor).
                val label = vehicleLabelFor(route, type)
                b.tvRouteNumber.text = route.routeShortName
                b.tvRouteName.text   = subtitle
                b.tvRouteType.text   = type.emoji
                // Short and speakable, without the redundant "линия" word.
                b.root.contentDescription =
                    "$label ${route.routeShortName}: $subtitle. Натиснете за направления."
                b.root.setOnClickListener { onClick(route) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${route.routeId}", e)
            }
        }

        /**
         * Singular vehicle label for a row: "Автобус", "Тролей",
         * "Електробус", "Трамвай", "Метро". Within CGM's route_type=11
         * group, a line is a real trolley if all its stops are on the
         * trolley network — this is decided in the repository and passed
         * to us via [trolleyRouteIds]. Everything else in type=11 is an
         * electric bus. Keeps the row label consistent with the Stops
         * screen, which resolves the same way.
         */
        private fun vehicleLabelFor(route: Route, type: bg.sofia.transit.data.db.entity.TransportType): String {
            return when (route.routeType) {
                0 -> "Трамвай"
                1 -> "Метро"
                3 -> "Автобус"
                11 -> if (route.routeId in trolleyRouteIds) "Тролей" else "Електробус"
                else -> type.labelBg
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
