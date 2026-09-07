package com.matt.weather.data

import org.json.JSONObject

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

data class RadarFrame(val time: Long, val path: String, val future: Boolean)

data class RadarIndex(val host: String, val frames: List<RadarFrame>)
