package xyz.vmflow.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.models.MachineWithStats
import xyz.vmflow.models.StockSeverity
import xyz.vmflow.models.TrayDeficit
import xyz.vmflow.models.WarehouseAvailability
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.components.StatusChip
import xyz.vmflow.ui.components.StockHealthBar
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.text.NumberFormat
import java.util.Locale

@Composable
fun MachineCard(
    machineStats: MachineWithStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY).apply {
        currency = java.util.Currency.getInstance("EUR")
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Top row: Name + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = machineStats.machine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(isOnline = machineStats.machine.isOnline)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Revenue row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currencyFormat.format(machineStats.todayRevenue),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column {
                    Text(
                        text = "Sales",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${machineStats.todaySalesCount}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column {
                    Text(
                        text = "Yesterday",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currencyFormat.format(machineStats.yesterdayRevenue),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stock health bar
            if (machineStats.trays.isNotEmpty()) {
                StockHealthBar(trays = machineStats.trays)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Warehouse-availability deficit list: summary badges + per-product rows.
            // Empty/low counts mirror MachineDetailScreen's OverviewTab (isCritical /
            // isLow && !isCritical) rather than MachineDeficits's own severity enum —
            // this task deliberately doesn't touch that existing precedent.
            val emptyTrayCount = machineStats.trays.count { it.isCritical }
            val lowTrayCount = machineStats.trays.count { it.isLow && !it.isCritical }
            if (emptyTrayCount > 0 || lowTrayCount > 0 || machineStats.swapNeededCount > 0 || machineStats.noStockCount > 0) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (emptyTrayCount > 0) {
                        SummaryBadge(
                            text = stringResource(R.string.machine_card_badge_empty, emptyTrayCount),
                            color = StockRed
                        )
                    }
                    if (lowTrayCount > 0) {
                        SummaryBadge(
                            text = stringResource(R.string.machine_card_badge_low, lowTrayCount),
                            color = StockOrange
                        )
                    }
                    if (machineStats.swapNeededCount > 0) {
                        SummaryBadge(
                            text = stringResource(R.string.machine_card_badge_swap, machineStats.swapNeededCount),
                            color = StockOrange
                        )
                    }
                    if (machineStats.noStockCount > 0) {
                        SummaryBadge(
                            text = stringResource(R.string.machine_card_badge_no_stock, machineStats.noStockCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (machineStats.trayDeficits.isNotEmpty()) {
                DeficitRowsSection(machineStats.trayDeficits)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Last sale + pax
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                machineStats.lastSaleAt?.let { dateStr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.height(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTimeAgo(dateStr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (machineStats.paxCount > 0) {
                    Text(
                        text = "${machineStats.paxCount} visitors",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Capsule badge used by the summary row above the deficit list ("N Empty", "N Swap", …). */
@Composable
private fun SummaryBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/** First four rows always visible; the rest expand/collapse behind a "+N more" toggle. */
@Composable
private fun DeficitRowsSection(deficits: List<TrayDeficit>) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) deficits else deficits.take(4)
    val remaining = deficits.size - 4

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEach { deficit -> DeficitRow(deficit) }
        if (remaining > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.machine_card_deficit_show_less)
                    } else {
                        stringResource(R.string.machine_card_deficit_show_more, remaining)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.height(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeficitRow(deficit: TrayDeficit) {
    val isSwap = deficit.warehouseAvailability == WarehouseAvailability.NEEDS_SWAP
    val isNoStock = deficit.warehouseAvailability == WarehouseAvailability.NO_STOCK
    val textColor = when {
        isSwap -> StockOrange
        isNoStock -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> when (deficit.severity) {
            StockSeverity.CRITICAL -> StockRed
            StockSeverity.LOW -> StockOrange
            StockSeverity.FILL_BELOW -> MaterialTheme.colorScheme.primary
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isNoStock) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProductImage(
            imagePath = deficit.imagePath,
            contentDescription = deficit.productName,
            size = 20.dp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = deficit.productName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.machine_card_deficit_amount, deficit.deficit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (deficit.isDiscontinued) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = stringResource(R.string.machine_card_deficit_discontinued),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        when (deficit.warehouseAvailability) {
            WarehouseAvailability.IN_STOCK -> Text(
                text = stringResource(R.string.machine_card_deficit_in_stock),
                style = MaterialTheme.typography.labelSmall,
                color = StockGreen
            )
            WarehouseAvailability.NEEDS_SWAP -> Text(
                text = stringResource(R.string.machine_card_deficit_needs_swap),
                style = MaterialTheme.typography.labelSmall,
                color = StockOrange
            )
            WarehouseAvailability.NO_STOCK -> Text(
                text = stringResource(R.string.machine_card_deficit_no_stock),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WarehouseAvailability.UNKNOWN -> Unit
        }
    }
}

private fun formatTimeAgo(isoString: String): String {
    return try {
        val instant = kotlinx.datetime.Instant.parse(isoString)
        val now = kotlinx.datetime.Clock.System.now()
        val diff = now - instant
        when {
            diff.inWholeMinutes < 1 -> "Just now"
            diff.inWholeMinutes < 60 -> "${diff.inWholeMinutes}m ago"
            diff.inWholeHours < 24 -> "${diff.inWholeHours}h ago"
            diff.inWholeDays < 7 -> "${diff.inWholeDays}d ago"
            else -> isoString.take(10)
        }
    } catch (_: Exception) {
        isoString.take(16)
    }
}
