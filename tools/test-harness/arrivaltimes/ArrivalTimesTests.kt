// How an arrival card shows its times (util/ArrivalTimes.kt).
import bg.sofia.transit.util.ArrivalTimes

fun main() {
    var fails = 0
    fun eq(name: String, want: String, got: String) { if (want != got) { fails++; println("FAIL $name: want '$want' got '$got'") } }
    val m = listOf("след 15 мин", "след 30 мин", "след 45 мин")
    eq("first, minutes", "15 мин", ArrivalTimes.first(m))
    eq("later, both minutes", "30, 45 мин", ArrivalTimes.later(m))
    eq("later, last a clock time", "30 мин, 17:10", ArrivalTimes.later(listOf("след 15 мин", "след 30 мин", "17:10")))
    eq("later, both clock times", "17:00, 17:10", ArrivalTimes.later(listOf("след 15 мин", "17:00", "17:10")))
    eq("first, now", "сега", ArrivalTimes.first(listOf("сега", "след 9 мин")))
    eq("later, one", "9 мин", ArrivalTimes.later(listOf("сега", "след 9 мин")))
    eq("later, none", "", ArrivalTimes.later(listOf("след 3 мин")))
    eq("first, none", "", ArrivalTimes.first(emptyList()))
    eq("first, clock time", "17:10", ArrivalTimes.first(listOf("17:10", "17:40")))
    eq("later, four, minutes then a time", "30, 45 мин, 18:00", ArrivalTimes.later(listOf("след 5 мин", "след 30 мин", "след 45 мин", "18:00")))
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
