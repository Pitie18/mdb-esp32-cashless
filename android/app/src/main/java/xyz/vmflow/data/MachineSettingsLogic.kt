package xyz.vmflow.data

import java.net.URI

/**
 * Pure logic ported from `ios/VMflow/Views/Machines/MachineSettingsSheet.swift`
 * for the Machine Settings sheet: `defaultPublicOrigin()` (~L236-249) and the
 * placemark-to-field merge inside `reverseGeocode(_:)` (~L204-215). Kept apart
 * from the Compose file so it's testable without an Android runtime — the
 * only Android-specific pieces (Geocoder, LocationManager) are thin callers
 * around these.
 */

/**
 * Best-effort public frontend URL guess for `/m/{id}`. The API (Kong,
 * default port 8000) and the Nuxt frontend (default port 3000) are separate
 * services in this deployment; there is no reliable way to derive one from
 * the other in general (a production setup may reverse-proxy them under
 * entirely different hosts). This is only a starting guess — always editable
 * in the sheet, and remembered once corrected. Falls back to [apiUrl]
 * unchanged if it can't be parsed as a URL.
 */
fun defaultPublicOrigin(apiUrl: String): String {
    val uri = runCatching { URI(apiUrl) }.getOrNull() ?: return apiUrl
    val scheme = uri.scheme ?: return apiUrl
    val host = uri.host ?: return apiUrl
    val port = if (uri.port == 8000) 3000 else uri.port
    val portSuffix = if (port == -1) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

/** One reverse-geocode result, in the same shape `android.location.Address` exposes (renamed for clarity). */
data class GeocodedPlace(
    val street: String? = null,
    val houseNumber: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val countryCode: String? = null
)

/** The address fields a successful reverse geocode fills into the sheet's state. */
data class ReverseGeocodeResult(
    val street: String?,
    val houseNumber: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String?,
    val formattedAddress: String?
)

/**
 * Merges a [GeocodedPlace] into the sheet's address state: country is only
 * overwritten if it wasn't already set by the user (mirrors iOS's
 * `if countryCode == nil { countryCode = placemark.isoCountryCode }`), and
 * `formattedAddress` is the non-blank parts of house number + street +
 * postal code + city joined with a space (mirrors iOS's `compactMap.joined`).
 */
fun applyGeocodedPlace(place: GeocodedPlace, existingCountryCode: String?): ReverseGeocodeResult {
    val formatted = listOfNotNull(place.houseNumber, place.street, place.postalCode, place.city)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifBlank { null }
    return ReverseGeocodeResult(
        street = place.street,
        houseNumber = place.houseNumber,
        postalCode = place.postalCode,
        city = place.city,
        countryCode = existingCountryCode ?: place.countryCode,
        formattedAddress = formatted
    )
}
