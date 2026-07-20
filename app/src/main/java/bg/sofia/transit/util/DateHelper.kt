package bg.sofia.transit.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Date helpers for the schedule screen.
 *
 * The schedule is browsed by concrete date, not by an abstract "weekday /
 * weekend" type. Sofia's GTFS feed enumerates every concrete date a service
 * runs on (calendar_dates.txt, no calendar.txt), so showing real dates is
 * both simpler and more honest: it naturally covers public holidays, seasonal
 * timetables and one-off changes that a weekday/weekend toggle would hide.
 *
 * The screen shows a 7-day window starting today; the user taps a day to see
 * that date's schedule.
 */
object DateHelper {

    private val FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** One day in the date strip. */
    data class DayChip(
        val date: String,        // YYYYMMDD — key for schedule lookup
        val dayShortBg: String,  // "вт" — short weekday, shown on the chip
        val dayNumber: String,   // "20"  — day-of-month, shown on the chip
        val isToday: Boolean
    )

    fun todayString(): String = LocalDate.now().format(FMT)

    /**
     * Builds the [count]-day window starting from today (inclusive).
     * We never show past dates — a transit schedule for yesterday is useless.
     */
    fun upcomingDays(count: Int = 7): List<DayChip> {
        val today = LocalDate.now()
        return (0 until count).map { offset ->
            val d = today.plusDays(offset.toLong())
            DayChip(
                date       = d.format(FMT),
                dayShortBg = shortDayBg(d.dayOfWeek),
                dayNumber  = d.dayOfMonth.toString(),
                isToday    = offset == 0
            )
        }
    }

    /**
     * Full, human-readable Bulgarian label for the big header above the
     * schedule, e.g. "вторник, 20 юли 2026 г.".
     */
    fun fullDateLabelBg(date: String): String = try {
        val d = LocalDate.parse(date, FMT)
        "${longDayBg(d.dayOfWeek)}, ${d.dayOfMonth} ${monthBg(d.monthValue)} ${d.year} г."
    } catch (_: Exception) { "" }

    /** Short weekday for the chip: пн вт ср чт пт сб нд. */
    private fun shortDayBg(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY    -> "пн"
        DayOfWeek.TUESDAY   -> "вт"
        DayOfWeek.WEDNESDAY -> "ср"
        DayOfWeek.THURSDAY  -> "чт"
        DayOfWeek.FRIDAY    -> "пт"
        DayOfWeek.SATURDAY  -> "сб"
        DayOfWeek.SUNDAY    -> "нд"
    }

    /** Full weekday name, lower-case, for the header and TalkBack. */
    fun longDayBg(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY    -> "понеделник"
        DayOfWeek.TUESDAY   -> "вторник"
        DayOfWeek.WEDNESDAY -> "сряда"
        DayOfWeek.THURSDAY  -> "четвъртък"
        DayOfWeek.FRIDAY    -> "петък"
        DayOfWeek.SATURDAY  -> "събота"
        DayOfWeek.SUNDAY    -> "неделя"
    }

    /** Bulgarian month name in the genitive-like form used in dates. */
    private fun monthBg(month: Int): String = when (month) {
        1  -> "януари";   2  -> "февруари"; 3  -> "март"
        4  -> "април";    5  -> "май";      6  -> "юни"
        7  -> "юли";      8  -> "август";   9  -> "септември"
        10 -> "октомври"; 11 -> "ноември";  12 -> "декември"
        else -> ""
    }

    /**
     * Full accessibility description of a chip, e.g.
     * "вторник, 20 юли, днес". Read by TalkBack instead of the terse "вт 20".
     */
    fun chipContentDescription(date: String, isToday: Boolean): String = try {
        val d = LocalDate.parse(date, FMT)
        val base = "${longDayBg(d.dayOfWeek)}, ${d.dayOfMonth} ${monthBg(d.monthValue)}"
        if (isToday) "$base, днес" else base
    } catch (_: Exception) { "" }
}
