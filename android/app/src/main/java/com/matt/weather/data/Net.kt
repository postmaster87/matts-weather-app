package com.matt.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /**
     * api.weather.gov asks callers to identify themselves and throttles generic
     * agents. This names the app and where it lives — deliberately no personal
     * contact details, since these are third-party services.
     */
    const val UA = "MattsWeather/1.0 (Android; +https://github.com/postmaster87/matts-weather-app)"

    suspend fun getString(url: String, timeoutMs: Int = 12_000): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getJson(url: String, timeoutMs: Int = 12_000): JSONObject =
        JSONObject(getString(url, timeoutMs))
}
