package com.matt.weather.data

import org.json.JSONObject
import java.net.URLEncoder

object WeatherApi {

    fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.4f".format(lat)}&longitude=${"%.4f".format(lon)}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
            "weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,uv_index,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset," +
            "precipitation_probability_max" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=auto&forecast_days=7"

    suspend fun forecast(lat: Double, lon: Double): Forecast =
        parseForecast(Net.getString(forecastUrl(lat, lon)))

    /**
     * All times arrive as local-to-the-forecast wall clock ("2026-09-07T14:00")
     * because of timezone=auto. They are kept as strings and formatted from the
     * string — routing them through the phone's clock would shift every label
     * whenever the forecast is for another time zone.
     */
    fun parseForecast(raw: String): Forecast {
        val root = JSONObject(raw)

        val c = root.getJSONObject("current")
        val current = Current(
            temp = c.optDouble("temperature_2m", 0.0),
            apparent = c.optDouble("apparent_temperature", 0.0),
            humidity = c.optInt("relative_humidity_2m", 0),
            isDay = c.optInt("is_day", 1) == 1,
            code = c.optInt("weather_code", 3),
            wind = c.optDouble("wind_speed_10m", 0.0),
            windDir = c.optInt("wind_direction_10m", 0),
            gust = c.optDouble("wind_gusts_10m", 0.0),
            time = c.optString("time", "")
        )

        val h = root.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hTemp = h.getJSONArray("temperature_2m")
        val hPop = h.getJSONArray("precipitation_probability")
        val hCode = h.getJSONArray("weather_code")
        val hUv = h.getJSONArray("uv_index")
        val hDay = h.getJSONArray("is_day")
        val hours = ArrayList<Hour>(ht.length())
        for (i in 0 until ht.length()) {
            hours.add(
                Hour(
                    time = ht.getString(i),
                    temp = hTemp.optDouble(i, 0.0),
                    pop = hPop.optInt(i, 0),
                    code = hCode.optInt(i, 3),
                    uv = hUv.optDouble(i, 0.0),
                    isDay = hDay.optInt(i, 1) == 1
                )
            )
        }

        val d = root.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val dCode = d.getJSONArray("weather_code")
        val dMax = d.getJSONArray("temperature_2m_max")
        val dMin = d.getJSONArray("temperature_2m_min")
        val dRise = d.getJSONArray("sunrise")
        val dSet = d.getJSONArray("sunset")
        val dPop = d.getJSONArray("precipitation_probability_max")
        val days = ArrayList<Day>(dt.length())
        for (i in 0 until dt.length()) {
            days.add(
                Day(
                    date = dt.getString(i),
                    code = dCode.optInt(i, 3),
                    max = dMax.optDouble(i, 0.0),
                    min = dMin.optDouble(i, 0.0),
                    sunrise = dRise.optString(i, ""),
                    sunset = dSet.optString(i, ""),
                    popMax = dPop.optInt(i, 0)
                )
            )
        }

        return Forecast(current, hours, days, System.currentTimeMillis())
    }

    /** NWS active alerts for the exact point. US-only; empty everywhere else. */
    suspend fun alerts(lat: Double, lon: Double): List<Alert> {
        val url = "https://api.weather.gov/alerts/active" +
            "?point=${"%.4f".format(lat)},${"%.4f".format(lon)}"
        val root = Net.getJson(url, 9_000)
        val feats = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Alert>()
        for (i in 0 until minOf(feats.length(), 3)) {
            val p = feats.getJSONObject(i).optJSONObject("properties") ?: continue
            out.add(
                Alert(
                    event = p.optString("event", "Weather alert"),
                    headline = p.optString("headline", ""),
                    body = p.optString("description", "").trim()
                )
            )
        }
        return out
    }

    suspend fun search(query: String): List<Place> {
        if (query.trim().length < 2) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = Net.getJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json",
            8_000
        )
        val res = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            out.add(
                Place(
                    name = o.optString("name", "?"),
                    region = o.optString("admin1", ""),
                    country = o.optString("country", ""),
                    lat = o.optDouble("latitude", 0.0),
                    lon = o.optDouble("longitude", 0.0)
                )
            )
        }
        return out
    }

    /** Past frames (~1 h) plus RainViewer's 30-minute nowcast. */
    suspend fun radar(): RadarIndex {
        val root = Net.getJson("https://api.rainviewer.com/public/weather-maps.json", 9_000)
        val host = root.optString("host", "https://tilecache.rainviewer.com")
        val radar = root.optJSONObject("radar") ?: return RadarIndex(host, emptyList())
        val frames = ArrayList<RadarFrame>()

        val past = radar.optJSONArray("past")
        if (past != null) {
            val from = maxOf(0, past.length() - 10)
            for (i in from until past.length()) {
                val o = past.getJSONObject(i)
                frames.add(RadarFrame(o.optLong("time"), o.optString("path"), false))
            }
        }
        val cast = radar.optJSONArray("nowcast")
        if (cast != null) {
            for (i in 0 until cast.length()) {
                val o = cast.getJSONObject(i)
                frames.add(RadarFrame(o.optLong("time"), o.optString("path"), true))
            }
        }
        return RadarIndex(host, frames)
    }
}
