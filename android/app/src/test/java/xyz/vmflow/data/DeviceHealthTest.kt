package xyz.vmflow.data

import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.SuppressedSale

/**
 * Pure logic ported from `ios/VMflow/Views/Machines/DeviceHealthSheet.swift`:
 * `restartReasonLabel(_:)` (~L285-297), `uptimeString(since:)` (~L85-93), and
 * `formatDuration(_:)` (~L303-308), plus the sheet's own day-grouping for the
 * auto-removed-duplicates list.
 */
class DeviceHealthTest {

    private val utc = TimeZone.UTC

    private fun suppressed(id: String, receivedAt: String) = SuppressedSale(
        id = id,
        embeddedId = "e1",
        itemPrice = 2.5,
        receivedAt = receivedAt,
        reason = "brownout_duplicate"
    )

    // ─── parseRestartReason ──────────────────────────────────────────────

    @Test
    fun `every known reason code maps to its own case`() {
        assertEquals(RestartReason.MQTT_WATCHDOG, parseRestartReason("mqtt_watchdog"))
        assertEquals(RestartReason.OTA_UPDATE, parseRestartReason("ota"))
        assertEquals(RestartReason.CONFIG_CHANGE, parseRestartReason("config"))
        assertEquals(RestartReason.PROVISIONING, parseRestartReason("provision"))
        assertEquals(RestartReason.FACTORY_RESET, parseRestartReason("factory_reset"))
        assertEquals(RestartReason.POWER_ON, parseRestartReason("power_on"))
        assertEquals(RestartReason.PANIC, parseRestartReason("panic"))
        assertEquals(RestartReason.BROWNOUT, parseRestartReason("brownout"))
    }

    @Test
    fun `an unrecognised or null reason falls back to unknown`() {
        assertEquals(RestartReason.UNKNOWN, parseRestartReason("something_new"))
        assertEquals(RestartReason.UNKNOWN, parseRestartReason(null))
    }

    // ─── formatUptimeSeconds ─────────────────────────────────────────────

    @Test
    fun `uptime under a day is formatted as hours and minutes`() {
        assertEquals("0h 0m", formatUptimeSeconds(0))
        assertEquals("2h 5m", formatUptimeSeconds(2 * 3600 + 5 * 60))
        assertEquals("23h 59m", formatUptimeSeconds(23 * 3600 + 59 * 60 + 59))
    }

    @Test
    fun `uptime crosses into days only at a full 24h boundary`() {
        assertEquals("1d 0h", formatUptimeSeconds(24 * 3600))
        assertEquals("3d 4h", formatUptimeSeconds(3 * 24 * 3600 + 4 * 3600 + 30 * 60))
    }

    @Test
    fun `a negative duration is clamped to zero`() {
        assertEquals("0h 0m", formatUptimeSeconds(-100))
    }

    // ─── formatDurationSeconds ───────────────────────────────────────────

    @Test
    fun `duration under an hour is formatted as minutes only`() {
        assertEquals("0m", formatDurationSeconds(0))
        assertEquals("45m", formatDurationSeconds(45 * 60))
    }

    @Test
    fun `duration of an hour or more includes the hour component`() {
        assertEquals("1h 0m", formatDurationSeconds(3600))
        assertEquals("2h 30m", formatDurationSeconds(2 * 3600 + 30 * 60))
    }

    // ─── groupSuppressedByDay ────────────────────────────────────────────

    @Test
    fun `rows on the same day land in one group`() {
        val groups = groupSuppressedByDay(
            listOf(
                suppressed("s1", "2026-08-12T08:00:00Z"),
                suppressed("s2", "2026-08-12T20:00:00Z")
            ),
            utc
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].rows.size)
    }

    @Test
    fun `groups are sorted newest day first`() {
        val groups = groupSuppressedByDay(
            listOf(
                suppressed("s1", "2026-08-10T08:00:00Z"),
                suppressed("s2", "2026-08-12T08:00:00Z")
            ),
            utc
        )
        assertEquals(2, groups.size)
        assertTrue(groups[0].date > groups[1].date)
    }

    @Test
    fun `rows within a day are sorted newest first`() {
        val groups = groupSuppressedByDay(
            listOf(
                suppressed("s1", "2026-08-12T08:00:00Z"),
                suppressed("s2", "2026-08-12T20:00:00Z")
            ),
            utc
        )
        assertEquals("s2", groups[0].rows[0].id)
    }

    @Test
    fun `a row with an unparseable timestamp is dropped`() {
        val groups = groupSuppressedByDay(
            listOf(
                suppressed("s1", "not-a-date"),
                suppressed("s2", "2026-08-12T08:00:00Z")
            ),
            utc
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].rows.size)
        assertEquals("s2", groups[0].rows[0].id)
    }
}
