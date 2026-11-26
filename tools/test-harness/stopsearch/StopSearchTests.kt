// Finding a stop by what is typed (util/StopSearch.kt; Спирки → "Търсене", the owner, 26 Nov 2026): made-up cases,
// and with STOPS set to a feed's stops.txt, on its stops — one a code, as the app lists them.
import bg.sofia.transit.util.StopSearch
import java.io.File

class S(val name: String, val code: String?)

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) { if (!ok) { fails++; println("FAIL $n ${detail()}") } else println("PASS $n") }

fun main(args: Array<String>) {
    val stops = listOf(S("МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ", "0169"), S("МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ", "0170"), S("Гара Подуяне", "0469"),
        S("ГАРА ПОДУЯНЕ", "0470"), S("БЛ. 472 Ж.К. МЛАДОСТ-4", "0091"), S("Обеля", null), S("УЛ. 1690", "4720"))
    fun f(q: String) = StopSearch.find(stops, q, { it.name }, { it.code }).map { it.code ?: it.name }
    check("by name, whatever the case and the marks: every word typed in it", f("ал малинов") == listOf("0169", "0170")) { f("ал малинов").toString() }
    check("small letters find a name in capitals, and the other way round", f("подуяне") == listOf("0469", "0470") && f("ГАРА ПОДУЯНЕ").size == 2)
    check("a word not in the name: none", f("малинов сливница").isEmpty())
    val order = listOf(S("КВ. МАЛИНОВА ДОЛИНА", "0423"), S("ЗАМАЛИНОВ", "0001"), S("МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ", "0169"))
    check("a word of the name first, then one beginning with it, then within a word",
        StopSearch.find(order, "малинов", { it.name }, { it.code }).map { it.code } == listOf("0169", "0423", "0001"))
    check("by code, with or without its leading zeros; then the names with the digits", f("0169") == listOf("0169") &&
        f("169") == listOf("0169", "4720")) { f("169").toString() }
    check("digits: the codes first, then the names with them", f("472") == listOf("4720", "0091")) { f("472").toString() }
    check("a name from two letters, a code from one digit", f("о").isEmpty() && f("об") == listOf("Обеля") && f("0").isNotEmpty())
    check("nothing typed: none", f("").isEmpty() && f("  ").isEmpty())
    val zk = listOf(S("Ж.К. МЛАДОСТ-1", "0001"), S("ЖК МЛАДОСТ 4", "0002"), S("МЛАДОСТ", "0003"))
    fun z(q: String) = StopSearch.find(zk, q, { it.name }, { it.code }).map { it.code }
    check("'жк' finds Ж.К. as ЖК, and 'ж.к.' finds ЖК", z("жк младост").toSet() == setOf("0001", "0002") &&
        z("ж.к. младост").toSet() == setOf("0001", "0002")) { "${z("жк младост")} ${z("ж.к. младост")}" }
    val codes = listOf(S("А", "0016"), S("Б", "1600"), S("В", "0160"), S("Г", "0169"), S("Д", "1690"))
    fun c(q: String) = StopSearch.find(codes, q, { it.name }, { it.code }).map { it.code }
    check("the code typed itself first, then those beginning with it, then without leading zeros",
        c("016") == listOf("0016", "0160", "0169", "1600", "1690") && c("169") == listOf("0169", "1690")) { "${c("016")} ${c("169")}" }
    val many = (1..30).map { S("СПИРКА $it", "%04d".format(it)) }
    check("at most ten", StopSearch.find(many, "спирка", { it.name }, { it.code }).size == 10)

    // A feed's stops, one a code (stops of no code each its own), as GtfsRepository.searchableStops gives them.
    val file = args.getOrNull(0)?.let { File(it) }
    if (file != null && file.isFile) {
        val lines = file.readLines()
        val head = lines[0].removePrefix("﻿").split(",")
        val iName = head.indexOf("stop_name"); val iCode = head.indexOf("stop_code"); val iType = head.indexOf("location_type")
        // Stops, not stations or their entrances (StopDao: locationType 0).
        val feed = lines.drop(1).map { l -> l.split(",") }.filter { it.size > iType && it[iType].trim('"').ifEmpty { "0" } == "0" }
            .map { S(it[iName].trim('"'), it[iCode].trim('"').ifEmpty { null }) }.distinctBy { it.code ?: it.name }
        fun g(q: String) = StopSearch.find(feed, q, { it.name }, { it.code })
        println("  ${feed.size} stops; 'малинов': ${g("малинов").map { "${it.name} ${it.code}" }}")
        println("  '169': ${g("169").map { "${it.name} ${it.code}" }}; 'хемус': ${g("хемус").map { "${it.name} ${it.code}" }}")
        check("feed: 'малинов' in small letters finds АЛ. МАЛИНОВ, before МАЛИНОВА ДОЛИНА",
            g("малинов").indexOfFirst { it.code == "0169" }.let { it >= 0 && it < g("малинов").indexOfFirst { s -> "ДОЛИНА" in s.name } })
        check("feed: '169' finds 0169 first", g("169").firstOrNull()?.code == "0169")
        check("feed: 'ал. малинов' and 'Ал Малинов' alike", g("ал. малинов").map { it.code } == g("Ал Малинов").map { it.code })
        println("  'жк люлин': ${g("жк люлин").size}, 'ж.к. люлин': ${g("ж.к. люлин").size}; '016': ${g("016").map { it.code }}")
        check("feed: 'жк люлин' finds what 'ж.к. люлин' does", g("жк люлин").isNotEmpty() &&
            g("жк люлин").map { it.code } == g("ж.к. люлин").map { it.code })
        check("feed: '0169' finds 0169 first", g("0169").firstOrNull()?.code == "0169")
    }
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
