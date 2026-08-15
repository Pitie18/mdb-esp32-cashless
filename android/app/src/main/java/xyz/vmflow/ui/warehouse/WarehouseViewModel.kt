package xyz.vmflow.ui.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.vmflow.data.ExpirationStatus
import xyz.vmflow.data.TrayRepository
import xyz.vmflow.data.WarehouseIntakeLogic
import xyz.vmflow.data.WarehouseRepository
import xyz.vmflow.models.AdjustReason
import xyz.vmflow.models.IntakeEntry
import xyz.vmflow.models.Product
import xyz.vmflow.models.Supplier
import xyz.vmflow.models.Warehouse
import xyz.vmflow.models.WarehouseProductSummary
import xyz.vmflow.models.WarehouseStockBatch

/** Filter options for the stock list's expiration severity. Mirrors iOS `ExpirationFilterOption`. */
enum class ExpirationFilter { ALL, EXPIRING_SOON, CRITICAL }

data class WarehouseUiState(
    val isLoading: Boolean = true,
    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouseId: String? = null,
    val productSummaries: List<WarehouseProductSummary> = emptyList(),
    val recentIntakes: List<IntakeEntry> = emptyList(),
    // Batch drilldown state (for the product-batches sheet)
    val drilldownBatches: List<WarehouseStockBatch> = emptyList(),
    val isLoadingBatches: Boolean = false,
    val isAdjustingBatch: Boolean = false,
    /** All non-discontinued products for the intake picker (`TrayRepository.fetchProducts()`). */
    val products: List<Product> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    /** Product IDs assigned to at least one `machine_trays` slot, company-wide. Drives the out-of-stock exemption below. */
    val assignedProductIds: Set<String> = emptySet(),
    val searchText: String = "",
    val includeOutOfStock: Boolean = false,
    val includeArchived: Boolean = false,
    val expirationFilter: ExpirationFilter = ExpirationFilter.ALL,
    val isBookingIntake: Boolean = false,
    val error: String? = null
) {
    /**
     * Product summaries after search + the three filters, sorted alphabetically
     * by product name (case-insensitive). Stock level deliberately does NOT
     * influence the order — a zero-stock product sits where its name puts it
     * and is identified by its badge instead of by position. Ported 1:1 from
     * iOS `filteredSummaries` (`WarehouseViewModel.swift:78-103`).
     *
     * A member property on the state (not an extension property/function),
     * matching this codebase's existing precedent for a filtered/derived list
     * on a UI state: `MachinesUiState.filteredMachines` in
     * `ui/machines/MachinesViewModel.kt`.
     */
    val filteredSummaries: List<WarehouseProductSummary>
        get() {
            var items = productSummaries

            if (searchText.isNotEmpty()) {
                val query = searchText.lowercase()
                items = items.filter { it.productName.lowercase().contains(query) }
            }
            if (!includeArchived) {
                items = items.filter { !it.discontinued }
            }
            if (!includeOutOfStock) {
                items = items.filter { !it.isOutOfStock || assignedProductIds.contains(it.productId) }
            }
            items = when (expirationFilter) {
                ExpirationFilter.ALL -> items
                ExpirationFilter.EXPIRING_SOON -> items.filter {
                    it.expirationStatus == ExpirationStatus.WARNING || it.expirationStatus == ExpirationStatus.CRITICAL
                }
                ExpirationFilter.CRITICAL -> items.filter { it.expirationStatus == ExpirationStatus.CRITICAL }
            }

            return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.productName })
        }
}

/**
 * Drives the Warehouse management screen: stock overview, intake booking and
 * batch adjustment. Mirrors iOS `WarehouseViewModel` (`ios/VMflow/ViewModels/WarehouseViewModel.swift`).
 * Intake-form field state (product/quantity-text/batch-number/expiration/supplier-name
 * inputs) deliberately lives in the Composable screen, not here — see Task 8/10.
 */
class WarehouseViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WarehouseUiState())
    val uiState: StateFlow<WarehouseUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    /**
     * Loads warehouses first (auto-selecting the first one if none is
     * selected yet), then the warehouse-scoped and company-wide data in
     * parallel. Mirrors iOS `loadAll()` (`WarehouseViewModel.swift:406-421`).
     */
    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            WarehouseRepository.fetchWarehouses().fold(
                onSuccess = { warehouses ->
                    _uiState.update { state ->
                        state.copy(
                            warehouses = warehouses,
                            selectedWarehouseId = state.selectedWarehouseId ?: warehouses.firstOrNull()?.id
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )

            val warehouseId = _uiState.value.selectedWarehouseId

            coroutineScope {
                launch { refreshProductSummaries(warehouseId) }
                launch { refreshRecentIntakes(warehouseId) }
                launch { loadAssignedProductIds() }
                launch { loadSuppliers() }
                launch { loadProducts() }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** Switches the active warehouse and reloads its scoped data. Mirrors iOS `selectWarehouse(_:)`. */
    fun selectWarehouse(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedWarehouseId = id) }
            coroutineScope {
                launch { refreshProductSummaries(id) }
                launch { refreshRecentIntakes(id) }
            }
        }
    }

    private suspend fun refreshProductSummaries(warehouseId: String?) {
        if (warehouseId == null) {
            _uiState.update { it.copy(productSummaries = emptyList()) }
            return
        }
        WarehouseRepository.fetchProductSummaries(warehouseId).fold(
            onSuccess = { summaries -> _uiState.update { it.copy(productSummaries = summaries) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    private suspend fun refreshRecentIntakes(warehouseId: String?) {
        if (warehouseId == null) {
            _uiState.update { it.copy(recentIntakes = emptyList()) }
            return
        }
        WarehouseRepository.fetchRecentIntakes(warehouseId).fold(
            onSuccess = { intakes -> _uiState.update { it.copy(recentIntakes = intakes) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    private suspend fun loadAssignedProductIds() {
        WarehouseRepository.fetchAssignedProductIds().fold(
            onSuccess = { ids -> _uiState.update { it.copy(assignedProductIds = ids) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    /** Non-critical for the intake form (mirrors iOS `loadSuppliersForIntake()` swallowing errors) — a failed fetch just leaves the picker empty. */
    private suspend fun loadSuppliers() {
        WarehouseRepository.fetchSuppliers().fold(
            onSuccess = { suppliers -> _uiState.update { it.copy(suppliers = suppliers) } },
            onFailure = { }
        )
    }

    private suspend fun loadProducts() {
        TrayRepository.fetchProducts().fold(
            onSuccess = { products -> _uiState.update { it.copy(products = products) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    /** Clears a surfaced error after the UI has shown it (e.g. via a snackbar). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun updateSearch(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun toggleIncludeOutOfStock() {
        _uiState.update { it.copy(includeOutOfStock = !it.includeOutOfStock) }
    }

    fun toggleIncludeArchived() {
        _uiState.update { it.copy(includeArchived = !it.includeArchived) }
    }

    fun setExpirationFilter(filter: ExpirationFilter) {
        _uiState.update { it.copy(expirationFilter = filter) }
    }

    /**
     * Loads all non-empty batches for a product in the current warehouse,
     * oldest expiration first. Mirrors iOS `loadBatchesForProduct(_:)`
     * (`WarehouseViewModel.swift:547-576`) — clears the previous drilldown
     * first so re-visiting a different product doesn't briefly render stale
     * rows while the new query runs.
     */
    fun loadBatchesForProduct(productId: String) {
        viewModelScope.launch {
            val warehouseId = _uiState.value.selectedWarehouseId
            if (warehouseId == null) {
                _uiState.update { it.copy(drilldownBatches = emptyList()) }
                return@launch
            }
            _uiState.update { it.copy(drilldownBatches = emptyList(), isLoadingBatches = true) }
            refreshDrilldownBatches(warehouseId, productId)
            _uiState.update { it.copy(isLoadingBatches = false) }
        }
    }

    private suspend fun refreshDrilldownBatches(warehouseId: String, productId: String) {
        WarehouseRepository.fetchBatchesForProduct(warehouseId, productId).fold(
            onSuccess = { batches -> _uiState.update { it.copy(drilldownBatches = batches) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    /**
     * Books a warehouse intake. Short-circuits with no repository call when
     * [quantityText] doesn't evaluate to a positive quantity — the form stays
     * open so the user can fix their input. When [supplierName] is non-blank
     * and doesn't match an existing supplier's name (case-insensitive), the
     * supplier is resolved-or-created first so its id can be attached to the
     * new batch. Mirrors iOS `bookIntake(...)` (`WarehouseViewModel.swift:459-530`);
     * resetting the intake form fields themselves is the caller's
     * responsibility (Task 8/10 own that state, not this ViewModel).
     */
    fun bookIntake(
        productId: String,
        quantityText: String,
        batchNumber: String?,
        expirationDateIso: String?,
        supplierName: String?
    ) {
        val quantity = WarehouseIntakeLogic.evaluateQuantityExpression(quantityText) ?: return

        viewModelScope.launch {
            val state = _uiState.value
            val warehouseId = state.selectedWarehouseId ?: return@launch
            val companyId = state.warehouses.firstOrNull { it.id == warehouseId }?.companyId ?: return@launch

            _uiState.update { it.copy(isBookingIntake = true, error = null) }

            val trimmedSupplierName = supplierName?.trim().orEmpty()
            var supplierId: String? = null
            if (trimmedSupplierName.isNotEmpty()) {
                val existingMatch = state.suppliers.firstOrNull { it.name.equals(trimmedSupplierName, ignoreCase = true) }
                if (existingMatch != null) {
                    supplierId = existingMatch.id
                } else {
                    val resolveResult = WarehouseRepository.resolveOrCreateSupplier(
                        name = trimmedSupplierName,
                        companyId = companyId,
                        existing = state.suppliers
                    )
                    val resolveError = resolveResult.exceptionOrNull()
                    if (resolveError != null) {
                        _uiState.update { it.copy(isBookingIntake = false, error = resolveError.message) }
                        return@launch
                    }
                    val supplier = resolveResult.getOrThrow()
                    supplierId = supplier.id
                    _uiState.update { s ->
                        s.copy(
                            suppliers = (s.suppliers + supplier)
                                .distinctBy { it.id }
                                .sortedBy { it.name.lowercase() }
                        )
                    }
                }
            }

            WarehouseRepository.bookIntake(
                warehouseId = warehouseId,
                companyId = companyId,
                productId = productId,
                quantity = quantity,
                batchNumber = batchNumber,
                expirationDate = expirationDateIso,
                supplierId = supplierId
            ).fold(
                onSuccess = {
                    coroutineScope {
                        launch { refreshProductSummaries(warehouseId) }
                        launch { refreshRecentIntakes(warehouseId) }
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )

            _uiState.update { it.copy(isBookingIntake = false) }
        }
    }

    /**
     * Adjusts a batch's quantity by a signed delta and logs the reason.
     * [productId] is supplied by the caller (the drilldown sheet already
     * knows which product it's showing) rather than round-tripped back from
     * the repository, since [WarehouseRepository.adjustBatch] returns only
     * `Result<Unit>`. Mirrors iOS `adjustBatch(...)` (`WarehouseViewModel.swift:587-671`).
     */
    fun adjustBatch(
        batchId: String,
        productId: String,
        quantityChange: Int,
        reason: AdjustReason,
        notes: String?
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            val warehouseId = state.selectedWarehouseId ?: return@launch
            val companyId = state.warehouses.firstOrNull { it.id == warehouseId }?.companyId ?: return@launch

            _uiState.update { it.copy(isAdjustingBatch = true, error = null) }

            WarehouseRepository.adjustBatch(
                warehouseId = warehouseId,
                companyId = companyId,
                batchId = batchId,
                quantityChange = quantityChange,
                reason = reason,
                notes = notes
            ).fold(
                onSuccess = {
                    coroutineScope {
                        launch { refreshDrilldownBatches(warehouseId, productId) }
                        launch { refreshProductSummaries(warehouseId) }
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )

            _uiState.update { it.copy(isAdjustingBatch = false) }
        }
    }

    /**
     * Looks up a product by barcode and invokes [onFound]/[onNotFound]
     * accordingly. A repository failure (network error) is treated the same
     * as a not-found match — mirrors iOS `lookupBarcode(_:)`
     * (`WarehouseViewModel.swift:436-455`), which logs and returns `nil` on
     * any error rather than surfacing it.
     */
    fun lookupBarcode(barcode: String, onFound: (productId: String) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch {
            WarehouseRepository.lookupBarcode(barcode).fold(
                onSuccess = { productId -> if (productId != null) onFound(productId) else onNotFound() },
                onFailure = { onNotFound() }
            )
        }
    }
}
