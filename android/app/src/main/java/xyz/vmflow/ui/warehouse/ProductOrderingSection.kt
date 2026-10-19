package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.vmflow.R

/**
 * Order part of the product sheet: whether the product is on the order list
 * (automatic / manual, with who and from which device), add or remove it,
 * units on the way and — for admins — main supplier and carton size.
 * Mirrors iOS `ProductOrderingSection` in `ProductBatchesView.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductOrderingSection(
    productId: String,
    uiState: WarehouseUiState,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onAssignSupplier: (supplierId: String?) -> Unit,
    onSaveUnitSize: (Int) -> Unit
) {
    val manual = uiState.manualEntry(productId)
    val onList = productId in uiState.orderListProductIds
    val onOrder = uiState.onOrderByProduct[productId] ?: 0
    val info = uiState.productOrderInfo[productId]

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(text = stringResource(R.string.warehouse_order_section), style = MaterialTheme.typography.titleSmall)

        if (manual != null) {
            StatusLine(
                text = stringResource(R.string.warehouse_order_manual_on_list, manual.addedByName ?: "—", viaLabel(manual.addedVia)),
                color = Color(0xFF8B5CF6)
            )
            OutlinedButton(onClick = onRemove) {
                Icon(Icons.Default.RemoveShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.warehouse_order_list_remove))
            }
        } else {
            if (onList) {
                StatusLine(text = stringResource(R.string.warehouse_order_auto_on_list), color = Color(0xFF3B82F6))
            }
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.warehouse_order_list_add))
            }
        }
        if (onOrder > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = pluralStringResource(R.plurals.warehouse_order_units_on_the_way, onOrder, onOrder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.isAdmin) {
            var expanded by remember { mutableStateOf(false) }
            val selected = uiState.orderSuppliers.firstOrNull { it.id == info?.preferredSupplierId }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.name ?: stringResource(R.string.warehouse_order_supplier_none),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.warehouse_order_main_supplier)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.warehouse_order_supplier_none)) },
                        onClick = {
                            expanded = false
                            onAssignSupplier(null)
                        }
                    )
                    uiState.orderSuppliers.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = {
                                expanded = false
                                onAssignSupplier(s.id)
                            }
                        )
                    }
                }
            }

            val current = info?.unitSize ?: 1
            var unitSizeText by remember(productId, current) { mutableStateOf(current.toString()) }
            val parsed = unitSizeText.toIntOrNull()?.takeIf { it >= 1 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = unitSizeText,
                    onValueChange = { unitSizeText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.warehouse_order_unit_size)) },
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (parsed != null && parsed != current) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSaveUnitSize(parsed) }) {
                        Text(stringResource(R.string.warehouse_order_save))
                    }
                }
            }
        } else {
            val supplierName = info?.supplierId?.let { id -> uiState.orderSuppliers.firstOrNull { it.id == id }?.name }
            if (supplierName != null) {
                Text(
                    text = stringResource(R.string.warehouse_order_supplier_value, supplierName),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
