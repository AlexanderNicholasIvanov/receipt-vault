package com.alexivanov.receiptvault.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class Place(val latitude: Double, val longitude: Double, val label: String?)

/** Framework location + geocoding. Every failure is a quiet null: location is a nice-to-have. */
class LocationService(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Current position with a reverse-geocoded label, or null when unavailable within a few seconds. */
    suspend fun currentPlace(): Place? {
        val location = currentLocation() ?: return null
        return Place(location.latitude, location.longitude, reverseGeocode(location.latitude, location.longitude))
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val address = geocoderCall { listener -> Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1, listener) }
        return address?.let { a ->
            a.getAddressLine(0) ?: listOfNotNull(a.thoroughfare, a.locality, a.adminArea).joinToString(", ").ifBlank { null }
        }
    }

    /** Coordinates for an address printed on a receipt. */
    suspend fun geocode(query: String): Place? {
        if (!Geocoder.isPresent() || query.isBlank()) return null
        val address = geocoderCall { listener -> Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1, listener) }
        return address?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { Place(it.latitude, it.longitude, it.getAddressLine(0)) }
    }

    private suspend fun geocoderCall(start: (Geocoder.GeocodeListener) -> Unit): Address? = withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses.firstOrNull())
                }

                override fun onError(errorMessage: String?) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                start(listener)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
