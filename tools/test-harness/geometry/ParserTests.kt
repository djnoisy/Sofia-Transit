// The shapes.txt parser (GtfsParser.parseShapes) and the point packing
// (ShapePoints): every point kept, in sequence order, only the shapes asked for.
import bg.sofia.transit.data.db.entity.ShapePoints
import bg.sofia.transit.data.parser.GtfsParser
import java.io.File

var ok = 0; val bad = mutableListOf<String>()
fun t(name: String, body: () -> Unit) {
    try { body(); ok++; println("PASS  $name") }
    catch (e: Throwable) { bad += "$name: ${e.message}"; println("FAIL  $name\n      ${e.message}") }
}
fun eq(w: String, e: Any?, a: Any?) { if (e != a) throw AssertionError("$w: expected <$e> but was <$a>") }

val root = kotlin.io.path.createTempDirectory("shapes").toFile()
val dir = File(root, "downloaded").apply { mkdirs() }
val assets = File(root, "assets").apply { File(this, "gtfs").mkdirs() }
val ctx = android.content.Context(root, assets)

fun parse(dir: File?, wanted: Set<String>) =
    kotlinx.coroutines.runBlocking { GtfsParser.parseShapes(ctx, dir, wanted) }

fun main() {

    t("P1 points kept as given, sorted by sequence, duplicates included; unwanted shapes left out") {
        File(dir, "shapes.txt").writeText(
            "﻿shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n" +
            "A1,42.5,23.5,3,\n" +
            "A1,42.1,23.1,1,\n" +
            "A1,42.2,23.2,2,\n" +
            "A1,42.2,23.2,2,\n" +           // the same point twice, as at stops
            "B9,1.0,2.0,1,\n" +             // a shape no trip uses
            "A2,42.65686798095703,23.38519859313965,1,\n" +
            "A2,oops,23.0,2,\n" +           // malformed: skipped
            "A2,42.66,23.38,3,\n")
        val shapes = parse(dir, setOf("A1", "A2")).associateBy { it.shapeId }
        eq("shapes", setOf("A1", "A2"), shapes.keys)
        eq("A1 points", listOf(42.1f, 23.1f, 42.2f, 23.2f, 42.2f, 23.2f, 42.5f, 23.5f),
            ShapePoints.decode(shapes["A1"]!!.points).toList())
        val a2 = ShapePoints.decode(shapes["A2"]!!.points)
        eq("A2 points (malformed line skipped)", 4, a2.size)
        eq("float coordinates kept exactly", 42.65686798095703, a2[0].toDouble())
    }

    t("P2 no downloaded set → the bundled shapes.txt is read, like every other file") {
        File(assets, "gtfs/shapes.txt").writeText(
            "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n" +
            "A1,42.1,23.1,1,\nA1,42.2,23.2,2,\n")
        eq("bundled", listOf(42.1f, 23.1f, 42.2f, 23.2f),
            ShapePoints.decode(parse(null, setOf("A1")).single().points).toList())
    }

    t("P3 packing round trip") {
        val pts = floatArrayOf(42.62636184692383f, 23.382368087768555f, -1.5f, 0f)
        eq("same floats", pts.toList(), ShapePoints.decode(ShapePoints.encode(pts)).toList())
        eq("4 bytes a float", 16, ShapePoints.encode(pts).size)
    }

    println("\n$ok passed, ${bad.size} failed"); bad.forEach { println("  ✗ $it") }
    kotlin.system.exitProcess(if (bad.isEmpty()) 0 else 1)
}
