package com.matt.weather.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
            "&timezone=auto&forecast_days=10"

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

    const val IEM = "https://mesonet.agron.iastate.edu/"
    const val IEM_TILES = IEM + "cache/tile.py/1.0.0/"
    private const val PAST_FRAMES = 10
    private const val PAST_STEP_MIN = 10
    private const val CAST_FRAMES = 12
    private const val HRRR_STEP_MIN = 15
    private const val HRRR_MAX_MIN = 1080

    /** The US composite and HRRR only cover the lower 48 and a margin around it. */
    fun inRadarBox(lat: Double, lon: Double): Boolean =
        lat in 21.0..53.0 && lon in -130.0..-60.0

    private val UTC_MIN = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)

    /** ISO-8601 UTC -> epoch millis, or null. */
    private fun isoMs(s: String?): Long? = try {
        if (s.isNullOrEmpty()) null else Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    private fun hrrrMetaUrl(m: Int) = IEM + "data/gis/images/4326/hrrr/refd_${"%04d".format(m)}.json"

    /**
     * 10 observed NEXRAD composite frames (T0 - 90 min .. T0, 10-minute steps)
     * plus up to 12 HRRR simulated-reflectivity frames after T0.
     */
    suspend fun radar(): RadarIndex {
        val meta = Net.getJson(IEM + "data/gis/images/4326/USCOMP/n0q_0.json", 9_000)
        val t0 = isoMs(meta.optJSONObject("meta")?.optString("valid"))
            ?: throw IllegalStateException("no composite time")
        val frames = ArrayList<RadarFrame>()
        for (k in PAST_FRAMES - 1 downTo 0) {
            val t = t0 - k * PAST_STEP_MIN * 60_000L
            frames.add(RadarFrame(t / 1000, "ridge::USCOMP-N0Q-" + UTC_MIN.format(Instant.ofEpochMilli(t)), "", false))
        }
        frames.addAll(hrrrFrames(t0))
        return RadarIndex(frames)
    }

    /**
     * HRRR forecast frames after [t0]. The model init is usually 1-3 h old, and
     * the refd_*.json files are overwritten one by one as a new run lands, so
     * each frame's own metadata is the only trustworthy time for it.
     */
    private suspend fun hrrrFrames(t0: Long): List<RadarFrame> = try {
        val init = isoMs(Net.getJson(hrrrMetaUrl(0), 9_000).optString("model_init_utc"))
            ?: throw IllegalStateException("no model init")
        val mins = (0..HRRR_MAX_MIN step HRRR_STEP_MIN)
            .filter { init + it * 60_000L > t0 }
            .take(CAST_FRAMES)
        coroutineScope {
            mins.map { m ->
                async {
                    try {
                        val j = Net.getJson(hrrrMetaUrl(m), 9_000)
                        val t = isoMs(j.optString("model_forecast_utc"))
                        val run = isoMs(j.optString("model_init_utc"))
                        if (t == null || run == null || t <= t0) null
                        else RadarFrame(
                            t / 1000, "hrrr::REFD-F${"%04d".format(m)}-0",
                            UTC_MIN.format(Instant.ofEpochMilli(run)).take(10), true
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.time }
    } catch (e: Exception) {
        emptyList() // no model frames: the loop is past-only
    }

    /** NEXRAD storm cells worth drawing for this place (see StormTracks.pick). */
    suspend fun stormCells(lat: Double, lon: Double): List<StormCell> {
        val root = Net.getJson(IEM + "geojson/nexrad_attr.geojson", 12_000)
        val feats = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<StormCell>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val g = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val c = StormCell(
                lat = g.optDouble(1), lon = g.optDouble(0),
                dbz = p.optDouble("max_dbz"), sknt = p.optDouble("sknt"), drct = p.optDouble("drct"),
                posh = p.optDouble("posh", 0.0).let { if (it.isNaN()) 0.0 else it },
                tvs = if (p.isNull("tvs")) "NONE" else p.optString("tvs").ifEmpty { "NONE" },
                valid = isoMs(p.optString("valid")) ?: 0L
            )
            if (listOf(c.lat, c.lon, c.dbz, c.sknt, c.drct).any { it.isNaN() }) continue
            out.add(c)
        }
        return StormTracks.pick(out, lat, lon)
    }
}
