package bg.sofia.transit.ui.lines

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.data.repository.StopLine
import bg.sofia.transit.databinding.FragmentScheduleBinding
import bg.sofia.transit.util.DateHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "Разписания" tab of a stop's window (StopBoardActivity; the owner,
 * 26 Nov 2026): the timetable of a line at the stop, chosen from a drop-down
 * list of every line calling there (GtfsRepository.linesAtStop) — the one
 * asked for, from a stop of a direction in Линии, else the first — as Линии
 * showed it before: the direction's trips and, marked, the line's short runs
 * of other directions that leave the stop its way
 * (GtfsRepository.getScheduleForDirectionAtStop).
 *
 * The user browses by concrete date via a 7-day strip (today + 6 days; from
 * yesterday when it opens on yesterday), opened on the first day with a time
 * not yet passed, its hour at the top (ScheduleViewModel; the owner, 25 Nov
 * 2026) — each time the tab is shown again, and when a line is chosen. Not
 * TalkBack's focus moved there (the owner, 27 Nov 2026: it broke the reading
 * of the screen, the tab opened was not heard): the first row a swipe from
 * the date reaches is that hour.
 */
@AndroidEntryPoint
class ScheduleFragment : Fragment() {

    companion object {
        private const val ARG_STOP_ID = "stopId"
        private const val ARG_ROUTE_ID = "routeId"
        private const val ARG_HEADSIGN = "headsign"
        /** The letters short runs are marked with, by where they end. */
        private const val MARK_LETTERS = "абвгдежзиклмнопрстуф"

        /**
         * The timetables at the stop [stopId], of the line [routeId] towards
         * [headsign] there when given (the pole [stopId]), else the first.
         */
        fun newInstance(stopId: String, routeId: String?, headsign: String?) = ScheduleFragment().apply {
            arguments = bundleOf(ARG_STOP_ID to stopId, ARG_ROUTE_ID to routeId, ARG_HEADSIGN to headsign)
        }
    }

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val vm: ScheduleViewModel by viewModels()

    private lateinit var adapter: ScheduleAdapter

    /** Chip views by date string, so we can toggle their selected state. */
    private val chipViews = linkedMapOf<String, View>()

    /** The lines the drop-down list was built of. */
    private var listed: List<StopLine>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        // A new view: opened afresh when shown (onResume), brought to its hour again.
        openedHere = false
        scrolled = false
        listed = null

        adapter = ScheduleAdapter()
        binding.rvSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedule.adapter = adapter

        val args = requireArguments()
        vm.loadLines(args.getString(ARG_STOP_ID).orEmpty(), args.getString(ARG_ROUTE_ID), args.getString(ARG_HEADSIGN))

        viewLifecycleOwner.lifecycleScope.launch {
            vm.lines.collect { lines -> bindLines(lines) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }
    }

