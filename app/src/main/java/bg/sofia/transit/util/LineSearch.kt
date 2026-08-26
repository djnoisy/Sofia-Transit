package bg.sofia.transit.util

/**
 * Matching of route short names typed by the user.
 *
 * Sofia's feed spells the same line on both alphabets — the static data
 * contains "10TM" and "10ТМ", "6TM" and "6ТМ", "12А" with a Cyrillic А next
 * to "22A" with a Latin one, plus "11Tm" and "1Tb" in mixed case. Those are
 * indistinguishable on screen, so a passenger cannot know which one to type.
 * Everything is therefore folded onto one alphabet before comparing.
 *
 * Results are ranked rather than filtered: typing "1" must not bury line 1
 * under the fifty other lines whose name contains a 1.
 */
object LineSearch {

    /** Latin letters that look identical to Cyrillic ones, mapped to Cyrillic. */
    private val HOMOGLYPHS = mapOf(
        'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М',
        'H' to 'Н', 'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т',
        'X' to 'Х', 'Y' to 'У'
    )

    /**
     * Upper-cases, strips separators and folds look-alike Latin letters onto
     * Cyrillic, so "10tm", "10TM" and "10ТМ" all compare equal.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.uppercase().trim()) {
            if (ch == ' ' || ch == '-' || ch == '.' || ch == '/') continue
            sb.append(HOMOGLYPHS[ch] ?: ch)
        }
        return sb.toString()
    }

    /**
     * How well [name] matches [query]; higher is better, 0 means no match.
     *   3 — exact
     *   2 — starts with the query
     *   1 — contains it
     */
    fun score(name: String, query: String): Int {
        if (query.isEmpty()) return 1
        val n = normalize(name)
        val q = normalize(query)
        return when {
            n == q            -> 3
            n.startsWith(q)   -> 2
            n.contains(q)     -> 1
            else              -> 0
        }
    }

    /**
     * Orders matches so the most likely intent comes first: exact, then
     * prefix, then substring. Within the same tier, lines that have a vehicle
     * nearby come first — useful when standing at a stop — and after that
     * shorter names, so "1" precedes "108A".
     */
    fun <T> rank(
        items: List<T>,
        query: String,
        nameOf: (T) -> String,
        isNearby: (T) -> Boolean = { false }
    ): List<T> = items
        .map { it to score(nameOf(it), query) }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<T, Int>> { it.second }
                .thenByDescending { isNearby(it.first) }
                .thenBy { nameOf(it.first).length }
                .thenBy { nameOf(it.first) }
        )
        .map { it.first }
}
