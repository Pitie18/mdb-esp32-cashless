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
    MQTT_WATCHDOG, OTA_UPDATE, CONFIG_CHANGE, PROVISIONING, FACTORY_RESET, POWER_ON, PANIC, BROWNOUT, UNKNOWN
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
    else -> RestartReason.UNKNOWN
}

/**
 * "Xd Yh" once uptime reaches a full day, else "Xh Ym". Mirrors iOS
 * `uptimeString(since:)` (`DeviceHealthSheet.swift` ~L85-93): whole hours
 * only roll into the day count once a full 24h has elapsed, so 23h59m of
 * uptime stays "23h 59m", not "1d -1h".
 */
fun formatUptimeSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val totalHours = seconds / 3600
    val days = totalHours / 24
    val hours = totalHours % 24
    if (days > 0) return "${days}d ${hours}h"
    val minutes = (seconds % 3600) / 60
    return "${hours}h ${minutes}m"
}

/**
 * "Xh Ym" once at least an hour, else "Ym". Mirrors iOS `formatDuration(_:)`
 * (`DeviceHealthSheet.swift` ~L303-308) — used for a past restart's prior
 * uptime.
 */
fun formatDurationSeconds(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    if (h > 0) return "${h}h ${m}m"
    return "${m}m"
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
