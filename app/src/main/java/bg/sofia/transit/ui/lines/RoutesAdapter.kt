package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.databinding.ItemRouteBinding

class RoutesAdapter(
    private val onClick: (Route) -> Unit
) : ListAdapter<Route, RoutesAdapter.VH>(DIFF) {

    inner class VH(val b: ItemRouteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(route: Route) {
            val type = route.getTransportType()
            b.tvRouteNumber.text = route.routeShortName
            b.tvRouteName.text   = route.routeLongName.ifBlank { type.labelBg }
            b.tvRouteType.text   = type.emoji

            b.root.contentDescription =
                "${type.labelBg} линия ${route.routeShortName}: ${route.routeLongName}. " +
                "Натиснете за направления."
            b.root.setOnClickListener { onClick(route) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(getItem(pos))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Route>() {
            override fun areItemsTheSame(a: Route, b: Route) = a.routeId == b.routeId
            override fun areContentsTheSame(a: Route, b: Route) = a == b
        }
    }
}
