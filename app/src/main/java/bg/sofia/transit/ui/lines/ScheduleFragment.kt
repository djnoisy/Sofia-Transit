package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentScheduleBinding
import bg.sofia.transit.util.DateHelper
import bg.sofia.transit.util.DateHelper.DayType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows the static schedule for a specific (route, direction, stop) triple,
 * filtered by day-type (Today / Weekday / Saturday / Sunday).
 *
 * Reached by tapping a stop in StopsListFragment.
 */
@AndroidEntryPoint
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val vm: ScheduleViewModel by viewModels()
    private val args: ScheduleFragmentArgs by navArgs()

    private lateinit var adapter: ScheduleAdapter

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
            "Разписание за линия ${args.routeName} към ${args.headsign}, " +
            "от спирка ${args.stopName}"

        binding.tvSubHeader.text = "Спирка: ${args.stopName}"

        adapter = ScheduleAdapter()
        binding.rvSchedule.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            contentDescription = "Часове на пристигане"
        }

        binding.btnToday.setOnClickListener     { vm.selectDayType(DayType.TODAY) }
        binding.btnWeekday.setOnClickListener   { vm.selectDayType(DayType.WEEKDAY) }
        binding.btnSaturday.setOnClickListener  { vm.selectDayType(DayType.SATURDAY) }
        binding.btnSunday.setOnClickListener    { vm.selectDayType(DayType.SUNDAY) }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }

        vm.init(
            routeId  = args.routeId,
            headsign = args.headsign,
            stopId   = args.stopId
        )
    }

    private fun renderState(state: ScheduleState) {
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
            binding.tvEmpty.text = state.noDataReason ?: "Няма данни"
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvSchedule.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvSchedule.visibility = View.VISIBLE

            // Update sub-header with effective date for transparency
            val effectiveLabel = state.effectiveDate?.let { d ->
                val dow = DateHelper.bgDayLabel(d)
                "Спирка: ${args.stopName} • показва се $dow"
            } ?: "Спирка: ${args.stopName}"
            binding.tvSubHeader.text = effectiveLabel

            // Announce the count for TalkBack
            val total = state.times.size
            binding.rvSchedule.announceForAccessibility(
                "Намерени $total пътувания за избрания ден"
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
