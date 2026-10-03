package xyz.vmflow.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Bound on the pre-API-30 `requestLocationUpdates` fallback below — the API
 * 30+ `getCurrentLocation` path is already bounded by the OS's own internal
 * default (~30s) via its `CancellationSignal`; this mirrors that so a weak
 * signal on an older device can't hang the "locating" spinner forever.
 */
private const val LEGACY_LOCATION_TIMEOUT_MS = 30_000L

/**
 * One-shot current-location fetch, wrapped as a suspend function. Android
 * counterpart of iOS's `OneShotLocationFetcher` (`MachineSettingsSheet.swift`
 * ~L264-316) — not a long-lived tracker, placing a machine only needs a
 * single fix. No `play-services-location` dependency: uses the platform
 * `LocationManager`, `getCurrentLocation` on API 30+ and a one-shot
 * `requestLocationUpdates` (immediately removed) below that, both usable
 * back to `minSdk 26`.
 *
 * Callers must have already confirmed `ACCESS_FINE_LOCATION` is granted —
 * this function does not request it.
 */
@SuppressLint("MissingPermission")
suspend fun fetchOneShotLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val provider = when {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    } ?: return null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    }

    // Pre-API-30 fallback: `requestLocationUpdates` has no built-in timeout,
    // so a provider that never produces a fix (weak signal, etc.) would
    // otherwise hang this coroutine forever. `withTimeoutOrNull` cancels the
    // inner `suspendCancellableCoroutine` on timeout, which runs the same
    // `invokeOnCancellation` cleanup (`removeUpdates`) as any other
    // cancellation path, then resolves to `null` here — same "no fix
    // obtained" contract as the API 30+ path already has.
    return withTimeoutOrNull(LEGACY_LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }
}

/**
 * Reverse-geocodes a coordinate via the platform `Geocoder` (no paid API, no
 * Maps key). Best-effort: returns `null` on any failure (no service
 * available, no result, `IOException`) — the caller keeps the coordinate
 * regardless, mirroring iOS's `try?` in `reverseGeocode(_:)`. Runs the
 * synchronous `getFromLocation` overload (works down to API 26, unlike the
 * listener-based one added in API 33) off the main thread.
 */
@Suppress("DEPRECATION")
suspend fun reverseGeocodeCoordinate(context: Context, lat: Double, lon: Double): GeocodedPlace? =
    withContext(Dispatchers.IO) {
        val address: Address? = try {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        address?.let {
            GeocodedPlace(
                street = it.thoroughfare,
                houseNumber = it.subThoroughfare,
                postalCode = it.postalCode,
                city = it.locality,
                countryCode = it.countryCode
            )
        }
    }
