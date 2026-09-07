package com.matt.weather.data

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatting for Open-Meteo's local wall-clock strings ("2026-09-07T14:00").
 * Everything is read straight off the string so a forecast for another time
 * zone still prints that zone's hours. Only [clockDevice] uses the phone clock,
 * and that is for radar frame stamps, which are real instants.
 */
object Fmt {
    fun hourOf(iso: String): Int = iso.substring(11, 13).toIntOrNull() ?: 0

    fun dateOf(iso: String): String = iso.substring(0, 10)

    /** "2p", "12a" */
    fun hour12(iso: String): String {
        val h = hourOf(iso)
        val n = if (h % 12 == 0) 12 else h % 12
        return "$n${if (h < 12) "a" else "p"}"
    }

    /** "6:46am" */
    fun hourMin12(iso: String): String {
        if (iso.length < 16) return "—"
        val h = hourOf(iso)
        val m = iso.substring(14, 16)
        val n = if (h % 12 == 0) 12 else h % 12
        return "$n:$m${if (h < 12) "am" else "pm"}"
    }

    /** "Wed" for a "yyyy-MM-dd" (or longer) string. */
    fun dow(iso: String): String = try {
        LocalDate.parse(iso.substring(0, 10))
            .dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
    } catch (e: Exception) {
        "—"
    }

    /** "1:40pm" in the phone's own time zone. */
    fun clockDevice(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val n = if (h % 12 == 0) 12 else h % 12
        val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        return "$n:$m${if (h < 12) "am" else "pm"}"
    }

    private val CARDINALS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )

    fun cardinal(deg: Int): String {
        val d = ((deg % 360) + 360) % 360
        return CARDINALS[((d / 22.5).roundToInt()) % 16]
    }

    fun temp(v: Double): String = "${v.roundToInt()}°"
}
