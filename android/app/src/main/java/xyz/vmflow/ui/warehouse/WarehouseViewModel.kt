package xyz.vmflow.ui.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.vmflow.data.AuthRepository
import xyz.vmflow.data.ExpirationStatus
import xyz.vmflow.data.ManualOrderEntry
import xyz.vmflow.data.OrderGroup
import xyz.vmflow.data.OrderProductInput
import xyz.vmflow.data.OrderSettings
import xyz.vmflow.data.OrderSuggestion
import xyz.vmflow.data.OrderSupplier
import xyz.vmflow.data.ProductOrderInfo
import xyz.vmflow.data.PurchaseOrder
import xyz.vmflow.data.PurchaseOrderRepository
import xyz.vmflow.data.ReceiveLineInput
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
    /** Shortest stock reach first instead of alphabetical (products without sales last). */
    val sortByReach: Boolean = false,
    val isBookingIntake: Boolean = false,
    /**
     * Increments exactly once per intake whose underlying write actually
     * succeeded, regardless of whether the post-write refresh
     * (`refreshProductSummaries`/`refreshRecentIntakes`) also succeeded. The
     * intake form's success-gated reset keys off this counter changing, not
     * off `error == null` — a refresh failure after a successful write still
     * leaves `error` non-null, and inferring "did the booking succeed" from
     * the absence of an error would leave the form armed for an accidental
     * duplicate resubmission of a delivery that already landed.
     */
    val intakeBookedTick: Int = 0,
    val error: String? = null,
    // ── Ordering ("Order" tab + expected deliveries under Incoming) ──
    val isAdmin: Boolean = false,
    val orderSettings: OrderSettings = OrderSettings(),
    val orderSuppliers: List<OrderSupplier> = emptyList(),
    val productOrderInfo: Map<String, ProductOrderInfo> = emptyMap(),
    /** Units in machine slots per product, company-wide (`machine_trays.current_stock`). */
    val machineStock: Map<String, Int> = emptyMap(),
    val manualEntries: List<ManualOrderEntry> = emptyList(),
    val openOrders: List<PurchaseOrder> = emptyList(),
    /** Cartons changed by hand, per product — client-side only, like on the web. */
    val orderOverrides: Map<String, Int> = emptyMap(),
    val busySupplierId: String? = null,
    val busyOrderId: String? = null,
    /** One-off confirmation for the snackbar ("Copied", "Delivery booked: …"). */
    val notice: String? = null
) {
    /** Summaries with the machine stock counted towards the reach when the company setting says so. */
    private val summariesWithMachineStock: List<WarehouseProductSummary>
        get() = productSummaries.map {
            it.copy(machineQuantity = if (orderSettings.countMachineStock) machineStock[it.productId] ?: 0 else 0)
        }

    /** Units of a product on open orders (on the way). */
    val onOrderByProduct: Map<String, Int>
        get() {
            val acc = mutableMapOf<String, Int>()
            for (order in openOrders) for (line in order.rawLines) {
                val id = line.productId ?: continue
                acc[id] = (acc[id] ?: 0) + line.quantity
            }
            return acc
        }

    val orderInputs: List<OrderProductInput>
        get() {
            val onOrder = onOrderByProduct
            return productSummaries.map { p ->
                val info = productOrderInfo[p.productId]
                OrderProductInput(
                    productId = p.productId,
                    productName = p.productName,
                    imagePath = p.imagePath,
                    discontinued = p.discontinued,
                    warehouseQty = p.totalQuantity,
                    machineQty = machineStock[p.productId] ?: 0,
                    avgDailySales = p.avgDailySales,
                    onOrder = onOrder[p.productId] ?: 0,
                    unitSize = info?.unitSize ?: 1,
                    supplierId = info?.supplierId,
                    unitPriceNet = info?.latestPriceNet
                )
            }
        }

    val orderGroups: List<OrderGroup>
        get() = OrderSuggestion.buildGroups(
            products = orderInputs,
            suppliers = orderSuppliers,
            manual = manualEntries,
            overrides = orderOverrides,
            settings = orderSettings
        )

    /** Products currently on the order list (automatic or manual). */
    val orderListProductIds: Set<String>
        get() = orderGroups.flatMap { g -> g.lines.map { it.product.productId } }.toSet()

    fun manualEntry(productId: String): ManualOrderEntry? = manualEntries.firstOrNull { it.productId == productId }

    /**
     * Product summaries after search + the three filters, sorted alphabetically
     * by product name (case-insensitive) — or, with [sortByReach], shortest
     * stock reach first. Stock level deliberately does NOT
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
            var items = summariesWithMachineStock

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

            val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { it: WarehouseProductSummary -> it.productName }
            if (sortByReach) {
                return items.sortedWith(
                    compareBy<WarehouseProductSummary> { it.daysRemaining ?: Int.MAX_VALUE }.then(byName)
                )
            }
            return items.sortedWith(byName)
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
                launch { loadMachineStock() }
                launch { loadSuppliers() }
                launch { loadProducts() }
                launch { loadRole() }
                launch { loadOrdering() }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** Switches the active warehouse and reloads its scoped data. Mirrors iOS `selectWarehouse(_:)`. */
    fun selectWarehouse(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedWarehouseId = id, orderOverrides = emptyMap()) }
            coroutineScope {
                launch { refreshProductSummaries(id) }
                launch { refreshRecentIntakes(id) }
                launch { loadWarehouseOrdering() }
            }
        }
    }

    private suspend fun refreshProductSummaries(warehouseId: String?) {
        if (warehouseId == null) {
            _uiState.update { it.copy(productSummaries = emptyList()) }
            return
        }
        val companyId = _uiState.value.warehouses.firstOrNull { it.id == warehouseId }?.companyId
        WarehouseRepository.fetchProductSummaries(warehouseId, companyId).fold(
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

    /** Machine stock per product; its keys are also the products assigned to a slot. */
    private suspend fun loadMachineStock() {
        PurchaseOrderRepository.fetchMachineStock().fold(
            onSuccess = { stock -> _uiState.update { it.copy(machineStock = stock, assignedProductIds = stock.keys) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    /** Best-effort: a failed role fetch leaves isAdmin false, which hides the admin-only actions. */
    private suspend fun loadRole() {
        AuthRepository.fetchOrganization().onSuccess { response ->
            _uiState.update { it.copy(isAdmin = response.role == "admin") }
        }
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

    fun toggleSortByReach() {
        _uiState.update { it.copy(sortByReach = !it.sortByReach) }
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

            var booked = false
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
                    booked = true
                    coroutineScope {
                        launch { refreshProductSummaries(warehouseId) }
                        launch { refreshRecentIntakes(warehouseId) }
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )

            // The tick only advances when the write itself (bookIntake) succeeded,
            // never based on whether the refreshes above also succeeded — see the
            // field doc on WarehouseUiState.intakeBookedTick. Stamped into the same
            // update as isBookingIntake = false so the two are atomic, matching the
            // ordering guarantee the previous fix established.
            _uiState.update {
                it.copy(
                    isBookingIntake = false,
                    intakeBookedTick = if (booked) it.intakeBookedTick + 1 else it.intakeBookedTick
                )
            }
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

    // ── Ordering ───────────────────────────────────────────────────────────
    // Mirrors iOS `WarehouseViewModel+Ordering.swift` and the web's order
    // handlers in `pages/warehouse/index.vue`.

    /** Settings, suppliers and product info (company-wide), then the selected warehouse's list and orders. */
    private suspend fun loadOrdering() {
        val state = _uiState.value
        val companyId = state.warehouses.firstOrNull { it.id == state.selectedWarehouseId }?.companyId
            ?: state.warehouses.firstOrNull()?.companyId
        // Best-effort: the stock list keeps working without ordering data
        // (e.g. a backend that has not been updated yet).
        if (companyId != null) {
            PurchaseOrderRepository.fetchSettings(companyId).onSuccess { settings ->
                _uiState.update { it.copy(orderSettings = settings) }
            }
        }
        PurchaseOrderRepository.fetchSuppliers().onSuccess { suppliers ->
            _uiState.update { it.copy(orderSuppliers = suppliers) }
            PurchaseOrderRepository.fetchProductInfo(suppliers).onSuccess { info ->
                _uiState.update { it.copy(productOrderInfo = info) }
            }
        }
        loadWarehouseOrdering()
    }

    /** Manual entries and open orders of the selected warehouse. */
    private suspend fun loadWarehouseOrdering() {
        val warehouseId = _uiState.value.selectedWarehouseId
        if (warehouseId == null) {
            _uiState.update { it.copy(manualEntries = emptyList(), openOrders = emptyList()) }
            return
        }
        coroutineScope {
            launch {
                PurchaseOrderRepository.fetchManualEntries(warehouseId).onSuccess { entries ->
                    _uiState.update { it.copy(manualEntries = entries) }
                }
            }
            launch {
                PurchaseOrderRepository.fetchOpenOrders(warehouseId).onSuccess { orders ->
                    _uiState.update { it.copy(openOrders = orders) }
                }
            }
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun showNotice(text: String) {
        _uiState.update { it.copy(notice = text) }
    }

    fun addToOrderList(productId: String) {
        val warehouseId = _uiState.value.selectedWarehouseId ?: return
        viewModelScope.launch {
            PurchaseOrderRepository.addToOrderList(warehouseId, productId).fold(
                onSuccess = { refreshManualEntries(warehouseId) },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    fun removeFromOrderList(productId: String) {
        val warehouseId = _uiState.value.selectedWarehouseId ?: return
        viewModelScope.launch {
            PurchaseOrderRepository.removeFromOrderList(warehouseId, productId).fold(
                onSuccess = {
                    _uiState.update { it.copy(orderOverrides = it.orderOverrides - productId) }
                    refreshManualEntries(warehouseId)
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    private suspend fun refreshManualEntries(warehouseId: String) {
        PurchaseOrderRepository.fetchManualEntries(warehouseId).fold(
            onSuccess = { entries -> _uiState.update { it.copy(manualEntries = entries) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
        )
    }

    fun setCartons(productId: String, cartons: Int) {
        _uiState.update { it.copy(orderOverrides = it.orderOverrides + (productId to maxOf(0, cartons))) }
    }

    fun resetCartons(productId: String) {
        _uiState.update { it.copy(orderOverrides = it.orderOverrides - productId) }
    }

    /**
     * Admins: moves a product to a supplier ("no supplier" group's inline
     * picker, product sheet). `null` clears the main supplier.
     */
    fun assignSupplier(productId: String, supplierId: String?, confirmation: ((product: String, supplier: String) -> String)? = null) {
        viewModelScope.launch {
            PurchaseOrderRepository.setPreferredSupplier(productId, supplierId).fold(
                onSuccess = {
                    _uiState.update { s ->
                        val info = s.productOrderInfo[productId] ?: ProductOrderInfo()
                        val supplierName = s.orderSuppliers.firstOrNull { it.id == supplierId }?.name
                        val productName = s.productSummaries.firstOrNull { it.productId == productId }?.productName.orEmpty()
                        s.copy(
                            productOrderInfo = s.productOrderInfo + (productId to info.copy(preferredSupplierId = supplierId)),
                            orderOverrides = s.orderOverrides - productId,
                            notice = if (supplierName != null && confirmation != null) confirmation(productName, supplierName) else s.notice
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    /** Admins: units per carton. */
    fun saveUnitSize(productId: String, size: Int) {
        val clamped = maxOf(1, size)
        viewModelScope.launch {
            PurchaseOrderRepository.setUnitSize(productId, clamped).fold(
                onSuccess = {
                    _uiState.update { s ->
                        val info = s.productOrderInfo[productId] ?: ProductOrderInfo()
                        s.copy(
                            productOrderInfo = s.productOrderInfo + (productId to info.copy(unitSize = clamped)),
                            orderOverrides = s.orderOverrides - productId
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
        }
    }

    /**
     * "Mark as ordered" (admins): the lines with cartons become an open order
     * and count as on the way; their manual entries are cleared server-side.
     */
    fun placeOrder(group: OrderGroup, confirmation: String) {
        val warehouseId = _uiState.value.selectedWarehouseId ?: return
        val supplier = group.supplier ?: return
        val lines = group.lines.filter { it.cartons > 0 }
        if (lines.isEmpty() || _uiState.value.busySupplierId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(busySupplierId = supplier.id) }
            PurchaseOrderRepository.placeOrder(warehouseId, supplier.id, lines).fold(
                onSuccess = {
                    val ids = group.lines.map { it.product.productId }.toSet()
                    _uiState.update { it.copy(orderOverrides = it.orderOverrides - ids) }
                    loadWarehouseOrdering()
                    _uiState.update { it.copy(notice = confirmation) }
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
            _uiState.update { it.copy(busySupplierId = null) }
        }
    }

    /** Admins only. */
    fun cancelOrder(order: PurchaseOrder) {
        runOrderAction(order) { PurchaseOrderRepository.cancelOrder(order.id) }
    }

    /** "Already booked · mark as delivered": closes without booking anything. */
    fun closeOrder(order: PurchaseOrder, onDone: () -> Unit = {}) {
        runOrderAction(order, onDone) { PurchaseOrderRepository.closeOrder(order.id) }
    }

    private fun runOrderAction(order: PurchaseOrder, onDone: () -> Unit = {}, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyOrderId = order.id) }
            action().fold(
                onSuccess = {
                    loadWarehouseOrdering()
                    onDone()
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
            _uiState.update { it.copy(busyOrderId = null) }
        }
    }

    /** Books what was delivered into the warehouse as batches; [onBooked] gets the booked units. */
    fun receiveOrder(order: PurchaseOrder, lines: List<ReceiveLineInput>, onBooked: (units: Int) -> Unit) {
        val warehouseId = _uiState.value.selectedWarehouseId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busyOrderId = order.id) }
            PurchaseOrderRepository.receiveOrder(order.id, lines).fold(
                onSuccess = { result ->
                    coroutineScope {
                        launch { loadWarehouseOrdering() }
                        launch { refreshProductSummaries(warehouseId) }
                        launch { refreshRecentIntakes(warehouseId) }
                    }
                    onBooked(result.units)
                },
                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
            )
            _uiState.update { it.copy(busyOrderId = null) }
        }
    }
}
