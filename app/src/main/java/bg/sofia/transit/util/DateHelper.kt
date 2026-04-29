package bg.sofia.transit.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Helpers for picking the right calendar date when the user wants to see
 * a "weekday" or "weekend" schedule without specifying an exact date.
 *
 * Sofia's GTFS feed enumerates every concrete date a service runs on, with
 * no calendar.txt to define day-of-week patterns. So to show a "weekday"
 * schedule we pick a recent normal weekday (Tuesday or Wednesday), and to
 * show a "weekend" schedule we pick a recent Sunday.
 */
object DateHelper {

    private val FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    enum class DayType(val labelBg: String) {
        TODAY("Днес"),
        WEEKDAY("Делник"),
        SATURDAY("Събота"),
        SUNDAY("Неделя")
    }

    fun todayString(): String = LocalDate.now().format(FMT)

    /**
     * Returns a YYYYMMDD date suitable as a representative for the requested
     * day type, picked from the list of dates available in the feed.
     *
     * The strategy: walk a few weeks forward from today; pick the first
     * matching date that the feed actually has schedule data for.
     */
    fun representativeDate(
        dayType: DayType,
        availableDates: Set<String>
    ): String? {
        val today = LocalDate.now()
        if (dayType == DayType.TODAY) {
            return today.format(FMT).takeIf { it in availableDates }
        }
        val targetDow = when (dayType) {
            DayType.WEEKDAY  -> DayOfWeek.TUESDAY     // representative weekday
            DayType.SATURDAY -> DayOfWeek.SATURDAY
            DayType.SUNDAY   -> DayOfWeek.SUNDAY
            DayType.TODAY    -> return null
        }
        // Search up to 6 weeks ahead for a matching date in the feed
        var date = today
        repeat(42) {
            if (date.dayOfWeek == targetDow) {
                val s = date.format(FMT)
                if (s in availableDates) return s
            }
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * Bulgarian day-of-week label for a YYYYMMDD date.
     */
    fun bgDayLabel(date: String): String {
        return try {
            val d = LocalDate.parse(date, FMT)
            when (d.dayOfWeek) {
                DayOfWeek.MONDAY    -> "понеделник"
                DayOfWeek.TUESDAY   -> "вторник"
                DayOfWeek.WEDNESDAY -> "сряда"
                DayOfWeek.THURSDAY  -> "четвъртък"
                DayOfWeek.FRIDAY    -> "петък"
                DayOfWeek.SATURDAY  -> "събота"
                DayOfWeek.SUNDAY    -> "неделя"
            }
        } catch (_: Exception) { "" }
    }
}
