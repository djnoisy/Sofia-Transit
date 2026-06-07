package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.databinding.ItemArrivalBinding
import bg.sofia.transit.util.FileLogger

class ArrivalAdapter : RecyclerView.Adapter<ArrivalAdapter.VH>() {

    companion object { private const val TAG = "ArrivalAdapter" }

    private val items = mutableListOf<ArrivalInfo>()

    fun submitList(newItems: List<ArrivalInfo>) {
        FileLogger.i(TAG, "submitList called with ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
        FileLogger.i(TAG, "submitList done. items.size=${items.size}, getItemCount()=$itemCount")
    }

    inner class VH(val b: ItemArrivalBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(info: ArrivalInfo) {
            try {
                // Visible label: prefer the vehicle type when known, e.g.
                // "Автобус 84" / "Тролейбус 2" / "Електробус 73". Fall back
                // to plain "Линия N" when the type is uncertain.
                val label = if (info.vehicleType != null) {
                    "${info.vehicleType.replaceFirstChar { it.uppercase() }} ${info.routeShortName}"
                } else {
                    "Линия ${info.routeShortName}"
                }
                b.tvRoute.text    = label
                val headsignText = if (info.dropOffOnly) "само слизане" else info.headsign
                b.tvHeadsign.text = headsignText
                b.tvTimes.text    = info.arrivals.joinToString("  •  ")

                val timesDesc = when (info.arrivals.size) {
                    0    -> "без информация"
                    else -> info.arrivals.take(3).joinToString(", ") { describeTime(it) }
                }
                // TalkBack: "Автобус 84, само за слизане: …" or
                // "Автобус 84 към ЛЕТИЩЕ…: след 3 мин, …"
                b.root.contentDescription = if (info.dropOffOnly) {
                    "$label, само за слизане: $timesDesc"
                } else {
                    "$label към ${info.headsign}: $timesDesc"
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind() FAILED for $info", e)
            }
        }

        /**
         * Formats one arrival string for TalkBack. Relative times like
         * "след 5 мин" or "сега" are read as-is; absolute "HH:MM" gets
         * "в" prepended so it sounds natural ("в 22:30").
         */
        private fun describeTime(s: String): String = when {
            s == "сега" -> s
            s.startsWith("след ") -> s
            else -> "в $s"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        FileLogger.i(TAG, "onCreateViewHolder called, parent=${parent.javaClass.simpleName}")
        return try {
            val binding = ItemArrivalBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            FileLogger.i(TAG, "  ↳ inflated successfully, root=${binding.root.javaClass.simpleName}")
            VH(binding)
        } catch (e: Exception) {
            FileLogger.e(TAG, "onCreateViewHolder FAILED", e)
            throw e
        }
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        FileLogger.i(TAG, "onBindViewHolder pos=$pos, items.size=${items.size}")
        try {
            holder.bind(items[pos])
        } catch (e: Exception) {
            FileLogger.e(TAG, "onBindViewHolder FAILED at pos=$pos", e)
        }
    }

    override fun getItemCount(): Int {
        // Don't log here — called many times by RecyclerView internals
        return items.size
    }
}
