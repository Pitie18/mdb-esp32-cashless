package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ExpirationStatus
import xyz.vmflow.models.WarehouseProductSummary
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Stock overview tab: search field, filter chips (out-of-stock / archived /
 * expiration severity) and the product list. Mirrors iOS `stockOverviewTab`
 * + `stockFilterMenu` (`ios/VMflow/Views/Warehouse/WarehouseView.swift`),
 * adapted to Android idiom — a chip row instead of a toolbar `Menu`, since
 * this screen has no existing filter-menu precedent to follow (checked
 * `MachineListScreen.kt`, which only has a plain search field).
 *
 * Batch drilldown ([onProductClick]) is wired through to `WarehouseScreen.kt`,
 * which opens `BatchDrilldownSheet`/`BatchAdjustSheet` (Task 11).
 */
@Composable
fun WarehouseStockTab(
    uiState: WarehouseUiState,
    onSearchChange: (String) -> Unit,
    onToggleOutOfStock: () -> Unit,
    onToggleArchived: () -> Unit,
    onExpirationFilterChange: (ExpirationFilter) -> Unit,
    onProductClick: (productId: String) -> Unit
) {
    val filtered = uiState.filteredSummaries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            OutlinedTextField(
                value = uiState.searchText,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.warehouse_stock_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.warehouse_stock_search_clear))
                        }
                    }
                },
                singleLine = true
            )
        }

        item {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.includeOutOfStock,
                    onClick = onToggleOutOfStock,
                    label = { Text(stringResource(R.string.warehouse_stock_out_of_stock)) }
                )
                FilterChip(
                    selected = uiState.includeArchived,
                    onClick = onToggleArchived,
                    label = { Text(stringResource(R.string.warehouse_stock_filter_archived)) }
                )
                FilterChip(
                    selected = uiState.expirationFilter == ExpirationFilter.EXPIRING_SOON,
                    onClick = {
                        onExpirationFilterChange(
                            if (uiState.expirationFilter == ExpirationFilter.EXPIRING_SOON) ExpirationFilter.ALL
                            else ExpirationFilter.EXPIRING_SOON
                        )
                    },
                    label = { Text(stringResource(R.string.warehouse_stock_filter_expiring_soon)) }
                )
                FilterChip(
                    selected = uiState.expirationFilter == ExpirationFilter.CRITICAL,
                    onClick = {
                        onExpirationFilterChange(
                            if (uiState.expirationFilter == ExpirationFilter.CRITICAL) ExpirationFilter.ALL
                            else ExpirationFilter.CRITICAL
                        )
                    },
                    label = { Text(stringResource(R.string.warehouse_stock_filter_critical)) }
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.productSummaries.isEmpty()) {
                            stringResource(R.string.warehouse_stock_empty_no_stock)
                        } else if (uiState.searchText.isNotEmpty()) {
                            stringResource(R.string.warehouse_stock_empty_search, uiState.searchText)
                        } else {
                            stringResource(R.string.warehouse_stock_empty_filtered)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filtered, key = { it.productId }) { summary ->
                WarehouseStockRow(
                    summary = summary,
                    onClick = { onProductClick(summary.productId) }
                )
            }
        }
    }
}

/**
 * One product row: image, name (+ "DC" badge when discontinued), batch
 * count + expiration badge, total quantity (red when out of stock) with a
 * "Low"/"Out of Stock" pill. Mirrors iOS `StockSummaryRow`.
 */
@Composable
private fun WarehouseStockRow(
    summary: WarehouseProductSummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (summary.discontinued) 0.55f else 1f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProductImage(
            imagePath = summary.imagePath,
            contentDescription = summary.productName,
            size = 48.dp
        )
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.productName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (summary.discontinued) {
                    Spacer(modifier = Modifier.width(6.dp))
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
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluralStringResource(R.plurals.warehouse_batch_count, summary.batchCount, summary.batchCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val expirationColor = when (summary.expirationStatus) {
                    ExpirationStatus.CRITICAL -> StockRed
                    ExpirationStatus.WARNING -> StockOrange
                    ExpirationStatus.OK -> null
                }
                val expirationDate = summary.earliestExpiration
                if (expirationColor != null && expirationDate != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ExpirationBadge(dateIso = expirationDate, color = expirationColor)
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = summary.totalQuantity.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (summary.isOutOfStock) StockRed else MaterialTheme.colorScheme.onSurface
            )
            if (summary.isOutOfStock) {
                StockStatusPill(text = stringResource(R.string.warehouse_stock_out_of_stock), color = StockRed)
            } else if (summary.isLow) {
                StockStatusPill(text = stringResource(R.string.warehouse_stock_low), color = StockOrange)
            }
        }
    }
}

@Composable
private fun ExpirationBadge(dateIso: String, color: Color) {
    val formatted = remember(dateIso) { formatExpirationDate(dateIso) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = formatted,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

/** Parses a `yyyy-MM-dd` date and renders it in the device's short locale format; falls back to the raw string if it doesn't parse. */
private fun formatExpirationDate(dateIso: String): String {
    return try {
        val date = java.time.LocalDate.parse(dateIso)
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    } catch (_: Exception) {
        dateIso
    }
}

@Composable
private fun StockStatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
