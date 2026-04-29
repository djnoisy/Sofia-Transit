package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.databinding.ItemArrivalBinding

class ArrivalAdapter : ListAdapter<ArrivalInfo, ArrivalAdapter.VH>(DIFF) {

    inner class VH(val b: ItemArrivalBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(info: ArrivalInfo) {
            b.tvRoute.text    = "Линия ${info.routeShortName}"
            b.tvHeadsign.text = info.headsign
            b.tvTimes.text    = info.arrivals.joinToString("  •  ")

            val timesDesc = when (info.arrivals.size) {
                0 -> "без информация"
                1 -> "в ${info.arrivals[0]}"
                else -> info.arrivals.take(3).joinToString(", ") { "в $it" }
            }
            b.root.contentDescription =
                "Линия ${info.routeShortName} към ${info.headsign}: $timesDesc"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArrivalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(getItem(pos))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ArrivalInfo>() {
            override fun areItemsTheSame(a: ArrivalInfo, b: ArrivalInfo) =
                a.routeId == b.routeId && a.headsign == b.headsign
            override fun areContentsTheSame(a: ArrivalInfo, b: ArrivalInfo) =
                a.arrivals == b.arrivals
        }
    }
}
