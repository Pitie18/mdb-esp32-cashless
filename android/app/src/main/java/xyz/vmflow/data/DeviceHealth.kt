package xyz.vmflow.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.models.SuppressedSale

/**
 * Reason codes the firmware writes to `embeddeds.last_restart_reason` /
 * `device_restarts.reason`. Mirrors iOS `restartReasonLabel(_:)`
 * (`DeviceHealthSheet.swift` ~L285-297) — this is only the raw-string-to-case
 * mapping; the localized label text lives in string resources
 * (`R.string.device_health_reason_*`) so it stays out of this pure/testable
 * layer, same split as `SlotTier`/`tierLabel()` in `MachineAnalysisView.kt`.
 */
enum class RestartReason {
    MQTT_WATCHDOG, OTA_UPDATE, CONFIG_CHANGE, PROVISIONING, FACTORY_RESET, POWER_ON, PANIC, BROWNOUT, HW_WATCHDOG, UNKNOWN
}

fun parseRestartReason(raw: String?): RestartReason = when (raw) {
    "mqtt_watchdog" -> RestartReason.MQTT_WATCHDOG
    "ota" -> RestartReason.OTA_UPDATE
    "config" -> RestartReason.CONFIG_CHANGE
    "provision" -> RestartReason.PROVISIONING
    "factory_reset" -> RestartReason.FACTORY_RESET
    "power_on" -> RestartReason.POWER_ON
    "panic" -> RestartReason.PANIC
    "brownout" -> RestartReason.BROWNOUT
    // The firmware derives this one from `esp_reset_reason()` (INT_WDT /
    // TASK_WDT / WDT) — mdb-slave-esp32s3.c ~L3128.
    "watchdog" -> RestartReason.HW_WATCHDOG
    else -> RestartReason.UNKNOWN
}

/**
 * "Xs" / "Xm Ys" / "Xh Ym" / "Xd Yh", widening one step per threshold.
 * Mirrors the web's `formatUptime()` (`useDeviceRestarts.ts`) and iOS
 * `formatUptime(_:)` (`DeviceHealthSheet.swift`) byte for byte, so one device
 * never shows three different runtimes across the three clients. Whole hours
 * only roll into the day count once a full 24h has elapsed, so 23h59m of
 * uptime stays "23h 59m", not "1d -1h".
 *
 * Used for both the live uptime and a past restart's prior uptime — the web
 * uses this same single formatter for both.
 */
fun formatUptimeSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    if (seconds < 60) return "${seconds}s"
    if (seconds < 3600) return "${seconds / 60}m ${seconds % 60}s"
    val totalHours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    if (totalHours < 24) return "${totalHours}h ${minutes}m"
    return "${totalHours / 24}d ${totalHours % 24}h"
}

/** One calendar day's worth of auto-removed duplicates, newest first. */
data class SuppressedDayGroup(val date: LocalDate, val rows: List<SuppressedSale>)

/**
 * Groups the raw suppressed-sales list by calendar day — no sales feed or
 * cutoff involved, unlike [SalesFeed.groupByDay] (which anchors suppressed
 * rows to a merged sales feed for the Sales tab). This is the simpler
 * grouping the Device Health sheet's read-only "Auto-Removed Duplicates"
 * section needs. Rows with an unparseable `received_at` are dropped, same
 * policy as [SalesFeed.buildItems].
 */
fun groupSuppressedByDay(rows: List<SuppressedSale>, zone: TimeZone): List<SuppressedDayGroup> {
    val parsed = rows.mapNotNull { row -> parseInstantOrNull(row.receivedAt)?.let { row to it } }
    return parsed
        .groupBy { (_, instant) -> instant.toLocalDateTime(zone).date }
        .toSortedMap(compareByDescending { it })
        .map { (date, entries) ->
            SuppressedDayGroup(date, entries.sortedByDescending { it.second }.map { it.first })
        }
}

private fun parseInstantOrNull(iso: String): Instant? =
    try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }
