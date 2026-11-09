package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemFavoriteStopBinding
import bg.sofia.transit.util.FavoriteStop

/** The favourite stops (Спирки → "Любими"); a tap opens the stop's virtual board. */
class FavoriteStopAdapter(
    private val onClick: (FavoriteStop) -> Unit
) : ListAdapter<FavoriteStop, FavoriteStopAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemFavoriteStopBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: FavoriteStop) {
            val code = s.stopCode?.takeIf { it.isNotBlank() }
            b.tvStopName.text = if (code != null) "${s.stopName} ($code)" else s.stopName
            // As the stops near us read: the name, then the code.
            b.root.contentDescription = if (code != null) "${s.stopName}, код $code" else s.stopName
            b.root.setOnClickListener { onClick(s) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavoriteStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<FavoriteStop>() {
            override fun areItemsTheSame(a: FavoriteStop, b: FavoriteStop) = a.stopId == b.stopId
            override fun areContentsTheSame(a: FavoriteStop, b: FavoriteStop) = a == b
        }
    }
}
