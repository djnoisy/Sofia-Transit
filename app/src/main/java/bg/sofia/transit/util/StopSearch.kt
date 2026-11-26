package bg.sofia.transit.util

/**
 * Finding a stop by what is typed (Спирки → "Търсене"; the owner, 26 Nov
 * 2026): by its code — digits only, the code starting with them, with or
 * without its leading zeros ("169" finds 0169) — or by its name: every word
 * typed somewhere in it, whatever the case of its letters and the marks
 * between its words ("ал малинов" finds "МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ"). In
 * Kotlin, not in SQL: SQLite's UPPER and LIKE fold the case of Latin letters
 * only, and "малинов" found nothing in the names, mostly in capitals.
 *
 * The codes first, by code, then the names: those with each word typed a
 * word of theirs first, then those with a word beginning with it, then the
 * rest ("малинов": МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ before the ten stops of
 * МАЛИНОВА ДОЛИНА); of the same, by name and code (two stops of one name,
 * across the street, side by side). At most [LIMIT] (the owner: ten). A name
 * from two letters on; a code from its first digit.
 *
 * Pure Kotlin, tested on the stops of the feed (tools/test-harness/run_stop_search_tests.sh).
 */
object StopSearch {

    /** How many found are shown (the owner, 26 Nov 2026). */
    const val LIMIT = 10

    /** The words of [text], in capitals: anything but letters and digits parts them. */
    fun words(text: String): List<String> = text.uppercase().split(NOT_WORD).filter { it.isNotEmpty() }

    /** Of [stops], those [query] finds, in order, at most [limit]; [nameOf] and [codeOf] read a stop. */
    fun <T> find(stops: List<T>, query: String, nameOf: (T) -> String, codeOf: (T) -> String?, limit: Int = LIMIT): List<T> {
        val typed = query.trim()
        val digits = typed.isNotEmpty() && typed.all { it.isDigit() }
        val byCode = if (!digits) emptyList() else stops
            .filter { s -> codeOf(s)?.let { codeMatches(it, typed) } == true }
            .sortedWith(compareBy({ codeOf(it)!!.trimStart('0').length }, { codeOf(it)!! }))
        val wanted = words(typed)
        val byName = if (wanted.sumOf { it.length } < 2) emptyList() else stops
            .filter { it !in byCode }
            .mapNotNull { s -> match(words(nameOf(s)), wanted)?.let { s to it } }
            .sortedWith(compareBy({ it.second }, { words(nameOf(it.first)).joinToString(" ") }, { codeOf(it.first).orEmpty() }))
            .map { it.first }
        return (byCode + byName).take(limit)
    }

    /**
     * How well the words of a name ([name]) match those typed ([wanted]),
     * the lower the better — for each typed word 0 when a word of the name,
     * 1 when one begins with it, 2 when it is in the name elsewhere; null
     * when one is not in it at all.
     */
    private fun match(name: List<String>, wanted: List<String>): Int? {
        val joined = name.joinToString(" ")
        var score = 0
        for (w in wanted) score += when {
            name.any { it == w } -> 0
            name.any { it.startsWith(w) } -> 1
            w in joined -> 2
            else -> return null
        }
        return score
    }

    /** Whether [code] starts with the digits [typed], its leading zeros or not ("169": 0169, 1690). */
    private fun codeMatches(code: String, typed: String): Boolean {
        if (code.startsWith(typed)) return true
        val bare = typed.trimStart('0')
        return bare.isNotEmpty() && code.trimStart('0').startsWith(bare)
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
}
