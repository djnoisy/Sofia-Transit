package bg.sofia.transit.ui.journey

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.data.repository.UpcomingTripInfo
import bg.sofia.transit.databinding.ItemUpcomingTripBinding

/**
 * Renders one row per upcoming arrival in the Journey screen.
 * Each row shows: type icon, line number, real headsign, ETA in minutes.
 * The row's contentDescription is a complete TalkBack-friendly sentence.
 */
class UpcomingTripsAdapter(
    private val onClick: (UpcomingTripInfo) -> Unit
) : ListAdapter<UpcomingTripInfo, UpcomingTripsAdapter.VH>(DIFF) {

    inner class VH(val b: ItemUpcomingTripBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(t: UpcomingTripInfo) {
            val type = transportTypeFor(t.routeType)
            b.tvType.text       = type.emoji
            b.tvRoute.text      = t.routeShortName
            b.tvHeadsign.text   = "→ ${t.headsign}"

            val mins = t.minutesUntilArrival()
            b.tvEta.text = when {
                mins <= 0  -> "сега"
                mins == 1  -> "1 мин"
                else       -> "$mins мин"
            }

            // Stop name + distance (helps the user know which stop to walk to)
            val distStr = if (t.stopDistanceMetres < 1000)
                "${t.stopDistanceMetres.toInt()} м"
            else
                "${"%.1f".format(t.stopDistanceMetres / 1000)} км"
            b.tvStopInfo.text = "${t.stopName} • $distStr"

            // Comprehensive content description for TalkBack
            val typeLabel = type.labelBg.dropLast(1)   // "Автобус" not "Автобуси"
            val etaText = when {
                mins <= 0 -> "пристига сега"
                mins == 1 -> "след 1 минута"
                else      -> "след $mins минути"
            }
            b.root.contentDescription =
                "$typeLabel ${t.routeShortName} към ${t.headsign}, $etaText, " +
                "от спирка ${t.stopName} на $distStr. " +
                "Натиснете за стартиране на пътуването."

            b.root.setOnClickListener { onClick(t) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(ItemUpcomingTripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    private fun transportTypeFor(routeType: Int): TransportType = when (routeType) {
        0    -> TransportType.TRAM
        1    -> TransportType.METRO
        11   -> TransportType.TROLLEYBUS
        else -> TransportType.BUS
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UpcomingTripInfo>() {
            override fun areItemsTheSame(a: UpcomingTripInfo, b: UpcomingTripInfo) =
                a.tripId == b.tripId
            override fun areContentsTheSame(a: UpcomingTripInfo, b: UpcomingTripInfo) =
                a == b
        }
    }
}
