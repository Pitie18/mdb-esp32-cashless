package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import xyz.vmflow.data.ExpirationStatus
import xyz.vmflow.data.WarehouseIntakeLogic
import xyz.vmflow.models.WarehouseStockBatch
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Batch drilldown sheet: lists every in-stock batch of one product in the
 * currently selected warehouse, tapping a row opens [BatchAdjustSheet] for
 * that batch (via [onAdjust]). Mirrors iOS `ProductBatchesView`
 * (`ios/VMflow/Views/Warehouse/ProductBatchesView.swift`), adapted from a
 * `NavigationStack` push (iOS) to a `ModalBottomSheet` (Android has no
 * dedicated navigation destination for this drilldown) — same convention as
 * `AddEditServerSheet.kt`/`QrScannerSheet.kt`.
 *
 * [batches] is expected already sorted oldest-expiration-first by the
 * caller's data source (`WarehouseRepository.fetchBatchesForProduct` orders
 * by `expiration_date ASC` server-side) — not re-sorted here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDrilldownSheet(
    productName: String,
    batches: List<WarehouseStockBatch>,
    isLoading: Boolean,
    onAdjust: (batchId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = productName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading && batches.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.warehouse_batch_drilldown_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                batches.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.warehouse_batch_drilldown_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(batches, key = { it.id }) { batch ->
                            BatchDrilldownRow(
                                batch = batch,
                                today = today,
                                onClick = { onAdjust(batch.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One batch row: batch number (or placeholder), expiration date (color-coded by severity), quantity. */
@Composable
private fun BatchDrilldownRow(
    batch: WarehouseStockBatch,
    today: LocalDate,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = batch.batchNumber?.takeIf { it.isNotBlank() } ?: stringResource(R.string.warehouse_batch_no_number),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val expirationDate = batch.expirationDate
            if (expirationDate != null) {
                val status = remember(expirationDate, today) {
                    WarehouseIntakeLogic.expirationStatus(expirationDate, today)
                }
                val color = when (status) {
                    ExpirationStatus.CRITICAL -> StockRed
                    ExpirationStatus.WARNING -> StockOrange
                    ExpirationStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatBatchDate(expirationDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = batch.quantity.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Parses a `yyyy-MM-dd` date and renders it in the device's short locale format; falls back to the raw string if it doesn't parse. */
private fun formatBatchDate(dateIso: String): String {
    return try {
        val date = java.time.LocalDate.parse(dateIso)
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    } catch (_: Exception) {
        dateIso
    }
}
