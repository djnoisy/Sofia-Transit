package bg.sofia.transit.util

/**
 * How an arrival card shows its times (the owner, 9 Nov 2026): the nearest
 * under the line's badge, the next ones under the direction; "след" left
 * out on screen; "мин" said once — after the last of a run of minutes
 * ("30, 45 мин"; "30 мин, 17:10"). TalkBack hears the times in full, as
 * before (ArrivalAdapter).
 *
 * Pure Kotlin (tools/test-harness/run_arrival_times_tests.sh).
 */
object ArrivalTimes {

    /** The nearest time, as shown under the badge ("15 мин", "сега", "17:10"). */
    fun first(arrivals: List<String>): String = arrivals.firstOrNull()?.let(::shown) ?: ""

    /** The later times, as shown under the direction ("30, 45 мин"); "" if none. */
    fun later(arrivals: List<String>): String {
        val rest = arrivals.drop(1).map(::shown)
        return rest.mapIndexed { i, t ->
            val next = rest.getOrNull(i + 1)
            if (t.endsWith(MIN) && next != null && next.endsWith(MIN)) t.removeSuffix(MIN) else t
        }.joinToString(", ")
    }

    private fun shown(s: String): String = s.removePrefix("след ")

    private const val MIN = " мин"
}
