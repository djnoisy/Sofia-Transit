package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.databinding.ItemSearchResultBinding
import bg.sofia.transit.util.FileLogger

class SearchResultAdapter(
    private val onClick: (Stop) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    companion object { private const val TAG = "SearchResultAdapter" }

    private val items = mutableListOf<Stop>()

    fun submitList(newItems: List<Stop>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(stop: Stop) {
            try {
                val codeText = stop.stopCode?.takeIf { it.isNotBlank() }
                    ?.let { "код $it" } ?: ""
                b.tvStopName.text = stop.stopName
                b.tvStopCode.text = codeText
                b.root.contentDescription =
                    "${stop.stopName}${if (codeText.isNotEmpty()) ", $codeText" else ""}. " +
                    "Натиснете за пристигания."
                b.root.setOnClickListener { onClick(stop) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${stop.stopId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
