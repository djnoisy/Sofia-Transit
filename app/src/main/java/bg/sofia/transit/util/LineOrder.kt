package bg.sofia.transit.util

/**
 * The order of lines by their short names, as the Lines screen lists them
 * (LinesViewModel) and a stop's timetable offers them
 * (GtfsRepository.linesAtStop): natural, so that "2" comes before "11" and
 * "11" before "111", instead of the lexical "1, 11, 12, ..., 2, 21, ...".
 * Each name is split into (letter prefix, number, letter suffix) and compared
 * tuple-wise:
 *   plain numeric first, ascending (1, 2, 3, ..., 11, 12, ..., 280)
 *   then numeric with a letter suffix, grouped by their number (7, 7А)
 *   then names starting with a letter, alphabetically (E186, M1, X50, Д1)
 * Names without a number (rare) come last, by text.
 */
object LineOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val ka = keyFor(a)
        val kb = keyFor(b)
        // First key: 0 = starts with a digit (plain numeric or numeric+suffix),
        // 1 = starts with a letter. Plain numeric group comes first.
        return compareValuesBy(ka, kb, { it.group }, { it.number }, { it.prefix }, { it.suffix })
    }

    private data class SortKey(val group: Int, val prefix: String, val number: Int, val suffix: String)

    private fun keyFor(name: String): SortKey {
        val m = Regex("^([^0-9]*)(\\d+)(.*)$").matchEntire(name)
        return if (m != null) {
            val prefix = m.groupValues[1]
            val number = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            val suffix = m.groupValues[3]
            val group = if (prefix.isEmpty()) 0 else 1
            SortKey(group, prefix, number, suffix)
        } else {
            // No digits at all: sort at the very end alphabetically.
            SortKey(2, name, Int.MAX_VALUE, "")
        }
    }
}
