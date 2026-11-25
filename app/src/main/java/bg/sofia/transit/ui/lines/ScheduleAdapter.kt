package bg.sofia.transit.ui.lines

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import bg.sofia.transit.databinding.ItemScheduleRowBinding
import bg.sofia.transit.util.FileLogger
import com.google.android.material.color.MaterialColors

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {

    companion object { private const val TAG = "ScheduleAdapter" }

    /**
     * A minute of the hour; for another direction's short run, the letter it
     * is marked with on screen ([mark], explained above the list) and where
     * it ends ([endsAt], said by TalkBack); [clock], the whole time, as said.
     */
    data class Minute(val minute: String, val mark: String? = null, val endsAt: String? = null, val clock: String = minute,
                      val seconds: Int = 0)

    data class Row(val hour: String, val minutes: List<Minute>)

    private val items = mutableListOf<Row>()


    fun submitList(newItems: List<Row>) {
        FileLogger.i(TAG, "submitList ${newItems.size} items (current=${items.size})")
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) notifyItemRangeInserted(0, newItems.size)
    }

    inner class VH(val b: ItemScheduleRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            try {
                b.tvHour.text    = "${row.hour}:"
                b.tvMinutes.text = shown(row.minutes)
                // Each time whole, the hour and the minutes (the owner, 25 Nov
                // 2026): "10:05, 10:30 до БУЛ. НИКОЛА ПЕТКОВ, 10:50".
                val said = row.minutes.joinToString(", ") { m ->
                    if (m.endsAt == null) m.clock else "${m.clock} до ${m.endsAt}" }
                b.root.contentDescription = said
            } catch (e: Exception) {
                FileLogger.e(TAG, "bind failed for ${row.hour}", e)
            }
        }
    }

    /** The minutes, two spaces apart; a short run's letter raised after its minute, in the accent colour. */
    private fun shown(minutes: List<Minute>): CharSequence {
        val out = SpannableStringBuilder()
        minutes.forEachIndexed { i, m ->
            if (i > 0) out.append("  ")
            out.append(m.minute)
            val mark = m.mark ?: return@forEachIndexed
            val start = out.length
            out.append(mark)
            out.setSpan(SuperscriptSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.75f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            markColor?.let { out.setSpan(ForegroundColorSpan(it), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return out
    }

    /** The theme's accent (colorPrimary), for the letters; set when the first row is made. */
    private var markColor: Int? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (markColor == null) markColor = MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary)
        return VH(ItemScheduleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos])

    override fun getItemCount(): Int = items.size
}
