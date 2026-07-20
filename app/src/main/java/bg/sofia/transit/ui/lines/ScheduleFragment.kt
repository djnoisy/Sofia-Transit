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
import bg.sofia.transit.databinding.FragmentScheduleBinding
import bg.sofia.transit.util.DateHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows the static schedule for a specific (route, direction, stop) triple.
 *
 * The user browses by concrete date via a 7-day strip (today + 6 days), with
 * today selected by default. Reached by tapping a stop in StopsListFragment.
 */
@AndroidEntryPoint
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val vm: ScheduleViewModel by viewModels()
    private val args: ScheduleFragmentArgs by navArgs()

    private lateinit var adapter: ScheduleAdapter

    /** Chip views by date string, so we can toggle their selected state. */
    private val chipViews = mutableMapOf<String, View>()

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

    /** Builds the 7 day chips once, the first time the day list arrives. */
    private fun buildDayStrip(days: List<DateHelper.DayChip>) {
        if (chipViews.isNotEmpty()) return
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

        // Big header: full date of the selected day.
        if (state.selectedDate.isNotEmpty()) {
            val full = DateHelper.fullDateLabelBg(state.selectedDate)
            binding.tvSelectedDate.text = full
            binding.tvSelectedDate.contentDescription = "Разписание за $full"
        }

        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE

        // Group times by hour for compact display: "07: 05  20  35  50"
        val groups = state.times
            .filter { it.length >= 5 }
            .groupBy { it.substring(0, 2) }   // "07"
            .toSortedMap()
            .map { (hour, list) ->
                ScheduleAdapter.Row(
                    hour    = hour,
                    minutes = list.map { it.substring(3, 5) }   // "05"
                )
            }
        adapter.submitList(groups)

        if (state.loading) {
            binding.tvEmpty.visibility = View.GONE
            binding.rvSchedule.visibility = View.GONE
        } else if (groups.isEmpty()) {
            binding.tvEmpty.text = state.noDataReason ?: "Няма курсове за тази дата"
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvSchedule.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvSchedule.visibility = View.VISIBLE

            // Announce the count for TalkBack
            val total = state.times.size
            binding.rvSchedule.announceForAccessibility(
                "Намерени $total курса за избрания ден"
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chipViews.clear()
        _binding = null
    }
}
