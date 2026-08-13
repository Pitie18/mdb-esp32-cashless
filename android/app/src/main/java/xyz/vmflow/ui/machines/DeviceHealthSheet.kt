package xyz.vmflow.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import xyz.vmflow.data.RestartReason
import xyz.vmflow.data.formatDurationSeconds
import xyz.vmflow.data.formatUptimeSeconds
import xyz.vmflow.data.groupSuppressedByDay
import xyz.vmflow.data.parseRestartReason
import xyz.vmflow.models.DeviceRestart
import xyz.vmflow.models.Embedded
import xyz.vmflow.models.MdbDiagnostics
import xyz.vmflow.models.MdbLogEntry
import xyz.vmflow.models.SuppressedSale
import xyz.vmflow.models.Tray
import xyz.vmflow.ui.common.dayLabel
import xyz.vmflow.ui.theme.OnlineGreen
import xyz.vmflow.ui.theme.StockRed
import java.text.NumberFormat
import java.util.Locale

/**
 * Device Health + MDB diagnostics, opened from the "monitor heart" toolbar
 * icon on `MachineDetailScreen`. Android counterpart of
 * `ios/VMflow/Views/Machines/DeviceHealthSheet.swift`: uptime, restart
 * history, and the auto-removed-duplicates list are visible to every member;
 * live MDB diagnostics and the MDB state-change history are admin-only,
 * matching the same UI-only gating already used for the Sales tab's restore
 * action.
 *
 * Restart history and the MDB log are fetched only while the sheet is open
 * (a `LaunchedEffect` scoped to this composable, backed by [DeviceHealthViewModel])
 * rather than being folded into `MachineDetailViewModel.loadMachine()`'s
 * eager-load sequence — this data has no other consumer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHealthSheet(
    embedded: Embedded?,
    suppressedSales: List<SuppressedSale>,
    trays: List<Tray>,
    isAdmin: Boolean,
    currencyFormat: NumberFormat,
    onDismiss: () -> Unit,
    viewModel: DeviceHealthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zone = remember { TimeZone.currentSystemDefault() }

    LaunchedEffect(embedded?.id, isAdmin) {
        embedded?.id?.let { viewModel.load(it, isAdmin) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.device_health_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item { UptimeSection(embedded) }
            if (isAdmin) {
                item { MdbDiagnosticsSection(embedded?.mdbDiagnostics) }
            }
            item { RestartHistorySection(uiState.restarts, uiState.isLoadingRestarts, zone) }
            if (isAdmin) {
                item { MdbHistorySection(uiState.mdbLogs, uiState.isLoadingMdbLogs, zone) }
            }
            duplicatesSection(suppressedSales, trays, zone, currencyFormat)
        }
    }
}

// ─── Uptime ──────────────────────────────────────────────────────────────

@Composable
private fun UptimeSection(embedded: Embedded?) {
    SectionCard(title = stringResource(R.string.device_health_section_uptime)) {
        val isOnline = embedded?.isOnline == true
        val since = embedded?.onlineSince ?: embedded?.statusAt

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Green/red (not the app's usual online/offline-gray StatusChip
            // palette) — this dot mirrors the iOS source's own coloring 1:1.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isOnline) OnlineGreen else StockRed)
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (isOnline && since != null) {
                Text(
                    text = formatUptimeSince(since),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = stringResource(R.string.device_health_offline),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val reason = embedded?.lastRestartReason
        val at = embedded?.lastRestartAt
        if (reason != null && at != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.device_health_last_restart_summary,
                    reasonLabel(parseRestartReason(reason)),
                    relativeTimeAgo(at)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun formatUptimeSince(sinceIso: String): String {
    val since = runCatching { Instant.parse(sinceIso) }.getOrNull()
        ?: return stringResource(R.string.device_health_offline)
    val seconds = (Clock.System.now() - since).inWholeSeconds
    return formatUptimeSeconds(seconds)
}

// ─── MDB Status (admin) ────────────────────────────────────────────────

@Composable
private fun MdbDiagnosticsSection(diagnostics: MdbDiagnostics?) {
    SectionCard(title = stringResource(R.string.device_health_section_mdb_status)) {
        if (diagnostics == null) {
            EmptyRow(stringResource(R.string.device_health_no_mdb_diagnostics))
        } else {
            DeviceInfoRow(stringResource(R.string.device_health_mdb_state), mdbStateLabel(diagnostics.state))
            diagnostics.addr?.let { DeviceInfoRow(stringResource(R.string.device_health_mdb_address), it) }
            diagnostics.vmcLevel?.let { DeviceInfoRow(stringResource(R.string.device_health_mdb_vmc_level), it.toString()) }
            DeviceInfoRow(stringResource(R.string.device_health_mdb_polls), (diagnostics.polls ?: 0).toString())
            DeviceInfoRow(stringResource(R.string.device_health_mdb_checksum_errors), (diagnostics.chkErr ?: 0).toString())
            diagnostics.lastCmd?.let { DeviceInfoRow(stringResource(R.string.device_health_mdb_last_command), it) }
        }
    }
}

// ─── Restart history ────────────────────────────────────────────────────

@Composable
private fun RestartHistorySection(restarts: List<DeviceRestart>, isLoading: Boolean, zone: TimeZone) {
    SectionCard(title = stringResource(R.string.device_health_section_restart_history)) {
        when {
            isLoading && restarts.isEmpty() -> LoadingRow()
            restarts.isEmpty() -> EmptyRow(stringResource(R.string.device_health_no_restarts))
            else -> restarts.forEach { restart -> RestartRow(restart, zone) }
        }
    }
}

@Composable
private fun RestartRow(restart: DeviceRestart, zone: TimeZone) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = reasonLabel(parseRestartReason(restart.reason)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = formatDateTime(restart.createdAt, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val uptime = restart.uptimeSec
        val firmware = restart.firmwareVersion
        if (uptime != null || firmware != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                uptime?.let {
                    Text(
                        text = stringResource(R.string.device_health_restart_uptime, formatDurationSeconds(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                firmware?.let {
                    Text(
                        text = "v$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─── MDB state change history (admin) ──────────────────────────────────

@Composable
private fun MdbHistorySection(logs: List<MdbLogEntry>, isLoading: Boolean, zone: TimeZone) {
    SectionCard(title = stringResource(R.string.device_health_section_mdb_state_changes)) {
        when {
            isLoading && logs.isEmpty() -> LoadingRow()
            logs.isEmpty() -> EmptyRow(stringResource(R.string.device_health_no_state_changes))
            else -> logs.forEach { entry -> MdbLogRow(entry, zone) }
        }
    }
}

@Composable
private fun MdbLogRow(entry: MdbLogEntry, zone: TimeZone) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val prev = entry.prevState
            val title = if (prev != null) {
                stringResource(R.string.device_health_state_transition, mdbStateLabel(prev), mdbStateLabel(entry.state))
            } else {
                stringResource(R.string.device_health_state_initial, mdbStateLabel(entry.state))
            }
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatTimeOnly(entry.createdAt, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val cmd = entry.lastCmd
        val polls = entry.polls
        if (cmd != null || polls != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cmd?.let {
                    Text(
                        text = stringResource(R.string.device_health_state_cmd, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                polls?.let {
                    Text(
                        text = pluralStringResource(R.plurals.device_health_polls_count, it, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─── Auto-removed duplicates (read-only here, unlike the Sales tab) ────

private fun LazyListScope.duplicatesSection(
    suppressedSales: List<SuppressedSale>,
    trays: List<Tray>,
    zone: TimeZone,
    currencyFormat: NumberFormat
) {
    item {
        Text(
            text = stringResource(R.string.device_health_section_duplicates),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
    if (suppressedSales.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.device_health_no_duplicates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        val groups = groupSuppressedByDay(suppressedSales, zone)
        groups.forEach { group ->
            item(key = "dh-day-${group.date}") {
                Text(
                    text = dayLabel(group.date, zone),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(items = group.rows, key = { "dh-sup-${it.id}" }) { sale ->
                SuppressedSaleCard(
                    sale = sale,
                    instant = Instant.parse(sale.receivedAt),
                    trays = trays,
                    zone = zone,
                    currencyFormat = currencyFormat,
                    isAdmin = false,
                    onRestoreClick = {}
                )
            }
        }
    }
    item {
        Text(
            text = stringResource(R.string.device_health_duplicates_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Shared row/section building blocks ─────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LoadingRow() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

// ─── Labels ──────────────────────────────────────────────────────────────

@Composable
private fun reasonLabel(reason: RestartReason): String = when (reason) {
    RestartReason.MQTT_WATCHDOG -> stringResource(R.string.device_health_reason_mqtt_watchdog)
    RestartReason.OTA_UPDATE -> stringResource(R.string.device_health_reason_ota)
    RestartReason.CONFIG_CHANGE -> stringResource(R.string.device_health_reason_config)
    RestartReason.PROVISIONING -> stringResource(R.string.device_health_reason_provisioning)
    RestartReason.FACTORY_RESET -> stringResource(R.string.device_health_reason_factory_reset)
    RestartReason.POWER_ON -> stringResource(R.string.device_health_reason_power_on)
    RestartReason.PANIC -> stringResource(R.string.device_health_reason_panic)
    RestartReason.BROWNOUT -> stringResource(R.string.device_health_reason_brownout)
    RestartReason.UNKNOWN -> stringResource(R.string.device_health_unknown)
}

/** `state?.capitalized ?? "Unknown"` — mirrors iOS `stateLabel(_:)` (`DeviceHealthSheet.swift` ~L299-301). */
@Composable
private fun mdbStateLabel(state: String?): String {
    if (state.isNullOrBlank()) return stringResource(R.string.device_health_unknown)
    return state.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

@Composable
private fun relativeTimeAgo(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull()
        ?: return stringResource(R.string.device_health_unknown)
    val diff = (Clock.System.now() - instant)
    val minutes = diff.inWholeMinutes.coerceAtLeast(0)
    val hours = diff.inWholeHours.coerceAtLeast(0)
    val days = diff.inWholeDays.coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.device_health_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.device_health_minutes_ago, minutes.toInt(), minutes.toInt())
        hours < 24 -> pluralStringResource(R.plurals.device_health_hours_ago, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.device_health_days_ago, days.toInt(), days.toInt())
    }
}

@Composable
private fun formatDateTime(iso: String, zone: TimeZone): String {
    return try {
        val ldt = Instant.parse(iso).toLocalDateTime(zone)
        val javaDateTime = java.time.LocalDateTime.of(ldt.year, ldt.monthNumber, ldt.dayOfMonth, ldt.hour, ldt.minute, ldt.second)
        val formatter = java.time.format.DateTimeFormatter
            .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
        javaDateTime.format(formatter)
    } catch (_: Exception) {
        iso
    }
}

@Composable
private fun formatTimeOnly(iso: String, zone: TimeZone): String {
    return try {
        val ldt = Instant.parse(iso).toLocalDateTime(zone)
        val javaTime = java.time.LocalTime.of(ldt.hour, ldt.minute, ldt.second)
        val formatter = java.time.format.DateTimeFormatter
            .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
        javaTime.format(formatter)
    } catch (_: Exception) {
        iso
    }
}
