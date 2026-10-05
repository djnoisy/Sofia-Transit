// Every trip shape of a CGM static feed against the stops of a trip that
// uses it: does RouteGeometry.fit place all the stops on the road, in order?
// Run by run_geometry_tests.sh when GTFS_DIR is set.
import bg.sofia.transit.service.RouteGeometry
import java.io.File

/** Splits a CSV line on commas outside quotes (headsigns can contain commas). */
fun split(line: String): List<String> =
    if ('"' !in line) line.split(",")
    else Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").split(line).map { it.removeSurrounding("\"") }

fun csv(f: File, wanted: (Map<String, Int>, List<String>) -> Boolean = { _, _ -> true },
        each: (Map<String, Int>, List<String>) -> Unit) {
    f.bufferedReader().use { r ->
        val head = r.readLine().removePrefix("﻿").split(",").mapIndexed { i, c -> c.trim() to i }.toMap()
        r.forEachLine { line -> val c = split(line); if (wanted(head, c)) each(head, c) }
    }
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val tripOfShape = HashMap<String, String>()
    csv(File(dir, "trips.txt")) { h, c -> c[h["shape_id"]!!].takeIf { it.isNotBlank() }?.let { tripOfShape.putIfAbsent(it, c[h["trip_id"]!!]) } }
    val wantTrips = tripOfShape.values.toHashSet()
    val stopsOfTrip = HashMap<String, MutableList<Pair<Int, String>>>()
    csv(File(dir, "stop_times.txt"), { h, c -> c[h["trip_id"]!!] in wantTrips }) { h, c ->
        stopsOfTrip.getOrPut(c[h["trip_id"]!!]) { mutableListOf() } += c[h["stop_sequence"]!!].toInt() to c[h["stop_id"]!!]
    }
    val stopPos = HashMap<String, Pair<Double, Double>>()
    // Stop names may contain commas in quotes; lat/lon are read from the end-independent columns.
    File(dir, "stops.txt").bufferedReader().use { r ->
        val head = r.readLine().removePrefix("﻿").split(",").map { it.trim() }
        val iId = head.indexOf("stop_id"); val iLat = head.indexOf("stop_lat"); val iLon = head.indexOf("stop_lon")
        r.forEachLine { line ->
            val c = split(line)
            stopPos[c[iId]] = c[iLat].toDouble() to c[iLon].toDouble()
        }
    }
    val pts = HashMap<String, MutableList<Triple<Int, Float, Float>>>()
    csv(File(dir, "shapes.txt"), { h, c -> c[h["shape_id"]!!] in tripOfShape }) { h, c ->
        pts.getOrPut(c[h["shape_id"]!!]) { mutableListOf() } +=
            Triple(c[h["shape_pt_sequence"]!!].toInt(), c[h["shape_pt_lat"]!!].toFloat(), c[h["shape_pt_lon"]!!].toFloat())
    }
    var fitted = 0; val failed = mutableListOf<String>()
    for ((shape, trip) in tripOfShape) {
        val raw = pts[shape]
        if (raw == null) { failed += "$shape: no points"; continue }
        val p = raw.sortedBy { it.first }
        val latLon = FloatArray(p.size * 2) { if (it % 2 == 0) p[it / 2].second else p[it / 2].third }
        val stops = stopsOfTrip[trip]?.sortedBy { it.first }?.map { stopPos[it.second]!! } ?: emptyList()
        if (RouteGeometry.fit(latLon, stops) != null) fitted++ else failed += "$shape (trip $trip, ${stops.size} stops)"
    }
    println("$fitted of ${tripOfShape.size} shapes fit their trips' stops")
    failed.take(20).forEach { println("  ✗ $it") }
    if (failed.isNotEmpty()) kotlin.system.exitProcess(1)
}
