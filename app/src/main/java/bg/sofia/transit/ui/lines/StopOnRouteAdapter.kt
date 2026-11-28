package bg.sofia.transit.ui.lines

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.ItemStopOnRouteBinding
import bg.sofia.transit.util.FileLogger

/**
 * A route's stops (RouteStopsFragment): number, name, and the travel time
 * from one of them — the stop counted from ([start]; the owner, 27 Nov
 * 2026): its number filled, the stops after it with their minutes from it,
 * none before it, nor at it. Always one: the first stop, or the board's, on
 * opening. Another chosen by touching its number, or, for TalkBack, by the
 * card's action "Време на пътуване от тази спирка" — the card one element,
 * not two a stop (the owner chose so, to try). The rest of the card opens
 * the stop ([onOpen]).
 */
class StopOnRouteAdapter(
    private val onOpen: (StopWithSequence) -> Unit,
    private val onStart: (Int) -> Unit
) : RecyclerView.Adapter<StopOnRouteAdapter.VH>() {

    companion object {
        private const val TAG = "StopOnRouteAdapter"
        const val ACTION_START = "Време на пътуване от тази спирка"
    }

    private val items = mutableListOf<StopWithSequence>()

    /** The position of the stop counted from. */
    private var start = 0

    /** A route's stops, counted from the stop at [startAt]. */
    fun submitList(newItems: List<StopWithSequence>, startAt: Int) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size}), from $startAt")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        start = startAt.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    /**
     * Counted from the stop at [startAt] now: the cards changed in place —
     * the same views, TalkBack's focus kept (change animations off in the
     * list).
     */
    fun setStart(startAt: Int) {
        if (startAt == start || startAt !in items.indices) return
        start = startAt
        notifyItemRangeChanged(0, items.size)
    }

    inner class VH(val b: ItemStopOnRouteBinding) : RecyclerView.ViewHolder(b.root) {
        /** The id of the card's action for TalkBack, to remove before another bind. */
        private var actionId = View.NO_ID

        fun bind(stop: StopWithSequence, pos: Int) {
            try {
                b.tvSequence.text = "${stop.stopSequence}."
                b.tvStopName.text = stop.stopName
                val isStart = pos == start
                b.tvSequence.isSelected = isStart

                // Minutes of travel from the stop counted from: after it
                // only; none at it and before it.
                val mins = if (pos > start)
                    ((stop.minutesFromStart ?: 0) - (items[start].minutesFromStart ?: 0)).coerceAtLeast(0) else null
                b.tvTime.text = mins?.let { "$it мин" } ?: ""
                b.tvTime.visibility = if (mins == null) View.GONE else View.VISIBLE
                b.root.contentDescription = "Спирка ${stop.stopSequence}: ${stop.stopName}" +
                    (if (isStart) ", избрана" else "") + (mins?.let { if (it == 1) ", 1 минута" else ", $it минути" } ?: "")
                b.root.setOnClickListener { onOpen(stop) }
                b.tvSequence.setOnClickListener { onStart(bindingAdapterPosition) }

                // For TalkBack: the same choice, an action of the card — not on
                // the stop counted from already.
                if (actionId != View.NO_ID) ViewCompat.removeAccessibilityAction(b.root, actionId)
                actionId = if (isStart) View.NO_ID else ViewCompat.addAccessibilityAction(b.root, ACTION_START) { _, _ ->
                    onStart(bindingAdapterPosition)
                    true
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${stop.stopId}", e)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStopOnRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos], pos)

    override fun getItemCount(): Int = items.size
}
