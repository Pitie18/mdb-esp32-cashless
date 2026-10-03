package xyz.vmflow.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import java.util.Locale

/**
 * "Today" / "Yesterday" / locale-formatted weekday+date, matching iOS
 * `dayLabel(for:)`. Shared by the dashboard activity feed
 * (`DashboardScreen.kt`) and the machine sales feed (`MachineDetailScreen.kt`)
 * so the day-header formatting rule lives in exactly one place.
 */
@Composable
fun dayLabel(date: LocalDate, zone: TimeZone): String {
    val today = remember(zone) { Clock.System.now().toLocalDateTime(zone).date }
    val yesterday = remember(today) { today.minus(1, DateTimeUnit.DAY) }
    return when (date) {
        today -> stringResource(R.string.dashboard_day_today)
        yesterday -> stringResource(R.string.dashboard_day_yesterday)
        else -> {
            // java.time is available unconditionally from minSdk 26 up, no
            // core-library desugaring needed — locale-aware weekday/month
            // formatting without hand-rolling a formatter table.
            val javaDate = java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
            val formatter = java.time.format.DateTimeFormatter.ofPattern(
                "EEEE, d MMMM",
                Locale.getDefault(),
            )
            javaDate.format(formatter)
        }
    }
}
