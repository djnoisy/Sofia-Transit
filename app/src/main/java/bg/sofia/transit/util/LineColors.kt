package bg.sofia.transit.util

/**
 * The colour of a line's badge (redesign, stage b, 8 Nov 2026): the city's
 * official colours, as the static data gives them (route_color) — buses red,
 * night buses black, some of the X lines green, trams orange, trolleybuses
 * and electric buses light blue, each metro line its own. The lists take it
 * from the data (GtfsRepository.getRouteColors); only while that is not at
 * hand, by the line's id and number — right for all but the green X lines,
 * then red (X1, X9 and X10 are red in the data, X43 and X50 green). The
 * text and icon on it: black or white,
 * whichever stands out more — the data's white on orange or light blue was
 * 2.3-2.7:1.
 *
 * Pure Kotlin, no Android types (tools/test-harness/run_line_color_tests.sh).
 */
object LineColors {
    const val BUS = 0xBE1E2D
    const val NIGHT_BUS = 0x000000
    const val TRAM = 0xF7941D
    const val TROLLEY = 0x27AAE1
    /** Metro lines by route id; another metro line takes M2's blue. */
    val METRO = mapOf("M1" to 0xEB2227, "M2" to 0x1C75BC, "M3" to 0x39B54A, "M4" to 0xFFDE17)

    /**
     * The badge colour, 0xRRGGBB: [dataColor] ("RRGGBB" or "#RRGGBB" from the
     * static data) when it is a colour, else by [routeId] and [shortName].
     */
    fun of(routeId: String, shortName: String, dataColor: String? = null): Int {
        parse(dataColor)?.let { return it }
        return when {
            routeId.startsWith("TM") -> TRAM
            routeId.startsWith("TB") -> TROLLEY
            routeId.startsWith("M") -> METRO[routeId] ?: METRO.getValue("M2")
            shortName.startsWith("N") -> NIGHT_BUS
            else -> BUS
        }
    }

    /** "RRGGBB" or "#RRGGBB" as 0xRRGGBB; null if it is not one. */
    fun parse(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        return h.toIntOrNull(16)
    }

    /** True: black on [rgb] stands out more than white. */
    fun darkContent(rgb: Int): Boolean = contrast(rgb, 0x000000) >= contrast(rgb, 0xFFFFFF)

    /** WCAG contrast ratio of two colours, 0xRRGGBB. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun ch(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }
}
