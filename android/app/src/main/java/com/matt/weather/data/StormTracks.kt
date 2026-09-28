package com.matt.weather.data

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Storm-track geometry: filter, de-duplicate, extrapolate, closest approach.
 * Pure Kotlin — no Android imports — so it can be checked on a plain JVM.
 * Mirrors the storm-track functions in the web build's app.js one for one.
 */
object StormTracks {
    const val KM_PER_KT_HR = 1.852
    private const val R_EARTH_KM = 6371.0

    /**
     * NEXRAD storm attributes give drct as the direction the cell is moving
     * FROM (meteorological convention). Checked against the live feed
     * 2026-09-28: 33 of 35 cells moved along drct + 180 between two
     * snapshots, 0 along drct.
     */
    fun heading(drct: Double): Double = (drct + 180.0) % 360.0

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val s1 = sin(rad(lat2 - lat1) / 2)
        val s2 = sin(rad(lon2 - lon1) / 2)
        val a = s1 * s1 + cos(rad(lat1)) * cos(rad(lat2)) * s2 * s2
        return 2 * R_EARTH_KM * asin(min(1.0, sqrt(a)))
    }

    /** Initial great-circle bearing from point 1 to point 2, 0-360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) -
            sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point reached going [km] along a great circle on bearing [brg]. (lat, lon) */
    fun destPoint(lat: Double, lon: Double, brg: Double, km: Double): Pair<Double, Double> {
        val d = km / R_EARTH_KM
        val b = rad(brg)
        val p1 = rad(lat)
        val l1 = rad(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return deg(p2) to ((deg(l2) + 540.0) % 360.0) - 180.0
    }

    /** Where the cell will be after [minutes]. (lat, lon) */
    fun cellAt(c: StormCell, minutes: Double): Pair<Double, Double> =
        destPoint(c.lat, c.lon, heading(c.drct), c.sknt * KM_PER_KT_HR * minutes / 60.0)

    private val COMPASS8 = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass8(b: Double): String {
        val d = ((b % 360.0) + 360.0) % 360.0
        return COMPASS8[(d / 45.0).roundToInt() % 8]
    }

    fun severe(c: StormCell): Boolean = c.tvs != "NONE" || c.posh >= 50

    /**
     * Moving (>= 5 kt), strong (>= 40 dBZ), within 300 km. Neighbouring radars
     * report the same storm, so within 12 km only the strongest is kept. Then
     * the 40 nearest to the place.
     */
    fun pick(cells: List<StormCell>, lat: Double, lon: Double): List<StormCell> {
        val near = cells
            .filter { it.sknt >= 5 && it.dbz >= 40 }
            .map { it to distKm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= 300 }
            .sortedWith(compareByDescending<Pair<StormCell, Double>> { it.first.dbz }.thenBy { it.second })
        val kept = ArrayList<Pair<StormCell, Double>>()
        for (c in near) {
            if (kept.none { distKm(it.first.lat, it.first.lon, c.first.lat, c.first.lon) < 12 }) kept.add(c)
        }
        return kept.sortedBy { it.second }.take(40).map { it.first }
    }

    data class Approach(val km: Double, val minutes: Double)

    /**
     * Closest the 0-60 minute track gets to the place. Sampled every quarter
     * minute — under 0.5 km of travel between samples at 60 kt.
     */
    fun closestApproach(c: StormCell, lat: Double, lon: Double): Approach {
        var best = Approach(Double.POSITIVE_INFINITY, 0.0)
        var q = 0
        while (q <= 240) {
            val m = q / 4.0
            val p = cellAt(c, m)
            val km = distKm(lat, lon, p.first, p.second)
            if (km < best.km) best = Approach(km, m)
            q++
        }
        return best
    }

    /** [at] is epoch millis; [mi] and [dir] are from the place to the cell now. */
    data class Arrival(val at: Long, val mi: Int, val dir: String, val mph: Int)

    /** The soonest cell whose track passes within 8 km, or null. */
    fun arrival(cells: List<StormCell>, lat: Double, lon: Double, now: Long): Arrival? {
        var best: StormCell? = null
        var bestAt = Long.MAX_VALUE
        for (c in cells) {
            val a = closestApproach(c, lat, lon)
            if (a.km > 8) continue
            val at = (if (c.valid > 0) c.valid else now) + (a.minutes * 60_000).toLong()
            if (at < bestAt) {
                best = c
                bestAt = at
            }
        }
        val c = best ?: return null
        return Arrival(
            at = bestAt,
            mi = (distKm(lat, lon, c.lat, c.lon) / 1.609344).roundToInt(),
            dir = compass8(bearingDeg(lat, lon, c.lat, c.lon)),
            mph = (c.sknt * 1.150779).roundToInt()
        )
    }
}
