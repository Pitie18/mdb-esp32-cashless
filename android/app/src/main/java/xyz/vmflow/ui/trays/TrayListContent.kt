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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.vmflow.data.TrayRepository
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TrayUpsert

@Composable
fun TrayListContent(
    trays: List<Tray>,
    products: List<Product>,
    machineId: String,
    onStockChange: (trayId: String, delta: Int) -> Unit,
    onFillTray: (trayId: String) -> Unit,
    onDeleteTray: (trayId: String) -> Unit,
    onTraysChanged: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var editingTray by remember { mutableStateOf<Tray?>(null) }
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
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = trays,
                    key = { it.id }
                ) { tray ->
                    TrayRow(
                        tray = tray,
                        onStockChange = { delta -> onStockChange(tray.id, delta) },
                        onFill = { onFillTray(tray.id) },
                        onDelete = { onDeleteTray(tray.id) }
                    )
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
