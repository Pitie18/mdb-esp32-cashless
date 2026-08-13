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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.ui.theme.OnlineGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.text.NumberFormat

/**
 * Dense period-over-period revenue tile: current period on top, previous
 * period below a divider. Mirrors `RevenueKPICard` in
 * `ios/VMflow/Views/Dashboard/DashboardView.swift`, minus the SwiftUI
 * `minimumScaleFactor` shrink-to-fit (Compose has no direct equivalent;
 * `maxLines` + `TextOverflow.Ellipsis` is the pragmatic substitute).
 *
 * Fixed [width] (not `Modifier.width(IntrinsicSize.Min)`) so every card in
 * the row is identically sized regardless of content — the clipped-card bug
 * this replaces came from a `LazyRow` whose card widths didn't agree with
 * its `contentPadding`, not from intrinsic sizing itself.
 *
 * [width] is tuned (not the visually "nicer" round number) so the row's
 * `KpiCardRow` `contentPadding`/`spacedBy` leaves only a sub-12dp peek of
 * the next card at typical phone widths — every text row starts flush at
 * the card's own internal padding with no leading icon offset, so any
 * peek wider than that reveals a partial digit rather than a clean card
 * edge. Verified on a 411dp-wide device (Galaxy S10); re-check this value
 * if `KpiCardRow`'s padding/spacing changes.
 */
@Composable
fun RevenueKpiCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    currentRevenue: Double,
    currentSales: Int,
    previousLabel: String,
    previousRevenue: Double,
    previousSales: Int,
    tint: androidx.compose.ui.graphics.Color,
    currencyFormat: NumberFormat,
    width: androidx.compose.ui.unit.Dp = 178.dp,
) {
    Card(
        modifier = Modifier
            .width(width)
            .height(148.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.height(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currencyFormat.format(currentRevenue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.dashboard_sales_count, currentSales, currentSales),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = previousLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${currencyFormat.format(previousRevenue)} · " +
                    pluralStringResource(R.plurals.dashboard_sales_count, previousSales, previousSales),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Stock + machines-online tile, same footprint as [RevenueKpiCard]. Mirrors
 * `StockAlertsCard` on iOS: top half is the alert summary, bottom half is
 * the online/total machine count.
 */
@Composable
fun StockAlertsKpiCard(
    critical: Int,
    low: Int,
    machinesOnline: Int,
    machinesTotal: Int,
    width: androidx.compose.ui.unit.Dp = 178.dp,
) {
    val allClear = critical == 0 && low == 0
    val alertTint = when {
        critical > 0 -> StockRed
        low > 0 -> StockOrange
        else -> OnlineGreen
    }
    Card(
        modifier = Modifier
            .width(width)
            .height(148.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = null,
                    tint = alertTint,
                    modifier = Modifier.height(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.dashboard_kpi_stock_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (allClear) {
                Text(
                    text = stringResource(R.string.dashboard_kpi_stock_ok),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnlineGreen,
                )
                Text(
                    text = stringResource(R.string.dashboard_kpi_stock_all_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = pluralStringResource(
                        R.plurals.dashboard_alerts_count,
                        critical + low,
                        critical + low,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = alertTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (critical > 0) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = StockRed,
                            modifier = Modifier.height(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "$critical",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (low > 0) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = StockOrange,
                            modifier = Modifier.height(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "$low",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = stringResource(R.string.dashboard_machines_online),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (machinesOnline == machinesTotal) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (machinesOnline == machinesTotal) OnlineGreen else StockRed,
                    modifier = Modifier.height(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$machinesOnline / $machinesTotal",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared icon set so [Icons.Filled.WbSunny] etc. don't need importing at each call site. */
object DashboardKpiIcons {
    val Today = Icons.Filled.WbSunny
    val Week = Icons.Filled.CalendarViewWeek
    val Month = Icons.Filled.CalendarMonth
}
