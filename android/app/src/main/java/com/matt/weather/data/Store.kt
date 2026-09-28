package com.matt.weather.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Last place, recent places, and the last forecast payload. */
class Store(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences("wx", Context.MODE_PRIVATE)

    var place: Place
        get() = try {
            prefs.getString(KEY_PLACE, null)?.let { Place.fromJson(JSONObject(it)) } ?: Place.DEFAULT
        } catch (e: Exception) {
            Place.DEFAULT
        }
        set(value) {
            prefs.edit().putString(KEY_PLACE, value.toJson().toString()).apply()
        }

    var recents: List<Place>
        get() = try {
            val a = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
            (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        private set(value) {
            val a = JSONArray()
            value.forEach { a.put(it.toJson()) }
            prefs.edit().putString(KEY_RECENTS, a.toString()).apply()
        }

    /** Length of the daily card: 10 or 5. */
    var dayCount: Int
        get() = if (prefs.getInt(KEY_DAYS, 10) == 5) 5 else 10
        set(value) {
            prefs.edit().putInt(KEY_DAYS, value).apply()
        }

    fun pushRecent(p: Place) {
        val kept = recents.filterNot { abs(it.lat - p.lat) < 0.02 && abs(it.lon - p.lon) < 0.02 }
        recents = (listOf(p) + kept).take(6)
    }

    fun saveForecast(p: Place, raw: String) {
        prefs.edit()
            .putString(KEY_CACHE, raw)
            .putString(KEY_CACHE_PLACE, p.toJson().toString())
            .putLong(KEY_CACHE_AT, System.currentTimeMillis())
            .apply()
    }

    /** Cached payload, but only if it belongs to this place. */
    fun cachedFor(p: Place): Pair<Forecast, Long>? {
        val raw = prefs.getString(KEY_CACHE, null) ?: return null
        val cp = prefs.getString(KEY_CACHE_PLACE, null) ?: return null
        return try {
            val cached = Place.fromJson(JSONObject(cp))
            if (abs(cached.lat - p.lat) > 0.02 || abs(cached.lon - p.lon) > 0.02) return null
            val at = prefs.getLong(KEY_CACHE_AT, 0L)
            WeatherApi.parseForecast(raw) to at
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_PLACE = "place"
        const val KEY_RECENTS = "recents"
        const val KEY_DAYS = "days"
        const val KEY_CACHE = "cache"
        const val KEY_CACHE_PLACE = "cache_place"
        const val KEY_CACHE_AT = "cache_at"
    }
}
