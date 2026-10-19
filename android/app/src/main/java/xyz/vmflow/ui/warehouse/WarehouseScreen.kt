package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import xyz.vmflow.data.OrderSuggestion
import xyz.vmflow.data.PurchaseOrder
import xyz.vmflow.models.Warehouse

/**
 * Warehouse management screen: warehouse picker (only shown when the
 * company has more than one warehouse) plus a Stock/Order/Incoming tab
 * switcher and a History toolbar icon.
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

    // Batch drilldown + adjust sheet state. Owned here (not inside
    // WarehouseStockTab) because onProductClick is a callback WarehouseScreen
    // wires down INTO the tab, and the adjust sheet needs the productId the
    // drilldown was opened for to call viewModel.adjustBatch(...). Only one
    // of the two sheets is ever rendered at a time: opening the adjust sheet
    // for a batch hides the drilldown sheet underneath it (dismissing the
    // adjust sheet — confirm or cancel — reveals the drilldown again, now
    // showing the refreshed batch list).
    var drilldownProductId by remember { mutableStateOf<String?>(null) }
    var adjustBatchId by remember { mutableStateOf<String?>(null) }
    var receiveOrderId by remember { mutableStateOf<String?>(null) }
    var cancelCandidate by remember { mutableStateOf<PurchaseOrder?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            viewModel.clearNotice()
            snackbarHostState.showSnackbar(it)
        }
    }

    val historyWarehouseId = uiState.selectedWarehouseId
    if (showHistory && historyWarehouseId != null) {
        WarehouseHistoryScreen(warehouseId = historyWarehouseId, onBack = { showHistory = false })
        return
    }

    val assignedTemplate = stringResource(R.string.warehouse_order_assigned)
    val orderedTemplate = stringResource(R.string.warehouse_order_marked_ordered)
    val bookedTemplate = stringResource(R.string.warehouse_delivery_booked)
    val orderActions = OrderTabActions(
        onCartons = viewModel::setCartons,
        onReset = viewModel::resetCartons,
        onAdd = viewModel::addToOrderList,
        onRemove = viewModel::removeFromOrderList,
        onAssignSupplier = { productId, supplierId ->
            viewModel.assignSupplier(productId, supplierId) { product, supplier -> assignedTemplate.format(product, supplier) }
        },
        onPlaceOrder = { group -> viewModel.placeOrder(group, orderedTemplate.format(group.supplier?.name.orEmpty())) },
        onCancelOrder = { order -> cancelCandidate = order },
        onCheckDelivery = { order ->
            selectedTab = 2
            receiveOrderId = order.id
        },
        onNotice = viewModel::showNotice,
        onOpenProduct = { productId ->
            drilldownProductId = productId
            adjustBatchId = null
            viewModel.loadBatchesForProduct(productId)
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.warehouse_screen_title)) },
                actions = {
                    if (uiState.selectedWarehouseId != null) {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Default.History, contentDescription = stringResource(R.string.warehouse_history_title))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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

                    val orderCount = OrderSuggestion.lineCount(uiState.orderGroups)
                    val tabs = listOf(
                        stringResource(R.string.warehouse_tab_stock),
                        if (orderCount > 0) stringResource(R.string.warehouse_tab_order_count, orderCount)
                        else stringResource(R.string.warehouse_tab_order),
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
                            onToggleSortByReach = { viewModel.toggleSortByReach() },
                            onProductClick = { productId ->
                                drilldownProductId = productId
                                adjustBatchId = null
                                viewModel.loadBatchesForProduct(productId)
                            },
                            onAddToOrderList = viewModel::addToOrderList,
                            onRemoveFromOrderList = viewModel::removeFromOrderList
                        )
                        1 -> WarehouseOrderTab(uiState = uiState, actions = orderActions)
                        2 -> WarehouseIntakeTab(
                            uiState = uiState,
                            onSubmit = { productId, quantityText, batchNumber, expirationIso, supplierName ->
                                viewModel.bookIntake(productId, quantityText, batchNumber, expirationIso, supplierName)
                            },
                            onLookupBarcode = { barcode, onFound, onNotFound ->
                                viewModel.lookupBarcode(barcode, onFound, onNotFound)
                            },
                            onCheckDelivery = { order -> receiveOrderId = order.id },
                            onCancelOrder = { order -> cancelCandidate = order }
                        )
                    }
                }
            }
        }
    }

    val openProductId = drilldownProductId
    if (openProductId != null) {
        val openBatchId = adjustBatchId
        if (openBatchId == null) {
            val productName = uiState.productSummaries.firstOrNull { it.productId == openProductId }?.productName.orEmpty()
            BatchDrilldownSheet(
                productName = productName,
                batches = uiState.drilldownBatches,
                isLoading = uiState.isLoadingBatches,
                onAdjust = { batchId -> adjustBatchId = batchId },
                onDismiss = { drilldownProductId = null },
                orderingSection = {
                    ProductOrderingSection(
                        productId = openProductId,
                        uiState = uiState,
                        onAdd = { viewModel.addToOrderList(openProductId) },
                        onRemove = { viewModel.removeFromOrderList(openProductId) },
                        onAssignSupplier = { supplierId -> viewModel.assignSupplier(openProductId, supplierId) },
                        onSaveUnitSize = { size -> viewModel.saveUnitSize(openProductId, size) }
                    )
                }
            )
        } else {
            val batch = uiState.drilldownBatches.firstOrNull { it.id == openBatchId }
            if (batch != null) {
                BatchAdjustSheet(
                    batch = batch,
                    isSubmitting = uiState.isAdjustingBatch,
                    onConfirm = { quantityChange, reason, notes ->
                        viewModel.adjustBatch(
                            batchId = batch.id,
                            productId = openProductId,
                            quantityChange = quantityChange,
                            reason = reason,
                            notes = notes
                        )
                    },
                    onDismiss = { adjustBatchId = null }
                )
            }
        }
    }

    val receiveOrder = uiState.openOrders.firstOrNull { it.id == receiveOrderId }
    if (receiveOrder != null) {
        DeliveryReceiveSheet(
            order = receiveOrder,
            isBusy = uiState.busyOrderId == receiveOrder.id,
            onReceive = { lines ->
                viewModel.receiveOrder(receiveOrder, lines) { units ->
                    receiveOrderId = null
                    viewModel.showNotice(bookedTemplate.format(units))
                }
            },
            onClose = { viewModel.closeOrder(receiveOrder) { receiveOrderId = null } },
            onDismiss = { receiveOrderId = null }
        )
    }

    cancelCandidate?.let { order ->
        CancelOrderDialog(
            order = order,
            onConfirm = {
                viewModel.cancelOrder(order)
                cancelCandidate = null
            },
            onDismiss = { cancelCandidate = null }
        )
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

