package com.matt.weather.data

/** WMO weather codes -> the icon we draw and the words we print. */
enum class Sky {
    CLEAR, MOSTLY_CLEAR, PARTLY, OVERCAST, CLOUDY, FOG,
    DRIZZLE, RAIN, HEAVY_RAIN, SLEET, SNOW, STORM
}

object Wmo {
    fun sky(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1 -> Sky.MOSTLY_CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.OVERCAST
        45, 48 -> Sky.FOG
        51, 53, 55 -> Sky.DRIZZLE
        56, 57 -> Sky.SLEET
        61, 80 -> Sky.RAIN
        63, 81 -> Sky.RAIN
        65, 82 -> Sky.HEAVY_RAIN
        66, 67 -> Sky.SLEET
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 80 -> "Light rain"
        63, 81 -> "Rain"
        65, 82 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 85 -> "Light snow"
        73 -> "Snow"
        75, 86 -> "Heavy snow"
        77 -> "Snow grains"
        95 -> "Thunderstorms"
        96, 99 -> "Storms, hail"
        else -> "Cloudy"
    }
}
