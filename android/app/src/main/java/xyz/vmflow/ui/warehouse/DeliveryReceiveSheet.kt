package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.PurchaseOrder
import xyz.vmflow.data.PurchaseOrderLine
import xyz.vmflow.data.ReceiveLineInput

/**
 * Incoming › expected delivery: per line the delivered quantity (prefilled
 * with the ordered one), best-before date and batch, then "Book all"
 * (`receive_purchase_order`). "Already booked · mark as delivered" closes an
 * order whose goods were booked by hand, e.g. with an older app. Mirrors iOS
 * `DeliveryReceiveView` and the web's `WarehouseExpectedDeliveries.vue`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryReceiveSheet(
    order: PurchaseOrder,
    isBusy: Boolean,
    onReceive: (List<ReceiveLineInput>) -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val quantities = remember(order.id) {
        mutableStateMapOf<String, String>().apply { order.lines.forEach { put(it.id, it.quantity.toString()) } }
    }
    val expirations = remember(order.id) { mutableStateMapOf<String, String?>() }
    val batches = remember(order.id) { mutableStateMapOf<String, String>() }
    var confirmClose by remember { mutableStateOf(false) }

    fun quantity(line: PurchaseOrderLine): Int? = quantities[line.id]?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    val isValid = order.lines.all { quantity(it) != null } && order.lines.any { (quantity(it) ?: 0) > 0 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(text = stringResource(R.string.warehouse_delivery_check), style = MaterialTheme.typography.titleLarge)
                    Text(text = order.supplierName ?: "—", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = orderSummary(order),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(order.lines, key = { it.id }) { line ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = line.productName ?: "—",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.warehouse_delivery_ordered_qty, line.quantity),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = quantities[line.id].orEmpty(),
                            onValueChange = { quantities[line.id] = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.warehouse_delivery_delivered)) },
                            isError = quantity(line) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ExpirationDateField(
                            expirationIso = expirations[line.id],
                            onExpirationChange = { expirations[line.id] = it }
                        )
                        OutlinedTextField(
                            value = batches[line.id].orEmpty(),
                            onValueChange = { batches[line.id] = it },
                            label = { Text(stringResource(R.string.warehouse_intake_batch_label)) },
                            placeholder = { Text(stringResource(R.string.warehouse_intake_optional_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        onReceive(order.lines.map { line ->
                            ReceiveLineInput(
                                lineId = line.id,
                                quantity = quantity(line) ?: 0,
                                expirationDate = expirations[line.id],
                                batchNumber = batches[line.id]
                            )
                        })
                    },
                    enabled = isValid && !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.MoveToInbox, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.warehouse_delivery_book_all))
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    TextButton(onClick = { confirmClose = true }, enabled = !isBusy) {
                        Text(stringResource(R.string.warehouse_delivery_close))
                    }
                    Text(
                        text = stringResource(R.string.warehouse_delivery_close_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            text = { Text(stringResource(R.string.warehouse_delivery_close_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onClose()
                }) { Text(stringResource(R.string.warehouse_delivery_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.warehouse_order_keep)) }
            }
        )
    }
}
