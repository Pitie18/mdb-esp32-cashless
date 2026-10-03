package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure logic ported from `ios/VMflow/Views/Machines/MachineSettingsSheet.swift`:
 * `defaultPublicOrigin()` (~L236-249) and the placemark-to-field merge inside
 * `reverseGeocode(_:)` (~L204-215).
 */
class MachineSettingsLogicTest {

    // ─── defaultPublicOrigin ─────────────────────────────────────────────

    @Test
    fun `port 8000 is swapped for the frontend's default port 3000`() {
        assertEquals("http://10.0.1.146:3000", defaultPublicOrigin("http://10.0.1.146:8000"))
    }

    @Test
    fun `a non-8000 port is left untouched`() {
        assertEquals("http://10.0.1.146:54321", defaultPublicOrigin("http://10.0.1.146:54321"))
    }

    @Test
    fun `no explicit port stays without one`() {
        assertEquals("https://supabase.vmflow.xyz", defaultPublicOrigin("https://supabase.vmflow.xyz"))
    }

    @Test
    fun `a trailing path is dropped, only scheme plus host plus port remain`() {
        assertEquals("http://10.0.1.146:3000", defaultPublicOrigin("http://10.0.1.146:8000/some/path"))
    }

    @Test
    fun `an unparseable URL is returned unchanged rather than throwing`() {
        val garbage = "not a url at all :://"
        assertEquals(garbage, defaultPublicOrigin(garbage))
    }

    // ─── applyGeocodedPlace ──────────────────────────────────────────────

    @Test
    fun `country is filled from the placemark only when not already set`() {
        val place = GeocodedPlace(countryCode = "DE")
        assertEquals("DE", applyGeocodedPlace(place, existingCountryCode = null).countryCode)
        assertEquals("FR", applyGeocodedPlace(place, existingCountryCode = "FR").countryCode)
    }

    @Test
    fun `formatted address joins house number, street, postal code and city with spaces`() {
        val place = GeocodedPlace(
            street = "Hauptstraße",
            houseNumber = "12",
            postalCode = "80331",
            city = "München",
            countryCode = "DE"
        )
        val result = applyGeocodedPlace(place, existingCountryCode = null)
        assertEquals("12 Hauptstraße 80331 München", result.formattedAddress)
    }

    @Test
    fun `missing or blank fields are skipped when joining the formatted address`() {
        val place = GeocodedPlace(street = "Hauptstraße", houseNumber = null, postalCode = "  ", city = "München")
        val result = applyGeocodedPlace(place, existingCountryCode = null)
        assertEquals("Hauptstraße München", result.formattedAddress)
    }

    @Test
    fun `an entirely empty placemark produces a null formatted address`() {
        val result = applyGeocodedPlace(GeocodedPlace(), existingCountryCode = null)
        assertNull(result.formattedAddress)
        assertNull(result.street)
        assertNull(result.houseNumber)
        assertNull(result.postalCode)
        assertNull(result.city)
        assertNull(result.countryCode)
    }
}
