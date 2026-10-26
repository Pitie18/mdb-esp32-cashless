package xyz.vmflow.ui.trays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrayEditDialog(
    tray: Tray?,
    products: List<Product>,
    machineId: String,
    onDismiss: () -> Unit,
    onSave: (itemNumber: Int, productId: String?, capacity: Int, currentStock: Int, minStock: Int?, fillWhenBelow: Int?) -> Unit
) {
    val isEditing = tray != null
    var itemNumber by remember { mutableIntStateOf(tray?.itemNumber ?: 1) }
    var selectedProductId by remember { mutableStateOf(tray?.productId) }
    var capacity by remember { mutableIntStateOf(tray?.capacity ?: 10) }
    var currentStock by remember { mutableIntStateOf(tray?.currentStock ?: 0) }
    var minStock by remember { mutableIntStateOf(tray?.minStock ?: 2) }
    var fillWhenBelow by remember { mutableIntStateOf(tray?.fillWhenBelow ?: 3) }
    var productDropdownExpanded by remember { mutableStateOf(false) }

    val selectedProduct = products.find { it.id == selectedProductId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEditing) stringResource(R.string.tray_edit_title, tray?.itemNumber ?: 0)
                else stringResource(R.string.tray_add_title)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = itemNumber.toString(),
                    onValueChange = { itemNumber = it.toIntOrNull() ?: itemNumber },
                    label = { Text(stringResource(R.string.tray_slot_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = productDropdownExpanded,
                    onExpandedChange = { productDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedProduct?.name ?: stringResource(R.string.tray_unassigned),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.tray_product)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = productDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = productDropdownExpanded,
                        onDismissRequest = { productDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tray_unassigned)) },
                            onClick = {
                                selectedProductId = null
                                productDropdownExpanded = false
                            }
                        )
                        products.forEach { product ->
                            DropdownMenuItem(
                                text = { Text(product.name ?: stringResource(R.string.tray_unnamed_product)) },
                                onClick = {
                                    selectedProductId = product.id
                                    productDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = capacity.toString(),
                        onValueChange = { capacity = it.toIntOrNull() ?: capacity },
                        label = { Text(stringResource(R.string.tray_capacity)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = currentStock.toString(),
                        onValueChange = { currentStock = it.toIntOrNull() ?: currentStock },
                        label = { Text(stringResource(R.string.tray_stock)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = minStock.toString(),
                        onValueChange = { minStock = it.toIntOrNull() ?: minStock },
                        label = { Text(stringResource(R.string.tray_min_stock)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fillWhenBelow.toString(),
                        onValueChange = { fillWhenBelow = it.toIntOrNull() ?: fillWhenBelow },
                        label = { Text(stringResource(R.string.tray_fill_below)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        itemNumber,
                        selectedProductId,
                        capacity,
                        currentStock.coerceAtMost(capacity),
                        minStock,
                        fillWhenBelow
                    )
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun BatchAddDialog(
    machineId: String,
    onDismiss: () -> Unit,
    onSave: (startSlot: Int, count: Int, capacity: Int) -> Unit
) {
    var startSlot by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(5) }
    var capacity by remember { mutableIntStateOf(10) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tray_batch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.tray_batch_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = startSlot.toString(),
                    onValueChange = { startSlot = it.toIntOrNull() ?: startSlot },
                    label = { Text(stringResource(R.string.tray_batch_start_slot)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = count.toString(),
                    onValueChange = { count = it.toIntOrNull() ?: count },
                    label = { Text(stringResource(R.string.tray_batch_count)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = capacity.toString(),
                    onValueChange = { capacity = it.toIntOrNull() ?: capacity },
                    label = { Text(stringResource(R.string.tray_batch_capacity_each)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tray_batch_preview, startSlot, startSlot + count - 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(startSlot, count, capacity) },
                enabled = count > 0 && capacity > 0
            ) {
                Text(pluralStringResource(R.plurals.tray_batch_submit, count, count))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
