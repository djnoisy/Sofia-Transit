package bg.sofia.transit.ui.nearby

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.databinding.ItemArrivalBinding
import bg.sofia.transit.ui.common.LineBadge
import bg.sofia.transit.util.ArrivalTimes
import bg.sofia.transit.util.FileLogger

class ArrivalAdapter : RecyclerView.Adapter<ArrivalAdapter.VH>() {

    companion object { private const val TAG = "ArrivalAdapter" }

    private val items = mutableListOf<ArrivalInfo>()

    /** The lines' official colours, by route id, for the badges (GtfsRepository.getRouteColors). */
    private var routeColors: Map<String, String> = emptyMap()

    fun setRouteColors(colors: Map<String, String>) {
        if (colors == routeColors) return
        routeColors = colors
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    /**
     * Updates the list using DiffUtil so RecyclerView receives precise
     * move/change/insert/remove operations instead of "everything was
     * replaced". This matters for accessibility: on the periodic refresh,
     * a vehicle that just moved up the time-sorted list is reported to
     * TalkBack as the SAME item moved (notifyItemMoved), so TalkBack keeps
     * focus on it and the scroll position is preserved — rather than the
     * whole list being torn down and the focus jumping back to the top.
     */
    fun submitList(newItems: List<ArrivalInfo>) {
        FileLogger.i(TAG, "submitList called with ${newItems.size} items (current=${items.size})")
        val diff = DiffUtil.calculateDiff(Callback(items.toList(), newItems))
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
        FileLogger.i(TAG, "submitList done. items.size=${items.size}, getItemCount()=$itemCount")
    }

    /**
     * DiffUtil callback. Identity is routeId + headsign — this is stable as
     * a vehicle's ETA counts down, so the same line+direction is recognised
     * across refreshes even when it changes position in the sorted list.
     * Contents compare the displayed times, type and drop-off flag.
     */
    private class Callback(
        private val old: List<ArrivalInfo>,
        private val new: List<ArrivalInfo>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = old[oldPos]; val b = new[newPos]
            return a.routeId == b.routeId && a.headsign == b.headsign
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = old[oldPos]; val b = new[newPos]
            return a.arrivals == b.arrivals &&
                   a.vehicleType == b.vehicleType &&
                   a.dropOffOnly == b.dropOffOnly
        }
    }

    inner class VH(val b: ItemArrivalBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(info: ArrivalInfo) {
            try {
                // Visible label: prefer the vehicle type when known, e.g.
                // "Автобус 84" / "Тролей 2" / "Електробус 73". Fall back to
                // plain "Линия N" when the type is uncertain (temporary
                // lines with unknown vehicle kind).
                val label = if (info.vehicleType != null) {
                    "${info.vehicleType.replaceFirstChar { it.uppercase() }} ${info.routeShortName}"
                } else {
                    "Линия ${info.routeShortName}"
                }
                // Shown as the badge: the vehicle's icon and the number on
                // the line's official colour (redesign, stage b); the label
                // is still what TalkBack says.
                LineBadge.bind(b.badge, info.vehicleType, info.routeId, info.routeShortName,
                    routeColors[info.routeId])
                val headsignText = if (info.dropOffOnly) "само слизане" else info.headsign
                b.tvHeadsign.text = headsignText
                // The nearest time under the badge, the next two under the
                // direction, "мин" once after a run of minutes (the owner,
                // 9 Nov 2026; ArrivalTimes); without "след" on screen (8 Nov
                // 2026) — TalkBack still hears it (timesDesc below).
                // Timetable times are marked as such: they carry no traffic
                // information, so presenting them like live predictions would
                // invite more trust than they deserve.
                val shown = info.arrivals.take(3)
                b.tvFirstTime.text = ArrivalTimes.first(shown)
                val later = ArrivalTimes.later(shown)
                val laterText = if (info.scheduled && shown.isNotEmpty())
                    listOf(later, "(по разписание)").filter { it.isNotEmpty() }.joinToString(" ")
                else later
                b.tvLaterTimes.text = laterText
                b.tvLaterTimes.visibility = if (laterText.isEmpty()) android.view.View.GONE
                    else android.view.View.VISIBLE

                val timesDesc = when (info.arrivals.size) {
                    0    -> "без информация"
                    else -> {
                        val list = info.arrivals.take(3).joinToString(", ") { describeTime(it) }
                        if (info.scheduled) "$list, по разписание" else list
                    }
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
        return try {
            val binding = ItemArrivalBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            VH(binding)
        } catch (e: Exception) {
            FileLogger.e(TAG, "onCreateViewHolder FAILED", e)
            throw e
        }
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        try {
            holder.bind(items[pos])
        } catch (e: Exception) {
            FileLogger.e(TAG, "onBindViewHolder FAILED at pos=$pos", e)
        }
    }

    override fun getItemCount(): Int = items.size
}
