package bg.sofia.transit.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import bg.sofia.transit.R
import bg.sofia.transit.databinding.ViewLineBadgeBinding
import bg.sofia.transit.util.LineColors
import com.google.android.material.color.MaterialColors

/**
 * A line's badge (view_line_badge.xml): the vehicle's icon and the line's
 * number on the line's official colour (LineColors), outlined as the
 * theme outlines cards. Shown, not spoken: the row it is in carries the
 * whole description for TalkBack, as before.
 */
object LineBadge {

    /**
     * [label] is the vehicle as the app names it (VehicleLabels.singular,
     * or the arrivals' vehicle type): "Трамвай", "Тролей", "Метро" have
     * their own icons; buses, electric buses and anything else the bus's.
     */
    fun bind(b: ViewLineBadgeBinding, label: String?, routeId: String, shortName: String,
             dataColor: String? = null) {
        val rgb = LineColors.of(routeId, shortName, dataColor)
        val content = if (LineColors.darkContent(rgb)) Color.BLACK else Color.WHITE
        val ctx = b.root.context
        val width = TypedValue().let { tv ->
            if (ctx.theme.resolveAttribute(R.attr.lineDividerWidth, tv, true))
                tv.getDimension(ctx.resources.displayMetrics) else ctx.resources.displayMetrics.density
        }
        b.root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * ctx.resources.displayMetrics.density
            setColor(Color.rgb(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF))
            setStroke(width.toInt().coerceAtLeast(1),
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOutline))
        }
        b.ivBadgeIcon.setImageResource(iconFor(label, routeId))
        b.ivBadgeIcon.imageTintList = ColorStateList.valueOf(content)
        b.tvBadgeNumber.text = shortName
        b.tvBadgeNumber.setTextColor(content)
    }

    private fun iconFor(label: String?, routeId: String): Int {
        val l = label?.lowercase().orEmpty()
        return when {
            l.startsWith("трамвай") -> R.drawable.ic_vehicle_tram
            l.startsWith("тролей") -> R.drawable.ic_vehicle_trolley
            l.startsWith("метро") -> R.drawable.ic_vehicle_metro
            label == null && routeId.startsWith("TM") -> R.drawable.ic_vehicle_tram
            label == null && routeId.startsWith("M") -> R.drawable.ic_vehicle_metro
            else -> R.drawable.ic_vehicle_bus
        }
    }
}
