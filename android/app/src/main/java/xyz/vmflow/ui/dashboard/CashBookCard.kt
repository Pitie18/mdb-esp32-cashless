package xyz.vmflow.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import xyz.vmflow.data.CashBookSummary
import java.text.NumberFormat

/**
 * Dashboard cash-book (Barkasse) summary tile. Mirrors
 * `ios/VMflow/Views/Dashboard/CashBookCard.swift`'s three-stat compact
 * layout (in machines · in box · last bank deposit), but reads from
 * [CashBookSummary] — a thin dashboard-only projection, not the full
 * `CashBookViewModel` pipeline (no Barkasse switcher, no theoretical-cash
 * per-machine breakdown, no writes). There is no Cash Book screen to push to
 * on Android yet, so the tile is informational only, not tappable.
 */
@Composable
fun CashBookCard(
    summary: CashBookSummary,
    currencyFormat: NumberFormat,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = xyz.vmflow.ui.theme.OnlineGreen,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.dashboard_cash_book_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!summary.hasCashBook) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dashboard_cash_book_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    CashBookStat(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.dashboard_cash_book_in_machines),
                        value = currencyFormat.format(summary.cashInMachines),
                    )
                    VerticalDivider(modifier = Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CashBookStat(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        title = stringResource(R.string.dashboard_cash_book_in_box),
                        value = currencyFormat.format(summary.currentBalance),
                    )
                    VerticalDivider(modifier = Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CashBookStat(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.dashboard_cash_book_last_deposit),
                        value = lastDepositText(summary.lastDepositAt),
                    )
                }
            }
        }
    }
}

@Composable
private fun CashBookStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun lastDepositText(lastDepositAt: Instant?): String {
    if (lastDepositAt == null) return stringResource(R.string.dashboard_cash_book_no_deposit)
    val zone = TimeZone.currentSystemDefault()
    val depositDate = lastDepositAt.toLocalDateTime(zone).date
    val today = Clock.System.now().toLocalDateTime(zone).date
    val days = today.toEpochDays() - depositDate.toEpochDays()
    return if (days <= 0) {
        stringResource(R.string.dashboard_cash_book_deposit_today)
    } else {
        pluralStringResource(R.plurals.dashboard_cash_book_deposit_days_ago, days, days)
    }
}
