package bg.sofia.transit.util
object FileLogger {
    val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    var echo = false
    private fun add(l: String, t: String, m: String) { lines += "$l/$t: $m"; if (echo) println("    [$l/$t] $m") }
    fun i(t: String, m: String) = add("I", t, m)
    fun w(t: String, m: String) = add("W", t, m)
    fun d(t: String, m: String) = add("D", t, m)
    fun e(t: String, m: String, e: Throwable? = null) = add("E", t, m + (e?.let { " ($it)" } ?: ""))
}
