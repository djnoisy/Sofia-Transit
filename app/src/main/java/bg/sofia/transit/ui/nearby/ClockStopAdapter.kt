package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.databinding.ItemClockStopBinding
import bg.sofia.transit.util.LocationHelper.ClockStop
import kotlin.math.roundToInt

class ClockStopAdapter(
    private val onClick: (Stop) -> Unit
) : ListAdapter<ClockStop, ClockStopAdapter.VH>(DIFF) {

    inner class VH(val b: ItemClockStopBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(cs: ClockStop) {
            val distStr = if (cs.distanceMetres < 1000)
                "${cs.distanceMetres.roundToInt()} м"
            else
                "${"%.1f".format(cs.distanceMetres / 1000)} км"

            b.tvClock.text    = cs.clockLabel
            b.tvStopName.text = cs.stop.stopName
            b.tvDistance.text = distStr

            // Comprehensive content description for TalkBack
            val desc = "${cs.clockLabel}: ${cs.stop.stopName}, $distStr. " +
                       "Натиснете за разписание."
            b.root.contentDescription = desc

            b.root.setOnClickListener { onClick(cs.stop) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemClockStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(getItem(pos))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ClockStop>() {
            override fun areItemsTheSame(a: ClockStop, b: ClockStop) =
                a.stop.stopId == b.stop.stopId && a.sector == b.sector
            override fun areContentsTheSame(a: ClockStop, b: ClockStop) =
                a.distanceMetres == b.distanceMetres && a.clockLabel == b.clockLabel
        }
    }
}
