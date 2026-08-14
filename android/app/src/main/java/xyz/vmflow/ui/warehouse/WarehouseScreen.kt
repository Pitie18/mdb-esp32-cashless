package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.vmflow.R
import xyz.vmflow.models.Warehouse

/**
 * Warehouse management screen: warehouse picker (only shown when the
 * company has more than one warehouse) plus a Stock/Incoming tab switcher.
 * The Stock tab renders [WarehouseStockTab] (Task 9); the Incoming tab
 * renders [WarehouseIntakeTab] (Task 10) — both tabs only receive already
 * ViewModel-bound callback lambdas here, never the `viewModel` itself.
 *
 * Mirrors iOS `WarehouseView` (`ios/VMflow/Views/Warehouse/WarehouseView.swift`
 * lines 1-105), adapted to Android idiom: an `ExposedDropdownMenuBox`
 * instead of iOS's picker, and a Material `TabRow` instead of a segmented
 * control. Loading/empty-state handling follows the pattern established by
 * `MachineDetailScreen.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseScreen(viewModel: WarehouseViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.warehouse_screen_title)) })
        }
    ) { padding ->
        when {
            uiState.isLoading && uiState.warehouses.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.warehouses.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.warehouse_empty_state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (uiState.warehouses.size > 1) {
                        WarehousePicker(
                            warehouses = uiState.warehouses,
                            selectedWarehouseId = uiState.selectedWarehouseId,
                            onSelect = { id -> viewModel.selectWarehouse(id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    val tabs = listOf(
                        stringResource(R.string.warehouse_tab_stock),
                        stringResource(R.string.warehouse_tab_incoming)
                    )
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }

                    when (selectedTab) {
                        0 -> WarehouseStockTab(
                            uiState = uiState,
                            onSearchChange = { text -> viewModel.updateSearch(text) },
                            onToggleOutOfStock = { viewModel.toggleIncludeOutOfStock() },
                            onToggleArchived = { viewModel.toggleIncludeArchived() },
                            onExpirationFilterChange = { filter -> viewModel.setExpirationFilter(filter) },
                            onProductClick = { }
                        )
                        1 -> WarehouseIntakeTab(
                            uiState = uiState,
                            onSubmit = { productId, quantityText, batchNumber, expirationIso, supplierName ->
                                viewModel.bookIntake(productId, quantityText, batchNumber, expirationIso, supplierName)
                            },
                            onScanRequested = { },
                            onLookupBarcode = { barcode, onFound, onNotFound ->
                                viewModel.lookupBarcode(barcode, onFound, onNotFound)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Dropdown warehouse selector, only rendered by the caller when there's more than one warehouse to choose from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarehousePicker(
    warehouses: List<Warehouse>,
    selectedWarehouseId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = warehouses.firstOrNull { it.id == selectedWarehouseId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.name ?: selected?.id.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.warehouse_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            warehouses.forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text(warehouse.name ?: warehouse.id) },
                    onClick = {
                        onSelect(warehouse.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