    /**
     * The drop-down list of the lines, once known: "Автобус 111 към Ж.К.
     * ЛЮЛИН-1,2" (or "Автобус 84, само слизане", as the board says it) — "към"
     * on screen too, not "→": the selection TalkBack announces is the item's
     * text (review of 26 Nov 2026); the one shown selected; no caption over
     * it (the owner, 26 Nov 2026). Hidden while not known and when there is none.
     */
    private fun bindLines(lines: List<StopLine>?) {
        val any = !lines.isNullOrEmpty()
        binding.spinnerLine.visibility = if (any) View.VISIBLE else View.GONE
        if (lines == null || !any || lines === listed) return
        listed = lines
        val spinner = binding.spinnerLine
        spinner.onItemSelectedListener = null
        spinner.adapter = LineChoices(requireContext(), lines)
        spinner.setSelection(vm.selected, false)
        // Set after the selection: the callback the Spinner then makes for it
        // is of the line shown, not a choice.
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position == vm.selected) return
                // A line chosen: its timetable opened, at its first time to come.
                scrolled = false
                vm.select(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    /**
     * The lines, in the app's own item layouts (as Настройки' drop-down list:
     * not the platform's, whose row is a CheckedTextView — "не е отметнато" —
     * and follows the system theme).
     */
    private class LineChoices(context: Context, lines: List<StopLine>) :
        ArrayAdapter<String>(context, R.layout.item_spinner_selected, lines.map { l ->
            "${l.vehicleType} ${l.routeShortName}" + if (l.dropOffOnly) ", само слизане" else " към ${l.headsign}"
        }) {
        init { setDropDownViewResource(R.layout.item_spinner_dropdown) }
    }

    /** The days the strip was built of. */
    private var stripDays: List<DateHelper.DayChip> = emptyList()

    /** Builds the 7 day chips — again when the days change (the strip may begin yesterday). */
    private fun buildDayStrip(days: List<DateHelper.DayChip>) {
        if (chipViews.isNotEmpty() && stripDays == days) return
        binding.dayStrip.removeAllViews()
        chipViews.clear()
        stripDays = days
        val inflater = LayoutInflater.from(requireContext())
        days.forEach { day ->
            val chip = inflater.inflate(
                R.layout.item_day_chip, binding.dayStrip, false
            )
            chip.findViewById<TextView>(R.id.tvChipDay).text = day.dayShortBg
            chip.findViewById<TextView>(R.id.tvChipNum).text = day.dayNumber
            chip.contentDescription =
                DateHelper.chipContentDescription(day.date, day.isToday)
            chip.setOnClickListener { vm.selectDate(day.date) }

            // Distribute chips equally across the strip width.
            val lp = chip.layoutParams as LinearLayout.LayoutParams
            lp.width = 0
            lp.weight = 1f
            chip.layoutParams = lp

            binding.dayStrip.addView(chip)
            chipViews[day.date] = chip
        }
    }

    private fun renderState(state: ScheduleState) {
        if (state.days.isNotEmpty()) buildDayStrip(state.days)

        // Reflect current selection on the chips.
        chipViews.forEach { (date, chip) ->
            chip.isSelected = (date == state.selectedDate)
        }

        // Big header: full date of the selected day — no "вчера", "днес",
        // "утре" (the owner, 25 Nov 2026).
        if (state.selectedDate.isNotEmpty()) {
            val full = DateHelper.fullDateLabelBg(state.selectedDate)
            binding.tvSelectedDate.text = full
            binding.tvSelectedDate.contentDescription = "Разписание за $full"
        }

        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE

        // Group times by hour for compact display: "07: 05  20  35  50".
        // Another direction's short run: its minute marked with a letter —
        // "а", "б", … by where it ends, in the order they first come — and
        // the letters explained above the list (the owner, 24 Nov 2026).
        val marks = LinkedHashMap<String, String>()
        state.times.mapNotNull { it.endsAt }.distinct().forEachIndexed { i, end ->
            marks[end] = MARK_LETTERS.getOrNull(i)?.toString() ?: "${i + 1}"
        }
        // By time, as they come (a night line's evening before first); each
        // as a clock — 00:32, not 24:32 (the owner, 25 Nov 2026).
        val groups = state.times
            .groupBy { Math.floorDiv(it.seconds, 3600) }
            .map { (_, list) ->
                ScheduleAdapter.Row(
                    hour    = RouteOfTheDay.clock(list.first().seconds).substring(0, 2),
                    minutes = list.map { t ->
                        val clock = RouteOfTheDay.clock(t.seconds)
                        ScheduleAdapter.Minute(clock.substring(3, 5), t.endsAt?.let { marks[it] }, t.endsAt, clock, t.seconds)
                    }
                )
            }
        // On opening: the hour of the first time not yet passed at the top
        // (the owner, 25 Nov 2026) — no focus moved (27 Nov 2026).
        val firstRow = state.scrollTo?.takeIf { !scrolled && !state.loading }
            ?.let { t -> groups.indexOfFirst { row -> row.minutes.any { it.seconds == t } } }?.takeIf { it >= 0 }
        adapter.submitList(groups)
        draws++
        if (firstRow != null) { scrolled = true; scrollToRow(firstRow) }
        binding.tvLegend.text = marks.entries.joinToString("\n") { (end, mark) -> "$mark — до $end" }
        binding.tvLegend.visibility = if (marks.isEmpty() || state.loading) View.GONE else View.VISIBLE

        if (state.loading) {
            // On opening — three days looked at, nothing yet to show — shown;
            // a day tapped: nothing, it comes quickly.
            if (state.days.isEmpty()) {
                binding.tvEmpty.text = "Зареждане на разписанието…"
                binding.tvEmpty.visibility = View.VISIBLE
            } else binding.tvEmpty.visibility = View.GONE
            binding.rvSchedule.visibility = View.GONE
        } else if (groups.isEmpty()) {
            binding.tvEmpty.text = state.noDataReason ?: "Няма курсове за тази дата"
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvSchedule.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvSchedule.visibility = View.VISIBLE
            // No count said (the owner, 25 Nov 2026).
        }
    }

    /**
     * The row [pos] at the top of the list, TalkBack's focus left where it
     * is (the owner, 27 Nov 2026). Not if the list was drawn again meanwhile
     * (a day tapped at once).
     */
    private fun scrollToRow(pos: Int) {
        val rv = binding.rvSchedule
        val drawn = draws
        rv.post {
            if (drawn != draws) return@post
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        }
    }

    /** How many times the list was drawn: a row asked for one drawing is not brought on another. */
    private var draws = 0

    /** Left for the other tab (not with the window): opened afresh when shown again. */
    private var leftForTab = false

    override fun onPause() {
        super.onPause()
        // The window still in front: the other tab chosen. The window leaving,
        // it has left resumed before its tabs do.
        leftForTab = requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        // Shown: its tab chosen again (the owner, 25 Nov 2026: opened afresh
        // each time it is shown), first shown, or after a new view (a
        // rotation), or left open overnight (Home, the screen off): opened
        // afresh, at its hour (review of 25 Nov 2026). Back from Home the
        // same day: as it was left.
        if (leftForTab || !openedHere || vm.openedBeforeToday()) {
            leftForTab = false
            openedHere = true
            scrolled = false
            vm.open()
        }
    }

    /**
     * Opened since this view was made — not whether it was brought to its
     * hour: with no time to come, it never is, and the day chosen was lost on
     * every return (review of 26 Nov 2026).
     */
    private var openedHere = false

    /** Brought to its first time to come on opening — once, not on every redraw. */
    private var scrolled = false

    override fun onDestroyView() {
        super.onDestroyView()
        chipViews.clear()
        _binding = null
    }
}
