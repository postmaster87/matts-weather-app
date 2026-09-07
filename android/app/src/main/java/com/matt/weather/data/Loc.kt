package com.matt.weather.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object Loc {

    private const val FRESH_MS = 5 * 60 * 1000L

    fun hasProvider(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /** A recent last-known fix if there is one, otherwise a fresh single fix. */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val recent = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < FRESH_MS) return recent

        val provider = providers.firstOrNull {
            it == LocationManager.NETWORK_PROVIDER
        } ?: providers.firstOrNull { it == LocationManager.GPS_PROVIDER } ?: return recent

        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                lm, provider, signal, ContextCompat.getMainExecutor(ctx)
            ) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }
        return fresh ?: recent
    }

    /** Name the fix using the platform geocoder; coordinates still work if it fails. */
    suspend fun describe(ctx: Context, lat: Double, lon: Double): Place = withContext(Dispatchers.IO) {
        val fallback = Place("Current location", "", "", lat, lon)
        if (!Geocoder.isPresent()) return@withContext fallback
        try {
            @Suppress("DEPRECATION")
            val hits = Geocoder(ctx, Locale.US).getFromLocation(lat, lon, 1)
            val a = hits?.firstOrNull() ?: return@withContext fallback
            val city = a.locality ?: a.subAdminArea ?: a.adminArea ?: return@withContext fallback
            Place(city, a.adminArea ?: "", a.countryCode ?: "", lat, lon)
        } catch (e: Exception) {
            fallback
        }
    }
}
