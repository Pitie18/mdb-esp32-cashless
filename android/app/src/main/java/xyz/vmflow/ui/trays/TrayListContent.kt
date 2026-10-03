package xyz.vmflow.ui.trays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ProductListRow
import xyz.vmflow.data.StockHealth
import xyz.vmflow.data.TrayStockFlag
import kotlinx.coroutines.launch
import xyz.vmflow.data.TrayRepository
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TrayUpsert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrayListContent(
    trays: List<Tray>,
    products: List<Product>,
    machineId: String,
    /** The machine's `linked_selections` flag: hides the "slot empty" hint. */
    linkedSelections: Boolean = false,
    onStockChange: (trayId: String, delta: Int) -> Unit,
    onFillTray: (trayId: String) -> Unit,
    onDeleteTray: (trayId: String) -> Unit,
    onTraysChanged: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var editingTray by remember { mutableStateOf<Tray?>(null) }
    // "By product" (default) groups a product's slots under a summary header;
    // "By slot" is the plain list. Mirrors the PWA's trayView toggle.
    var viewByProduct by rememberSaveable { mutableStateOf(true) }
    // Search on product name and slot number, like the PWA's tray search.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        if (trays.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No trays configured",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add trays to track stock levels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val groupIndex = remember(trays, linkedSelections) {
                StockHealth.buildTrayGroupIndex(trays, linkedSelections)
            }
            // Top-off rows are only tinted while some product is actually low,
            // same rule as the PWA's tray list.
            val anyLow = remember(groupIndex) { groupIndex.values.any { it.flag == TrayStockFlag.LOW } }
            val rows = remember(trays, groupIndex, viewByProduct, searchQuery) {
                val visible: (Tray) -> Boolean = { StockHealth.trayMatchesSearch(it, searchQuery) }
                // Headers keep the whole product's totals even when the search hides some of its slots.
                if (viewByProduct) StockHealth.productListRows(trays, groupIndex, visible)
                else trays.filter(visible).map { ProductListRow(tray = it) }
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.tray_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.warehouse_stock_search_clear))
                                }
                            }
                        },
                        singleLine = true
                    )
                }
                item(key = "view-toggle") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = viewByProduct,
                            onClick = { viewByProduct = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text(stringResource(R.string.tray_view_by_product))
                        }
                        SegmentedButton(
                            selected = !viewByProduct,
                            onClick = { viewByProduct = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text(stringResource(R.string.tray_view_by_slot))
                        }
                    }
                }
                rows.forEach { row ->
                    val tray = row.tray
                    val header = row.header
                    if (header != null) {
                        item(key = "header-${header.productId}") {
                            ProductGroupHeader(
                                group = header,
                                needsRefill = groupIndex[tray.id]?.needsRefill ?: false
                            )
                        }
                    }
                    item(key = tray.id) {
                        TrayRow(
                            tray = tray,
                            onStockChange = { delta -> onStockChange(tray.id, delta) },
                            onFill = { onFillTray(tray.id) },
                            onDelete = { onDeleteTray(tray.id) },
                            modifier = if (row.inGroup) Modifier.padding(start = 16.dp) else Modifier,
                            flag = groupIndex[tray.id]?.flag ?: TrayStockFlag.OK,
                            showFillHighlight = anyLow
                        )
                    }
                }
            }
        }

        // FABs
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = { showBatchDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Batch add trays")
            }
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Tray") }
            )
        }
    }

    // Add/Edit dialog
    if (showAddDialog || editingTray != null) {
        TrayEditDialog(
            tray = editingTray,
            products = products,
            machineId = machineId,
            onDismiss = {
                showAddDialog = false
                editingTray = null
            },
            onSave = { itemNumber, productId, capacity, currentStock, minStock, fillWhenBelow ->
                scope.launch {
                    val upsert = TrayUpsert(
                        id = editingTray?.id,
                        machineId = machineId,
                        itemNumber = itemNumber,
                        productId = productId,
                        capacity = capacity,
                        currentStock = currentStock,
                        minStock = minStock,
                        fillWhenBelow = fillWhenBelow
                    )
                    TrayRepository.upsertTray(upsert)
                    showAddDialog = false
                    editingTray = null
                    onTraysChanged()
                }
            }
        )
    }

    // Batch add dialog
    if (showBatchDialog) {
        BatchAddDialog(
            machineId = machineId,
            onDismiss = { showBatchDialog = false },
            onSave = { startSlot, count, capacity ->
                scope.launch {
                    TrayRepository.batchCreateTrays(machineId, startSlot, count, capacity)
                    showBatchDialog = false
                    onTraysChanged()
                }
            }
        )
    }
}
