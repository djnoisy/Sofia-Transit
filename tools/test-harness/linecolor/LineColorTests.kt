// The colour of a line's badge (util/LineColors.kt): the static data's colour
// taken; by id and number alone, the data's colour but for the green X lines;
// and the text on it stands out (4.5:1 at least). Run by run_line_color_tests.sh.
import bg.sofia.transit.util.LineColors
import java.io.File

fun main(args: Array<String>) {
    var fails = 0
    var n = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) { fails++; println("FAIL $name $detail") }
    }
    val header = File(args[0]).readLines().first().removePrefix("﻿").split(",")
    val idI = header.indexOf("route_id"); val nameI = header.indexOf("route_short_name")
    val colorI = header.indexOf("route_color")
    for (line in File(args[0]).readLines().drop(1)) {
        val f = line.split(",")
        if (f.size <= colorI) continue
        val data = LineColors.parse(f[colorI]) ?: continue
        n++
        // By id alone (only until the data's colours are at hand): as the
        // data, but for the green X lines (red then).
        val byId = LineColors.of(f[idI], f[nameI])
        check("${f[idI]} ${f[nameI]}: by id as the data", byId == data ||
            (f[nameI].startsWith("X") && byId == LineColors.BUS), "%06X vs %06X".format(byId, data))
        check("${f[idI]}: the data's colour taken", LineColors.of(f[idI], f[nameI], f[colorI]) == data)
        val text = if (LineColors.darkContent(data)) 0x000000 else 0xFFFFFF
        val c = LineColors.contrast(data, text)
        check("${f[idI]}: text on the badge 4.5:1", c >= 4.5, "%.2f".format(c))
    }
    check("a colour with #", LineColors.parse("#BE1E2D") == 0xBE1E2D)
    check("not a colour", LineColors.parse("red") == null && LineColors.parse("") == null && LineColors.parse(null) == null)
    check("no data colour: by id", LineColors.of("TM18", "18", "") == LineColors.TRAM)
    println("$n lines with a colour in the data")
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
