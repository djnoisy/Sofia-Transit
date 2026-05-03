package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.ItemStopOnRouteBinding

class StopOnRouteAdapter(
    private val onClick: (StopWithSequence) -> Unit
) : ListAdapter<StopWithSequence, StopOnRouteAdapter.VH>(DIFF) {

    inner class VH(val b: ItemStopOnRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(stop: StopWithSequence, position: Int) {
            b.tvSequence.text = "${stop.stopSequence}."
            b.tvStopName.text = stop.stopName
            b.tvTime.text     = stop.arrivalTime.take(5)   // HH:MM

            b.root.contentDescription =
                "Спирка ${stop.stopSequence}: ${stop.stopName}, " +
                "час ${stop.arrivalTime.take(5)}. Натиснете за разписание."
            b.root.setOnClickListener { onClick(stop) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(ItemStopOnRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos), pos)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<StopWithSequence>() {
            override fun areItemsTheSame(a: StopWithSequence, b: StopWithSequence) =
                a.stopId == b.stopId && a.stopSequence == b.stopSequence
            override fun areContentsTheSame(a: StopWithSequence, b: StopWithSequence) = a == b
        }
    }
}
