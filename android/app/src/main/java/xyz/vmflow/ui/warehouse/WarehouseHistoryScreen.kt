package xyz.vmflow.ui.warehouse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.vmflow.R
import xyz.vmflow.data.WarehouseHistoryEntry
import xyz.vmflow.data.WarehouseRepository
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class HistoryFilter(val label: Int, val types: List<String>?) {
    ALL(R.string.warehouse_history_filter_all, null),
    INCOMING(R.string.warehouse_tab_incoming, listOf("intake", "incoming", "transfer_in")),
    REFILL(R.string.warehouse_history_type_refill, listOf("outgoing_refill", "adjustment_refill_return")),
    CORRECTIONS(
        R.string.warehouse_history_filter_corrections,
        listOf("adjustment_correction", "adjustment_damage", "adjustment_expired", "transfer_out")
    ),
}

private const val HISTORY_PAGE_SIZE = 50L

/**
 * Warehouse › History (toolbar icon): every stock movement of one warehouse,
 * newest first, filterable by type and paged. Read-only; mirrors iOS
 * `WarehouseHistoryView` and the web's history tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseHistoryScreen(warehouseId: String, onBack: () -> Unit) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var entries by remember { mutableStateOf<List<WarehouseHistoryEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        isLoading = true
        val offset = if (reset) 0L else entries.size.toLong()
        WarehouseRepository.fetchHistory(warehouseId, filter.types, offset, HISTORY_PAGE_SIZE).fold(
            onSuccess = { page ->
                entries = if (reset) page else entries + page
                hasMore = page.size >= HISTORY_PAGE_SIZE
                error = null
            },
            onFailure = { e -> error = e.message }
        )
        isLoading = false
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(warehouseId, filter) { load(reset = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.warehouse_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.warehouse_history_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HistoryFilter.entries) { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(f.label)) }
                        )
                    }
                }
            }

            error?.let { message ->
                item { Text(text = message, color = StockRed, style = MaterialTheme.typography.bodySmall) }
            }

            if (entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                text = stringResource(R.string.warehouse_history_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(entries, key = { it.id }) { entry -> HistoryRow(entry) }

            if (hasMore) {
                item {
                    TextButton(
                        onClick = { scope.launch { load(reset = false) } },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.warehouse_history_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: WarehouseHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProductImage(imagePath = entry.imagePath, contentDescription = entry.productName, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.productName ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = historyTypeLabel(entry.transactionType),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = historyTypeColor(entry.transactionType)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatDateTime(entry.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val details = listOfNotNull(entry.user, entry.batchNumber?.takeIf { it.isNotBlank() })
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val q = entry.quantityChange
        Text(
            text = if (q > 0) "+$q" else "$q",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = when {
                q > 0 -> StockGreen
                q < 0 -> StockRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun historyTypeLabel(type: String): String = when (type) {
    "intake", "incoming" -> stringResource(R.string.warehouse_tab_incoming)
    "outgoing_refill" -> stringResource(R.string.warehouse_history_type_refill)
    "adjustment_damage" -> stringResource(R.string.warehouse_adjust_reason_damage)
    "adjustment_expired" -> stringResource(R.string.warehouse_adjust_reason_expired)
    "adjustment_correction" -> stringResource(R.string.warehouse_history_type_correction)
    "adjustment_refill_return" -> stringResource(R.string.warehouse_adjust_reason_refill_return)
    "transfer_out" -> stringResource(R.string.warehouse_history_type_transfer_out)
    "transfer_in" -> stringResource(R.string.warehouse_history_type_transfer_in)
    else -> type
}

private fun historyTypeColor(type: String): Color = when (type) {
    "intake", "incoming", "transfer_in" -> StockGreen
    "outgoing_refill", "adjustment_refill_return" -> Color(0xFF3B82F6)
    "adjustment_damage", "adjustment_expired" -> StockRed
    else -> StockOrange
}

private fun formatDateTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))
}.getOrDefault(iso)
