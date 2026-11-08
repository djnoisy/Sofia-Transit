package bg.sofia.transit.service

/**
 * Trips at a stop around us, or just past it — for the early rule by the
 * boarding stop (JourneyService.fetchBoardingArrivals, RideModel.onBoarding).
 *
 * The trip-updates feed drops the stop a vehicle has reached: its list then
 * begins at the next stop (the live feeds on 8 Nov 2026: 11 of 12 vehicles
 * standing within 30 m of a stop of their trip, 96 of 103 moving there). So
 * a vehicle already at our stop when the arrivals around us are first
 * collected — tracking begun with it there — is not among those due there,
 * and the early rule passes it over though it is the one we board (7 Nov
 * 2026, b: 213 at ХЕРМЕС ПАРК, 18 m from us, taken by the metres 30 s later).
 *
 * Such a trip is one whose first stop ahead in the feed comes right after
 * one of the stops around us in its own order of stops (the static data):
 * it is at that stop, or has just left it. Had the arrivals been collected a
 * little earlier, before it came, it would have been due there anyway — so
 * this adds no vehicle the rule could not take already; and the rule still
 * takes one only alone near us, as we move off, once.
 *
 * Pure Kotlin, so it can be tested on its own
 * (tools/test-harness/run_boarding_tests.sh).
 */
object ReachedStops {
    /**
     * (trip, stop) for each trip of [firstAhead] (trip → the first stop
     * still ahead of it in the feed) whose order of stops in [orders]
     * (trip → its stops in order) has one of [stopIds] right before that
     * stop. A trip not in [orders] — not in the static data — gives nothing;
     * nor one whose stop ahead comes twice in its order, as on a loop: which
     * time is meant is not known (none in the network on 8 Nov 2026).
     */
    fun of(
        stopIds: Set<String>,
        firstAhead: Map<String, String>,
        orders: Map<String, List<String>>
    ): Set<Pair<String, String>> {
        val reached = HashSet<Pair<String, String>>()
        for ((trip, next) in firstAhead) {
            val order = orders[trip] ?: continue
            if (order.count { it == next } != 1) continue
            for (i in 0 until order.size - 1) {
                if (order[i + 1] == next && order[i] in stopIds) reached += trip to order[i]
            }
        }
        return reached
    }
}
