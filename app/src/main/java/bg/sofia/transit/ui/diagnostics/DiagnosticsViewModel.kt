package bg.sofia.transit.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RealtimeDiagnostics
import bg.sofia.transit.data.repository.RealtimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsState(
    val running: Boolean = false,
    val report: String = ""
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    private val realtimeRepo: RealtimeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state

    fun runDiagnostics() {
        _state.value = DiagnosticsState(running = true, report = "Стартиране на проверка…\n")
        viewModelScope.launch {
            val sb = StringBuilder()

            // 1. Static DB info
            sb.appendLine("=== СТАТИЧНИ ДАННИ ===")
            try {
                val sample = gtfsRepo.getSampleStops(5)
                sb.appendLine("Брой спирки в базата: ${sample.totalCount}")
                sb.appendLine("Примерни stop_id-та от базата:")
                sample.samples.forEach { sb.appendLine("  • $it") }
            } catch (e: Exception) {
                sb.appendLine("ГРЕШКА: ${e.message}")
            }

            sb.appendLine()
            sb.appendLine("=== РЕАЛНОВРЕМЕННИ ДАННИ ===")
            sb.appendLine("URL: https://gtfs.sofiatraffic.bg/api/v1/trip-updates")
            sb.appendLine()

            try {
                val diag: RealtimeDiagnostics = realtimeRepo.diagnose()
                if (!diag.success) {
                    sb.appendLine("ГРЕШКА: ${diag.errorMessage}")
                } else {
                    sb.appendLine("Получени байтове: ${diag.bytesReceived}")
                    sb.appendLine("Брой entities: ${diag.entityCount}")
                    sb.appendLine("Брой trip_update-и: ${diag.tripUpdateCount}")
                    sb.appendLine("Уникални stop_id-та в feed: ${diag.uniqueStopIds}")
                    sb.appendLine()
                    sb.appendLine("Разпределение на stop_id по префикс:")
                    diag.stopsByPrefix.entries
                        .sortedByDescending { it.value }
                        .forEach { sb.appendLine("  • ${it.key}... → ${it.value}") }
                    sb.appendLine()
                    sb.appendLine("Разпределение на route_id по префикс:")
                    diag.routesByPrefix.entries
                        .sortedByDescending { it.value }
                        .forEach { sb.appendLine("  • ${it.key}... → ${it.value}") }
                    sb.appendLine()
                    sb.appendLine("Първите 10 stop_id-та (в реда от feed):")
                    diag.sampleStopIds.forEach { sb.appendLine("  • $it") }
                    sb.appendLine()
                    sb.appendLine("Първите 10 route_id-та:")
                    diag.sampleRouteIds.forEach { sb.appendLine("  • $it") }
                }
            } catch (e: Exception) {
                sb.appendLine("ГРЕШКА: ${e.javaClass.simpleName}: ${e.message}")
            }

            sb.appendLine()
            sb.appendLine("=== СРАВНЕНИЕ СЪС СТАТИЧНИТЕ ===")
            try {
                val match = realtimeRepo.compareWithStatic(gtfsRepo)
                sb.appendLine("Stop_id-та съвпадат: ${match.matchingStopIds} от ${match.totalStopIdsInFeed}")
                sb.appendLine("Route_id-та съвпадат: ${match.matchingRouteIds} от ${match.totalRouteIdsInFeed}")
            } catch (e: Exception) {
                sb.appendLine("ГРЕШКА: ${e.message}")
            }

            _state.value = DiagnosticsState(running = false, report = sb.toString())
        }
    }

    fun lookupStop(stopId: String) {
        val cleaned = stopId.trim().uppercase()
        if (cleaned.isEmpty()) return

        _state.value = DiagnosticsState(running = true, report = "Проверка на $cleaned…\n")

        viewModelScope.launch {
            val sb = StringBuilder()
            sb.appendLine("=== ПРОВЕРКА НА СПИРКА $cleaned ===")
            sb.appendLine()

            // 1. Is it in the static DB?
            val staticStop = try {
                gtfsRepo.getStopById(cleaned)
            } catch (e: Exception) { null }

            if (staticStop == null) {
                sb.appendLine("⚠ Спирка с ID '$cleaned' не съществува в статичните данни")
            } else {
                sb.appendLine("Намерена в статични данни:")
                sb.appendLine("  Име: ${staticStop.stopName}")
            }

            sb.appendLine()
            sb.appendLine("--- Realtime feed (raw) ---")

            try {
                val result = realtimeRepo.lookupStop(cleaned)
                if (result.errorMessage != null) {
                    sb.appendLine("ГРЕШКА: ${result.errorMessage}")
                } else if (!result.found) {
                    sb.appendLine("В момента няма stop_time_update-и за тази спирка")
                } else {
                    sb.appendLine("Общо: ${result.totalForStop}, минало: ${result.pastForStop}, " +
                                  "бъдеще: ${result.entries.size}")
                    sb.appendLine()
                    if (result.entries.isNotEmpty()) {
                        // Bulk-resolve trip + route info from static DB
                        val tripIds  = result.entries.map { it.tripId }.distinct()
                        val routeIds = result.entries.map { it.routeId }.distinct()
                        val staticTrips = gtfsRepo.getTripsByIds(tripIds).associateBy { it.tripId }
                        val staticRoutes = gtfsRepo.getRoutesByIds(routeIds).associateBy { it.routeId }

                        sb.appendLine("Намерени trip-ове в статични данни: ${staticTrips.size} от ${tripIds.size}")
                        sb.appendLine("Намерени route-ове в статични данни: ${staticRoutes.size} от ${routeIds.size}")
                        sb.appendLine()

                        sb.appendLine("Следващи пристигания (с резолвирани имена):")
                        result.entries.forEach { e ->
                            val mins = e.secondsUntil / 60
                            val staticTrip  = staticTrips[e.tripId]
                            val staticRoute = staticRoutes[e.routeId]

                            val shortName = staticRoute?.routeShortName ?: "?"
                            val headsign  = e.headsign
                                            ?: staticTrip?.tripHeadsign
                                            ?: "?"

                            sb.appendLine("  • Линия $shortName (route_id=${e.routeId})")
                            sb.appendLine("    към $headsign — след $mins мин")
                            sb.appendLine("    trip_id: ${e.tripId}  ${if (staticTrip != null) "✓" else "✗ не е в DB"}")
                        }

                        // Test the actual pipeline that the UI uses
                        sb.appendLine()
                        sb.appendLine("--- Тест на UI потока (getArrivalsForStop) ---")
                        try {
                            val arrivals = gtfsRepo.getArrivalsForStop(cleaned, realtimeRepo)
                            sb.appendLine("Върнати ArrivalInfo записи: ${arrivals.size}")
                            if (arrivals.isEmpty()) {
                                sb.appendLine("⚠ ВНИМАНИЕ: UI потокът връща празно!")
                                sb.appendLine("Това е причината потребителят да не вижда нищо.")
                            } else {
                                arrivals.forEach { a ->
                                    sb.appendLine("  • Линия ${a.routeShortName} → ${a.headsign}: ${a.arrivals.joinToString()}")
                                }
                            }
                        } catch (e: Exception) {
                            sb.appendLine("ГРЕШКА в потока: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("ГРЕШКА: ${e.javaClass.simpleName}: ${e.message}")
            }

            _state.value = DiagnosticsState(running = false, report = sb.toString())
        }
    }

    fun testNearbyStops(userLat: Double, userLon: Double) {
        _state.value = DiagnosticsState(running = true, report = "Извличане на близки спирки…\n")
        viewModelScope.launch {
            val sb = StringBuilder()
            sb.appendLine("=== ТЕСТ НА БЛИЗКИ СПИРКИ ===")
            sb.appendLine("Местоположение: $userLat, $userLon")
            sb.appendLine()
            try {
                val rows = gtfsRepo.getNearestStopsDiagnostic(userLat, userLon, limit = 15)
                sb.appendLine("Върнати ${rows.size} реда (подредени според SQL):")
                sb.appendLine()
                rows.forEachIndexed { i, r ->
                    val haversine = bg.sofia.transit.util.LocationHelper.distanceMetres(
                        userLat, userLon, r.stopLat, r.stopLon
                    )
                    sb.appendLine("${i+1}. ${r.stopId} (${r.stopCode ?: "—"})  ${r.stopName}")
                    sb.appendLine("   distSq=${"%.10f".format(r.distSq)}  истинско=${haversine.toInt()}м")
                }
            } catch (e: Exception) {
                sb.appendLine("ГРЕШКА: ${e.javaClass.simpleName}: ${e.message}")
            }
            _state.value = DiagnosticsState(running = false, report = sb.toString())
        }
    }
}
