package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.databinding.ItemDirectionBinding

class DirectionsAdapter(
    private val onClick: (Trip) -> Unit
) : ListAdapter<Trip, DirectionsAdapter.VH>(DIFF) {

    inner class VH(val b: ItemDirectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(trip: Trip) {
            val label = trip.tripHeadsign ?: "Направление ${(trip.directionId ?: 0) + 1}"
            b.tvHeadsign.text = label
            b.tvDirectionIcon.text = if ((trip.directionId ?: 0) == 0) "→" else "←"
            b.root.contentDescription = "Направление: $label. Натиснете за спирките."
            b.root.setOnClickListener { onClick(trip) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(ItemDirectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Trip>() {
            override fun areItemsTheSame(a: Trip, b: Trip) = a.tripId == b.tripId
            override fun areContentsTheSame(a: Trip, b: Trip) = a == b
        }
    }
}
