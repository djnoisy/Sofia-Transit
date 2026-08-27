package bg.sofia.transit.ui.journey

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemUpcomingTripBinding
import bg.sofia.transit.util.VehicleLabels

/**
 * The line picker for starting a journey.
 *
 * Shows only what the choice depends on: the vehicle type, the line number
 * and the direction. Arrival times, stop names and distances were dropped
 * deliberately — they belong to the Stops screen, and the concrete vehicle is
 * now matched by position at journey start, so the specific arrival a user
 * taps carries no meaning. Ordering still encodes proximity: nearest stop
 * first, then earliest arrival.
 */
class UpcomingTripsAdapter(
    private val onClick: (LineChoice) -> Unit
) : ListAdapter<LineChoice, UpcomingTripsAdapter.VH>(DIFF) {

    /**
     * route_ids on the real trolleybus network, so route_type 11 can be told
     * apart as "Тролей" or "Електробус" like the Lines and Stops screens.
     */
    private var trolleyRouteIds: Set<String> = emptySet()

    fun setTrolleyRouteIds(ids: Set<String>) {
        if (ids != trolleyRouteIds) {
            trolleyRouteIds = ids
            notifyDataSetChanged()
        }
    }

    inner class VH(private val b: ItemUpcomingTripBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(c: LineChoice) {
            val type = VehicleLabels.typeOf(c.routeType)
            val typeLabel = VehicleLabels.singular(
                c.routeType, c.routeId in trolleyRouteIds)

            b.tvType.text = type.emoji
            b.tvRoute.text = c.routeShortName

            // Rows carry no direction — it is chosen after tapping — so the
            // second line shows the line's corridor instead. That identifies
            // the route without implying a direction it does not have.
            b.tvHeadsign.text = c.headsign ?: c.routeSubtitle

            // The row is one focusable unit for a screen reader. "→" is shown
            // visually but spoken as "към", since the glyph itself would
            // either be skipped or read as a symbol.
            b.root.contentDescription = when {
                c.headsign != null ->
                    "$typeLabel ${c.routeShortName} към ${c.headsign}"
                c.routeSubtitle.isNotEmpty() ->
                    "$typeLabel ${c.routeShortName}, ${c.routeSubtitle}"
                else -> "$typeLabel ${c.routeShortName}"
            }

            b.root.setOnClickListener { onClick(c) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemUpcomingTripBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LineChoice>() {
            override fun areItemsTheSame(a: LineChoice, b: LineChoice) =
                a.routeId == b.routeId && a.headsign == b.headsign
            override fun areContentsTheSame(a: LineChoice, b: LineChoice) = a == b
        }
    }
}
