package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.databinding.FragmentScheduleBinding
import bg.sofia.transit.util.DateHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows the static schedule for a specific (route, direction, stop) triple:
 * the direction's trips and, marked, the line's short runs of other
 * directions that leave the stop its way (GtfsRepository.getScheduleForDirectionAtStop).
 *
 * The user browses by concrete date via a 7-day strip (today + 6 days; from
 * yesterday when it opens on yesterday), opened on the first day with a time
 * not yet passed (ScheduleViewModel; the owner, 25 Nov 2026). Reached by tapping a stop in StopsListFragment,
 * and from the "Виртуално табло" in a window of its own (LineWindowActivity).
 */
@AndroidEntryPoint
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val vm: ScheduleViewModel by viewModels()
    private val args: ScheduleFragmentArgs by navArgs()

    private lateinit var adapter: ScheduleAdapter

    /** Chip views by date string, so we can toggle their selected state. */
    private val chipViews = linkedMapOf<String, View>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.tvHeader.text =
            "Линия ${args.routeName} → ${args.headsign}"
        binding.tvHeader.contentDescription =
            "Линия ${args.routeName} към ${args.headsign}"

        binding.tvSubHeader.text = "Спирка: ${args.stopName}"

        // Replace "Линия N" with the concrete vehicle type once meta loads
        // — same source as the Lines list, so "Автобус 84" / "Тролей 2"
        // read consistently across the app. Visual uses "→"; TalkBack
        // reads "към" so it sounds natural instead of speaking a symbol.
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = vm.getRouteMeta(args.routeId)
            val label = meta.vehicleType?.replaceFirstChar { it.uppercase() } ?: "Линия"
            binding.tvHeader.text = "$label ${args.routeName} → ${args.headsign}"
            binding.tvHeader.contentDescription =
                "$label ${args.routeName} към ${args.headsign}"
        }

        adapter = ScheduleAdapter()
        binding.rvSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedule.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }

        vm.init(
            routeId  = args.routeId,
            headsign = args.headsign,
            stopId   = args.stopId
        )
    }

    /** Builds the 7 day chips — again when the days change (the strip may begin yesterday). */
    private fun buildDayStrip(days: List<DateHelper.DayChip>) {
        if (chipViews.keys.toList() == days.map { it.date }) return
        binding.dayStrip.removeAllViews()
        chipViews.clear()
        val inflater = LayoutInflater.from(requireContext())
        days.forEach { day ->
            val chip = inflater.inflate(
                R.layout.item_day_chip, binding.dayStrip, false
            )
            chip.findViewById<TextView>(R.id.tvChipDay).text = day.dayShortBg
            chip.findViewById<TextView>(R.id.tvChipNum).text = day.dayNumber
            chip.contentDescription =
                DateHelper.chipContentDescription(day.date, day.relative)
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

        // Big header: full date of the selected day — for TalkBack with
        // "вчера", "днес", "утре": the screen may open on any of the three.
        val relative = state.days.firstOrNull { it.date == state.selectedDate }?.relative
        if (state.selectedDate.isNotEmpty()) {
            val full = DateHelper.fullDateLabelBg(state.selectedDate)
            binding.tvSelectedDate.text = full
            binding.tvSelectedDate.contentDescription = "Разписание за $full${relative?.let { ", $it" } ?: ""}"
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
                        ScheduleAdapter.Minute(clock.substring(3, 5), t.endsAt?.let { marks[it] }, t.endsAt, clock)
                    }
                )
            }
        adapter.submitList(groups)
        binding.tvLegend.text = marks.entries.joinToString("\n") { (end, mark) -> "$mark — до $end" }
        binding.tvLegend.visibility = if (marks.isEmpty() || state.loading) View.GONE else View.VISIBLE

        if (state.loading) {
            // On opening — three days looked at, nothing yet to show — said;
            // a day tapped: quiet, it comes quickly.
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

            // Announce the count for TalkBack, and for which day — the
            // screen may open on yesterday or tomorrow (review of 25 Nov 2026):
            // "Намерени 24 курса за петък, 27 ноември, утре".
            val total = state.times.size
            binding.rvSchedule.announceForAccessibility(
                "Намерени $total курса за ${DateHelper.chipContentDescription(state.selectedDate, relative)}"
            )
        }
    }

    private companion object {
        /** The letters short runs are marked with, by where they end. */
        const val MARK_LETTERS = "абвгдежзиклмнопрстуф"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chipViews.clear()
        _binding = null
    }
}
