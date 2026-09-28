package com.matt.weather.data

import org.json.JSONObject
import kotlin.math.abs

data class Place(
    val name: String,
    val region: String = "",
    val country: String = "",
    val lat: Double,
    val lon: Double
) {
    /** "Ames, IA" — the state gets abbreviated when we know it. */
    val label: String
        get() = if (region.isNotBlank() && region != name) "$name, ${shortRegion(region)}" else name

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("region", region).put("country", country)
        .put("lat", lat).put("lon", lon)

    companion object {
        val DEFAULT = Place("Ames", "Iowa", "US", 42.0308, -93.6319)

        fun fromJson(o: JSONObject) = Place(
            o.optString("name", "?"),
            o.optString("region", ""),
            o.optString("country", ""),
            o.optDouble("lat", 0.0),
            o.optDouble("lon", 0.0)
        )

        private val STATES = mapOf(
            "Iowa" to "IA", "Texas" to "TX", "Kansas" to "KS", "Missouri" to "MO",
            "Minnesota" to "MN", "Illinois" to "IL", "Nebraska" to "NE", "Wisconsin" to "WI",
            "Colorado" to "CO", "Arizona" to "AZ", "California" to "CA", "Florida" to "FL",
            "New York" to "NY", "Oklahoma" to "OK", "Arkansas" to "AR", "South Dakota" to "SD",
            "North Dakota" to "ND", "Michigan" to "MI", "Indiana" to "IN", "Ohio" to "OH",
            "Tennessee" to "TN", "Kentucky" to "KY", "Georgia" to "GA", "Alabama" to "AL",
            "Mississippi" to "MS", "Louisiana" to "LA", "Montana" to "MT", "Idaho" to "ID",
            "Utah" to "UT", "Nevada" to "NV", "Oregon" to "OR", "Washington" to "WA",
            "Wyoming" to "WY", "New Mexico" to "NM", "Virginia" to "VA", "Maryland" to "MD",
            "Pennsylvania" to "PA", "North Carolina" to "NC", "South Carolina" to "SC"
        )

        fun shortRegion(r: String) = STATES[r] ?: r
    }
}

data class Current(
    val temp: Double,
    val apparent: Double,
    val humidity: Int,
    val isDay: Boolean,
    val code: Int,
    val wind: Double,
    val windDir: Int,
    val gust: Double,
    /** Local wall-clock at the forecast location, "yyyy-MM-ddTHH:mm". */
    val time: String
)

data class Hour(
    val time: String,
    val temp: Double,
    val pop: Int,
    val code: Int,
    val uv: Double,
    val isDay: Boolean
)

data class Day(
    val date: String,
    val code: Int,
    val max: Double,
    val min: Double,
    val sunrise: String,
    val sunset: String,
    val popMax: Int
)

data class Forecast(
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
    val fetchedAt: Long
)

data class Alert(
    val event: String,
    val headline: String,
    val body: String
)

/**
 * One IEM tile layer. [time] is epoch seconds. [stamp] is the HRRR model init
 * ("2026092817") that busts the cache for a layer name reused every run;
 * empty for observed frames, whose layer name already carries the time.
 */
data class RadarFrame(val time: Long, val layer: String, val stamp: String, val future: Boolean) {
    /** Unique per frame content; osmdroid keys its tile cache on this. */
    val cacheKey: String
        get() = "iem_" + (layer + if (stamp.isEmpty()) "" else "_$stamp").replace(Regex("[^A-Za-z0-9]"), "_")
}

data class RadarIndex(val frames: List<RadarFrame>) {
    /** Same layers and cache stamps = same picture; a refresh that returns it changes nothing. */
    val key: String
        get() = frames.joinToString(",") { it.cacheKey }

    val newestObserved: Int
        get() = frames.indexOfLast { !it.future }.coerceAtLeast(0)

    /**
     * Where to stand after the frame list is rebuilt from [old]: parked on the
     * newest observed frame stays parked there; anything else keeps the
     * nearest time.
     */
    fun idxAfterRefresh(old: List<RadarFrame>, oldIdx: Int): Int {
        val was = old.getOrNull(oldIdx)
        if (was == null || oldIdx == RadarIndex(old).newestObserved) return newestObserved
        var best = 0
        frames.forEachIndexed { i, f ->
            if (abs(f.time - was.time) < abs(frames[best].time - was.time)) best = i
        }
        return best
    }

    companion object {
        /**
         * While a new HRRR run is landing, two frames can come from different
         * runs and share a valid time. Keep the newer run's; [RadarFrame.stamp]
         * is the fixed-width init stamp, so a string compare orders it.
         */
        fun dedupeCast(cast: List<RadarFrame>): List<RadarFrame> =
            cast.groupBy { it.time }.values.map { same -> same.maxBy { it.stamp } }.sortedBy { it.time }
    }
}

/** One NEXRAD storm-attribute cell. [drct] is as reported; see StormTracks.heading. */
data class StormCell(
    val lat: Double,
    val lon: Double,
    val dbz: Double,
    val sknt: Double,
    val drct: Double,
    val posh: Double,
    val tvs: String,
    /** Epoch millis of the observation; 0 when missing. */
    val valid: Long
)
