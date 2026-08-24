package xyz.vmflow.ui.refill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.vmflow.data.AuthRepository
import xyz.vmflow.data.RefillRepository
import xyz.vmflow.data.RefillTourLogic
import xyz.vmflow.data.RefillTourStoreHolder
import xyz.vmflow.data.TourStore
import xyz.vmflow.data.WarehouseRepository
import xyz.vmflow.models.CombinedPackingItem
import xyz.vmflow.models.PersistedTourState
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillStep
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.RefillTrayPayload
import xyz.vmflow.models.TourLogEntry
import xyz.vmflow.models.TrayApplicationResult
import xyz.vmflow.models.Warehouse

/**
 * Everything the refill wizard's three steps read. One immutable snapshot,
 * same shape as [xyz.vmflow.ui.warehouse.WarehouseUiState].
 *
 * [packingList] is **stored**, not computed in a getter: the pack step reads
 * it on every frame and [RefillTourLogic.buildCombinedPackingList] groups,
 * allocates and locale-sorts. It is re-derived (see
 * `RefillViewModel.withPackingList`) whenever its inputs — [machines] or
 * [pickOrder] — change.
 *
 * @property machines every machine with at least one tray, in the
 *   repository's meaningless fetch order. The tour's visit order is
 *   established at tour start (Task 8), not here.
 * @property warehouseStock `productId -> total units in the selected
 *   warehouse`. Empty before the first load, after a failed load, and for a
 *   warehouse that genuinely holds nothing. There is deliberately no
 *   separate "stock loaded" flag: every [RefillTourLogic] entry point that
 *   takes a `stockLoaded` argument short-circuits on
 *   `!stockLoaded || warehouseStock.isEmpty()`, so an empty map already
 *   behaves exactly like "not loaded" — callers pass
 *   `stockLoaded = warehouseStock.isNotEmpty()`. Use [RefillUiState.stockLoaded]
 *   rather than re-deriving that expression at each call site.
 * @property pickOrder `productId -> index` in warehouse walk order. Empty
 *   when the warehouse has no configured layout *or* when reading it failed;
 *   either way the packing list falls back to quantity-descending sorting.
 * @property packedItems `machineId -> productIds the driver packed for it`.
 * @property customQuantities `machineId -> productId -> pinned quantity`.
 * @property activeChip `null` = the "all machines" chip, otherwise a machineId.
 * @property currentMachineId the machine being refilled during
 *   [RefillStep.REFILL].
 * @property tourCompanyId the `company_id` for this tour's `activity_log`
 *   rows, resolved **once** when the tour starts (or resumes) rather than
 *   once per written row: [RefillRepository.writeTourActivity] otherwise
 *   resolves it through the `get-my-organization` edge function on every
 *   call, i.e. once per machine, each time *after* that machine's tray
 *   write has already committed. `null` means the resolution failed — the
 *   repository then falls back to resolving it itself, so a null here costs
 *   round trips, never audit rows.
 * @property error a raw, non-localized message — either an exception's own
 *   text, or (paired with [refillFailedAttempts]) the underlying cause of a
 *   booking that exhausted its retries. Never a sentence this ViewModel
 *   formulated itself: composing the user-facing text is the UI's job (see
 *   [refillFailedAttempts]).
 * @property refillFailedAttempts set together with [error] when
 *   [confirmRefill] gives up after [MAX_REFILL_ATTEMPTS] failed
 *   `refill_machine_trays` calls; `null` in every other case, including when
 *   [error] carries a plain exception message. The UI (not this ViewModel)
 *   composes the localized "could not be saved after N attempts: …" sentence
 *   from this count plus [error] — this class must not format user-facing
 *   text itself, since it has no access to localized resources.
 */
data class RefillUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val step: RefillStep = RefillStep.PACKING,
    val machines: List<RefillMachine> = emptyList(),
    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouseId: String? = null,
    val warehouseStock: Map<String, Int> = emptyMap(),
    val pickOrder: Map<String, Int> = emptyMap(),
    val packedItems: Map<String, Set<String>> = emptyMap(),
    val customQuantities: Map<String, Map<String, Int>> = emptyMap(),
    val activeChip: String? = null,
    val packingList: List<CombinedPackingItem> = emptyList(),
    val currentMachineId: String? = null,
    val tourId: String = "",
    val tourCompanyId: String? = null,
    val tourLog: List<TourLogEntry> = emptyList(),
    val hasSavedTour: Boolean = false,
    val error: String? = null,
    val refillFailedAttempts: Int? = null,
)

/**
 * Derived rather than stored: a genuinely empty warehouse and a warehouse
 * whose stock hasn't loaded yet are otherwise indistinguishable from a
 * plain boolean field, so it is computed from [RefillUiState.warehouseStock]
 * instead — matching iOS's guard `selectedWarehouseId != nil && !warehouseStock.isEmpty`.
 */
val RefillUiState.stockLoaded: Boolean
    get() = warehouseStock.isNotEmpty()

/**
 * Drives the refill wizard. This half of it loads machines/trays,
 * warehouses, the selected warehouse's stock and its physical pick order,
 * and owns the state contract above. Ported from iOS
 * `RefillWizardViewModel.loadData` (`RefillWizardViewModel.swift:1213-1380`)
 * and `loadWarehouseStock` (L1381-1441).
 *
 * The pack actions (Task 7), `startTour` (Task 8) and the
 * refill/skip/summary/resume actions (Task 9) are added to this class by
 * those tasks; they all read and write the same [RefillUiState].
 *
 * No unit tests: the arithmetic this class orchestrates lives in
 * [RefillTourLogic] and is tested there. Anything worth testing that shows
 * up in a later task belongs in `RefillTourLogic`, not in a mock harness
 * around this ViewModel.
 */
class RefillViewModel : ViewModel() {

    /** Held as a plain field rather than a constructor parameter. */
    private val tourStore: TourStore = RefillTourStoreHolder.instance

    private val _uiState = MutableStateFlow(RefillUiState())
    val uiState: StateFlow<RefillUiState> = _uiState.asStateFlow()

    /**
     * Entry gate. The wizard's load runs **once per ViewModel lifetime**,
     * not on every tab re-selection: the screen's `LaunchedEffect(Unit)`
     * re-fires whenever the composable re-enters composition while the
     * ViewModel (scoped to the nav entry) survives — and on iOS exactly that
     * reset the refill view in the middle of a tour, restoring tour-start
     * stock over live values.
     *
     * Re-armed only by a *failed* initial load that left no state behind
     * (see [rearmEntryGateUnlessTourInMemory]), so returning to the tab
     * retries instead of being permanently skipped, and never while a tour
     * is in memory. `reset()` (Task 9) re-arms it deliberately so the next
     * visit loads fresh.
     */
    private var didRunInitialLoad = false

    /** Call from the screen on entry. No-op after the first call — see [didRunInitialLoad]. */
    fun loadDataIfNeeded() {
        if (didRunInitialLoad) return
        didRunInitialLoad = true
        loadData()
    }

    /**
     * Machines + trays, warehouses, then the **first** warehouse's stock and
     * pick order (iOS auto-selects the first warehouse the same way).
     *
     * This is the *initial* load only — it overwrites [RefillUiState.machines]
     * wholesale, which is also where `isPacked`/`isRefilled`/`isSkipped` and
     * every tray's `fillAmount` live. Calling it mid-tour would silently
     * discard the driver's in-progress fills and pack state. It therefore
     * no-ops unless the wizard is still at the pack step with no tour in
     * memory (see [isTourInMemory]) — it deliberately does **not** double as
     * a mid-tour pull-to-refresh; that needs a step-specific refresh (a
     * later task adds `refreshDuringPacking`/`refreshDuringRefill`,
     * mirroring iOS `RefillWizardViewModel.swift:1199-1211`).
     */
    fun loadData() {
        if (isTourInMemory(_uiState.value)) return
        viewModelScope.launch {
            val hasSavedTour = tourStore.hasSavedTour
            // `hasSavedTour = true` must only ever be written in the same
            // update as `isLoading = true`, never on its own — the resume
            // prompt in RefillWizardScreen is gated on `!isLoading` and
            // relies on that pairing to never fire mid-load, while `machines`
            // is about to be overwritten below.
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    refillFailedAttempts = null,
                    hasSavedTour = hasSavedTour
                )
            }

            RefillRepository.fetchRefillMachines().fold(
                onSuccess = { machines ->
                    // `withSyncedPackedState()` rather than `withPackingList()`:
                    // `isPacked` is *derived* from `packedItems`, and replacing
                    // `machines` wholesale drops it. `packedItems` survives a
                    // reload, so without the re-derivation every tick and
                    // quantity still renders as packed while the header counts
                    // zero packed machines and `startTour()` hard-returns.
                    // Defensive: nothing reaches this branch with a non-empty
                    // `packedItems` today, and no future caller should be able
                    // to break the invariant either.
                    _uiState.update { it.copy(machines = machines).withSyncedPackedState() }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    rearmEntryGateUnlessTourInMemory()
                    return@launch
                }
            )

            WarehouseRepository.fetchWarehouses().fold(
                onSuccess = { warehouses ->
                    _uiState.update { state ->
                        state.copy(
                            warehouses = warehouses,
                            selectedWarehouseId = state.selectedWarehouseId ?: warehouses.firstOrNull()?.id
                        )
                    }
                },
                onFailure = { e ->
                    // Machines did load, so the pack step can still render —
                    // but without a warehouse there is no stock cap, so the
                    // gate is re-armed to retry on the next entry (iOS does
                    // the same: the whole `loadData` catch re-arms).
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    rearmEntryGateUnlessTourInMemory()
                    return@launch
                }
            )

            _uiState.value.selectedWarehouseId?.let { loadWarehouseData(it) }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Switches the active warehouse and reloads its stock and pick order.
     * The selection is written immediately (the picker must react at once);
     * the fetched data is written only if the selection still matches when
     * the responses land — see [loadWarehouseData].
     */
    fun selectWarehouse(id: String) {
        if (_uiState.value.selectedWarehouseId == id) return
        _uiState.update { it.copy(selectedWarehouseId = id) }
        viewModelScope.launch { loadWarehouseData(id) }
    }

    /**
     * Retry path for the pack step's "no warehouse stock" banner. Re-fetches
     * the selected warehouse's stock totals and pick order — and nothing
     * else.
     *
     * Deliberately **not** [loadData]: that replaces [RefillUiState.machines]
     * wholesale, which is where `isPacked` lives, and reloading the machine
     * list is neither what failed nor what the driver needs. [selectWarehouse]
     * can't serve as the retry either — it early-returns for the
     * already-selected id — hence this separate entry point. The stale-
     * response guard lives in [loadWarehouseData] and still applies.
     *
     * With no warehouse selected — the *warehouses* fetch is what failed, or
     * the company has none — the warehouse list is fetched first, since there
     * would otherwise be nothing to load stock for. `machines` is never
     * touched on either path.
     */
    fun reloadWarehouseStock() {
        viewModelScope.launch {
            if (_uiState.value.selectedWarehouseId == null) {
                WarehouseRepository.fetchWarehouses().fold(
                    onSuccess = { warehouses ->
                        _uiState.update { state ->
                            state.copy(
                                warehouses = warehouses,
                                selectedWarehouseId = state.selectedWarehouseId
                                    ?: warehouses.firstOrNull()?.id
                            )
                        }
                    },
                    onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
                )
            }
            val warehouseId = _uiState.value.selectedWarehouseId ?: return@launch
            loadWarehouseData(warehouseId)
        }
    }

    /** Clears a surfaced error after the UI has shown it (e.g. via a snackbar). */
    fun clearError() {
        _uiState.update { it.copy(error = null, refillFailedAttempts = null) }
    }

    /**
     * Loads one warehouse's stock totals and pick order concurrently, then
     * writes **both** into the state in a single update — and only if
     * [warehouseId] is still the selected warehouse.
     *
     * Without that check a slow response for a warehouse the user has since
     * switched away from would overwrite the newer warehouse's stock (and
     * its pick order, and therefore the packing list's sort order) with
     * stale numbers, silently mis-capping every packing quantity. Both
     * halves of the result — including any error from the stock fetch — are
     * discarded together, because a stale error is as misleading as stale
     * stock.
     *
     * A failing pick order is **not** an error the user sees: it degrades to
     * an empty map and the packing list sorts by quantity instead, matching
     * iOS `fetchOrderedProductIdsOrEmpty`.
     */
    private suspend fun loadWarehouseData(warehouseId: String) {
        val (stockResult, pickOrderResult) = coroutineScope {
            val stock = async { RefillRepository.fetchWarehouseStockTotals(warehouseId) }
            val pickOrder = async { RefillRepository.fetchPickOrder(warehouseId) }
            stock.await() to pickOrder.await()
        }

        if (_uiState.value.selectedWarehouseId != warehouseId) return

        val pickOrder = pickOrderResult.getOrDefault(emptyMap())

        stockResult.fold(
            onSuccess = { stock ->
                _uiState.update {
                    it.copy(warehouseStock = stock, pickOrder = pickOrder).withPackingList()
                }
            },
            onFailure = { e ->
                // Stock is what caps packing quantities, so a failure here
                // is user-visible (iOS surfaces it too). The pick order that
                // did arrive is still applied.
                _uiState.update {
                    it.copy(pickOrder = pickOrder, error = e.message).withPackingList()
                }
            }
        )
    }

    /**
     * Re-derives [RefillUiState.packingList] from the two inputs it depends
     * on. Every update that changes `machines` or `pickOrder` must go
     * through this — including the ones later tasks add.
     */
    private fun RefillUiState.withPackingList(): RefillUiState =
        copy(packingList = RefillTourLogic.buildCombinedPackingList(machines, pickOrder))

    /**
     * Re-opens the entry gate after a failed load, unless a tour is already
     * in memory — a running tour must never be reloaded out from under the
     * driver.
     */
    private fun rearmEntryGateUnlessTourInMemory() {
        if (!isTourInMemory(_uiState.value)) didRunInitialLoad = false
    }

    /**
     * Whether the wizard holds state worth protecting from a wholesale
     * reload: the driver has left the pack step, or a tour id has already
     * been assigned. Shared by [loadData]'s mid-tour guard and
     * [rearmEntryGateUnlessTourInMemory] so the two can't drift apart.
     */
    private fun isTourInMemory(state: RefillUiState): Boolean =
        state.step != RefillStep.PACKING || state.tourId.isNotEmpty()

    // ---------------------------------------------------------------------
    // Pack step (Task 7). All quantity/capping/commitment math lives in
    // [RefillTourLogic] and is called with named arguments throughout this
    // section — several of its functions take multiple consecutive
    // same-typed map parameters, and a transposition compiles silently.
    // Ported from iOS `RefillWizardViewModel.swift` L613-945.
    // ---------------------------------------------------------------------

    /**
     * Quantity to show in the Pack UI for a machine-product pair. Delegates
     * to [RefillTourLogic.displayQuantity]. Ported from iOS `displayQuantity`.
     */
    fun displayQuantity(machineId: String, productId: String): Int {
        val state = _uiState.value
        return RefillTourLogic.displayQuantity(
            machines = state.machines,
            machineId = machineId,
            productId = productId,
            packedItems = state.packedItems,
            customQuantities = state.customQuantities,
            warehouseStock = state.warehouseStock,
            stockLoaded = state.stockLoaded
        )
    }

    /**
     * Max quantity a machine could pack for a product, capped by tray
     * capacity and remaining warehouse stock. Delegates to
     * [RefillTourLogic.maxPackingQuantity]. Ported from iOS `maxPackingQuantity`.
     */
    fun maxPackingQuantity(machineId: String, productId: String): Int {
        val state = _uiState.value
        return RefillTourLogic.maxPackingQuantity(
            machines = state.machines,
            machineId = machineId,
            productId = productId,
            packedItems = state.packedItems,
            customQuantities = state.customQuantities,
            warehouseStock = state.warehouseStock,
            stockLoaded = state.stockLoaded
        )
    }

    /** Whether a specific machine-product pair has been packed. Ported from iOS `isMachinePacked`. */
    fun isPacked(machineId: String, productId: String): Boolean =
        _uiState.value.packedItems[machineId]?.contains(productId) == true

    /**
     * Whether a machine-product pair is out of remaining warehouse stock.
     * Delegates to [RefillTourLogic.isOutOfStockForMachine]. Ported from iOS
     * `isOutOfStockForMachine`.
     */
    fun isOutOfStockForMachine(machineId: String, productId: String): Boolean {
        val state = _uiState.value
        return RefillTourLogic.isOutOfStockForMachine(
            machines = state.machines,
            machineId = machineId,
            productId = productId,
            packedItems = state.packedItems,
            customQuantities = state.customQuantities,
            warehouseStock = state.warehouseStock,
            stockLoaded = state.stockLoaded
        )
    }

    /**
     * Product-level (not machine-scoped) out-of-stock check used only to
     * decide whether a picklist row is worth showing at all. Ported from iOS
     * `isOutOfWarehouseStock(productId:)` (distinct from the machine-scoped
     * [isOutOfStockForMachine] above, which also short-circuits `false` for
     * a machine that already packed its allocation).
     */
    private fun isOutOfWarehouseStock(state: RefillUiState, productId: String): Boolean {
        if (!state.stockLoaded) return false
        return RefillTourLogic.remainingWarehouseStock(
            machines = state.machines,
            productId = productId,
            packedItems = state.packedItems,
            customQuantities = state.customQuantities,
            warehouseStock = state.warehouseStock
        ) <= 0
    }

    /**
     * [RefillUiState.packingList], with rows hidden that have no warehouse
     * stock left **and** nothing packed yet — they'd just waste screen
     * space. A partially-packed row (or a row with any machine need already
     * packed) stays visible so the driver can still correct it. Ported from
     * iOS `visibleCombinedPackingList`.
     */
    fun visiblePackingList(): List<CombinedPackingItem> {
        val state = _uiState.value
        return state.packingList.filter { item ->
            val anyPacked = item.machineNeeds.any { need -> isPacked(need.machineId, item.productId) }
            anyPacked || !isOutOfWarehouseStock(state, item.productId)
        }
    }

    /**
     * Sets which chip filters the Pack step's list: `null` is the "all
     * machines" chip, otherwise one machine's chip. Ported from iOS
     * `activeChip` assignment (`ChipFilter.all` / `.machine(id)`).
     */
    fun selectChip(machineId: String?) {
        _uiState.update { it.copy(activeChip = machineId) }
    }

    /**
     * Potential box size for a chip: sum of [displayQuantity] over every
     * `(machine, product)` pair the chip covers, skipping pairs that are
     * out-of-stock and not yet packed (they don't render in the picklist
     * either). `null` sums across every machine. Ported from iOS
     * `chipItemCount`.
     */
    fun chipItemCount(machineId: String?): Int {
        val state = _uiState.value
        val scope = if (machineId == null) state.machines.map { it.machine.id }.toSet() else setOf(machineId)
        var total = 0
        for (item in state.packingList) {
            for (need in item.machineNeeds) {
                if (need.machineId !in scope) continue
                val packed = isPacked(need.machineId, item.productId)
                val outOfStock = isOutOfStockForMachine(need.machineId, item.productId)
                if (outOfStock && !packed) continue
                total += displayQuantity(need.machineId, item.productId)
            }
        }
        return total
    }

    /**
     * True once every needed `(machine, product)` pair for a chip is both
     * packed and packed at the highest quantity currently possible (a
     * warehouse-capped pack still counts as "done" — the driver has done all
     * they can). `null` requires every machine chip to be fully packed.
     * Ported from iOS `chipIsFullyPacked`.
     */
    fun chipIsFullyPacked(machineId: String?): Boolean {
        val state = _uiState.value
        if (machineId == null) {
            if (state.machines.isEmpty()) return false
            return state.machines.all { chipIsFullyPacked(it.machine.id) }
        }
        var hadAnyNeed = false
        for (item in state.packingList) {
            val need = item.machineNeeds.find { it.machineId == machineId } ?: continue
            val packed = isPacked(machineId, item.productId)
            val outOfStock = isOutOfStockForMachine(machineId, item.productId)
            if (outOfStock && !packed) continue
            hadAnyNeed = true
            if (!packed) return false
            val qty = displayQuantity(machineId, item.productId)
            val maxQty = maxPackingQuantity(machineId, item.productId)
            if (qty < minOf(need.quantity, maxQty)) return false
        }
        return hadAnyNeed
    }

    /**
     * Sets a custom packing quantity for a machine-product pair, clamped to
     * [RefillTourLogic.maxPackingQuantity]. Does not touch [RefillUiState.machines]
     * or `packedItems`, so `isPacked` is not re-derived here. Ported from iOS
     * `setPackingQuantity`.
     */
    fun setPackingQuantity(machineId: String, productId: String, quantity: Int) {
        _uiState.update { it.withPinnedQuantity(machineId, productId, quantity).withPackingList() }
    }

    /**
     * Toggles the packed state for one machine-product pair, skipping the
     * pack (silently) if the warehouse has no remaining stock for it. Pins
     * the quantity on pack, clears the pinned quantity on unpack. Re-derives
     * every machine's `isPacked`. Ported from iOS `togglePackedForMachine`.
     */
    fun togglePackedForMachine(machineId: String, productId: String) {
        _uiState.update { it.withTogglePacked(machineId, productId).withSyncedPackedState() }
    }

    /**
     * Toggles the packed state for a product across every machine that needs
     * it: unpacks all if every one of them is already packed, else packs
     * every one of them that isn't out of stock (stock-aware, in picklist
     * order — an earlier machine in the loop can exhaust the remaining
     * warehouse stock for a later one). Ported from iOS `togglePackedAll`.
     */
    fun togglePackedAll(productId: String) {
        _uiState.update { state ->
            val item = state.packingList.find { it.productId == productId } ?: return@update state
            val allPacked = item.machineNeeds.all { need ->
                state.packedItems[need.machineId]?.contains(productId) == true
            }
            var next = state
            for (need in item.machineNeeds) {
                next = if (allPacked) {
                    next.withUnpacked(need.machineId, productId)
                } else {
                    next.withPackedIfInStock(need.machineId, productId)
                }
            }
            next.withSyncedPackedState()
        }
    }

    /**
     * Packs every needed product for every machine that isn't already
     * packed, skipping (not aborting on) out-of-stock pairs. Ported from iOS
     * `packEverything`.
     */
    fun packEverything() {
        _uiState.update { state ->
            var next = state
            for (item in state.packingList) {
                for (need in item.machineNeeds) {
                    next = next.withPackedIfInStock(need.machineId, item.productId)
                }
            }
            next.withSyncedPackedState()
        }
    }

    /**
     * Packs every needed product for one machine, skipping (not aborting on)
     * out-of-stock pairs and leaving already-packed pairs untouched. Ported
     * from iOS `packAllForMachine`.
     */
    fun packAllForMachine(machineId: String) {
        _uiState.update { state ->
            var next = state
            for (item in state.packingList) {
                if (item.machineNeeds.none { it.machineId == machineId }) continue
                next = next.withPackedIfInStock(machineId, item.productId)
            }
            next.withSyncedPackedState()
        }
    }

    /**
     * Writes [quantity] into `customQuantities`, clamped to
     * [RefillTourLogic.maxPackingQuantity]. Shared by [setPackingQuantity]
     * and [withPinnedCurrentQuantity] so the clamp lives in exactly one
     * place. Ported from iOS `setPackingQuantity`'s clamp.
     */
    private fun RefillUiState.withPinnedQuantity(machineId: String, productId: String, quantity: Int): RefillUiState {
        val maxQty = RefillTourLogic.maxPackingQuantity(
            machines = machines,
            machineId = machineId,
            productId = productId,
            packedItems = packedItems,
            customQuantities = customQuantities,
            warehouseStock = warehouseStock,
            stockLoaded = stockLoaded
        )
        val clamped = quantity.coerceIn(0, maxQty)
        val machineMap = (customQuantities[machineId] ?: emptyMap()) + (productId to clamped)
        return copy(customQuantities = customQuantities + (machineId to machineMap))
    }

    /**
     * Pins [RefillTourLogic.packingQuantity]'s current value (the live tray
     * deficit, unless already pinned) into `customQuantities` at the moment
     * of packing. Without this, a sale during the tour widens the deficit,
     * the displayed "packed" number drifts under the driver's fingers, and a
     * warehouse that can only partly satisfy the new figure leaves the
     * driver short. Ported from iOS `pinPackingQuantity`.
     */
    private fun RefillUiState.withPinnedCurrentQuantity(machineId: String, productId: String): RefillUiState {
        val machine = machines.find { it.machine.id == machineId } ?: return this
        val currentQty = RefillTourLogic.packingQuantity(
            machine = machine,
            productId = productId,
            customQuantities = customQuantities
        )
        return withPinnedQuantity(machineId, productId, currentQty)
    }

    /** Removes a pinned quantity so a later re-pack recalculates from the tray deficit. Ported from iOS `togglePackedForMachine`'s unpack branch. */
    private fun RefillUiState.withClearedPinnedQuantity(machineId: String, productId: String): RefillUiState {
        val machineMap = customQuantities[machineId] ?: return this
        if (productId !in machineMap) return this
        return copy(customQuantities = customQuantities + (machineId to (machineMap - productId)))
    }

    /** Adds [productId] to [machineId]'s packed set and pins its quantity, unless out of warehouse stock. */
    private fun RefillUiState.withPackedIfInStock(machineId: String, productId: String): RefillUiState {
        val outOfStock = RefillTourLogic.isOutOfStockForMachine(
            machines = machines,
            machineId = machineId,
            productId = productId,
            packedItems = packedItems,
            customQuantities = customQuantities,
            warehouseStock = warehouseStock,
            stockLoaded = stockLoaded
        )
        if (outOfStock) return this
        val currentSet = packedItems[machineId] ?: emptySet()
        if (productId in currentSet) return this
        return copy(packedItems = packedItems + (machineId to (currentSet + productId)))
            .withPinnedCurrentQuantity(machineId, productId)
    }

    /** Removes [productId] from [machineId]'s packed set and clears its pinned quantity. */
    private fun RefillUiState.withUnpacked(machineId: String, productId: String): RefillUiState {
        val currentSet = packedItems[machineId] ?: emptySet()
        if (productId !in currentSet) return this
        return copy(packedItems = packedItems + (machineId to (currentSet - productId)))
            .withClearedPinnedQuantity(machineId, productId)
    }

    /**
     * Toggles one machine-product pair: unpacks (clearing the pin) if
     * already packed, else packs it unless out of stock (pinning the
     * quantity). Shared plumbing for [togglePackedForMachine].
     */
    private fun RefillUiState.withTogglePacked(machineId: String, productId: String): RefillUiState {
        val currentSet = packedItems[machineId] ?: emptySet()
        return if (productId in currentSet) {
            withUnpacked(machineId, productId)
        } else {
            withPackedIfInStock(machineId, productId)
        }
    }

    // ---------------------------------------------------------------------
    // Tour start (Task 8). Ported from iOS `startTour`
    // (`RefillWizardViewModel.swift:1652-1740`) and `deductWarehouseStock`
    // (L1741-1800).
    // ---------------------------------------------------------------------

    /**
     * Starts the tour: mints a tour id, narrows every machine's trays to
     * what was actually packed, charges the warehouse for exactly those
     * goods, writes the `tour_started` feed row, orders the machines by
     * urgency and moves to [RefillStep.REFILL].
     *
     * The order of the two writes is deliberate and matches iOS and the
     * PWA: the FIFO deductions run **before** the `tour_started` activity
     * row, so a start that dies mid-way never leaves an orphaned feed entry
     * announcing a tour that was never charged. A *failed* deduction, by
     * contrast, does not block anything — [RefillRepository.deductForTour]
     * swallows per-deduction errors on purpose (the tour must not get stuck
     * because the warehouse ledger had a hiccup).
     *
     * The deduction set comes from [RefillTourLogic.buildDeductions] and
     * from nowhere else. That function charges only the intersection of
     * `packedItems` and each machine's real tray products — never the tray
     * deficits — which is the fix for the bug that motivated this phase
     * (iOS billed the warehouse for goods the driver never packed, ~334
     * units across 53 tours). Rebuilding that list here, in any form, would
     * reintroduce it.
     */
    fun startTour() {
        val snapshot = _uiState.value
        if (snapshot.machines.none { it.isPacked }) return
        // Re-entrancy guard: a second tap (or a tap on a resumed tour)
        // would mint a second tour id and charge the warehouse twice for
        // the same box of goods. Not in iOS, which relies on `isSaving`
        // disabling its button; double-charging is the exact failure class
        // this phase exists to prevent, so it is enforced here too.
        // Task 9's reset path must clear BOTH tourId and step, or this guard
        // latches shut and startTour() silently does nothing forever.
        if (isTourInMemory(snapshot)) return

        val tourId = UUID.randomUUID().toString()

        // The id is written into the state *before* anything that records
        // it runs, so the deductions' `tour_id` metadata, the activity row
        // and the persisted snapshot can never disagree about which tour
        // they belong to.
        val started = _uiState.updateAndGet { state ->
            state.copy(
                isSaving = true,
                tourId = tourId,
                tourLog = emptyList(),
                machines = RefillTourLogic.applyTourInclusion(
                    machines = state.machines,
                    packedItems = state.packedItems,
                    customQuantities = state.customQuantities
                )
            ).withPackingList()
        }

        viewModelScope.launch {
            val warehouseId = started.selectedWarehouseId
            if (warehouseId != null) {
                val deductions = RefillTourLogic.buildDeductions(
                    machines = started.machines,
                    packedItems = started.packedItems,
                    customQuantities = started.customQuantities
                )
                // Result deliberately ignored: a warehouse-ledger failure
                // must not abort a tour the driver has already packed for.
                RefillRepository.deductForTour(
                    warehouseId = warehouseId,
                    tourId = tourId,
                    deductions = deductions
                )
            }

            // Resolved once here and carried in the state for every later
            // row this tour writes (refills, skips) — see
            // [RefillUiState.tourCompanyId]. Deliberately after the
            // deductions so the "charge before you announce" ordering above
            // is untouched.
            val companyId = resolveTourCompanyId()

            // `activity_log.metadata` is a typed cross-client contract
            // (PWA/iOS/Android). These four keys and their types are what
            // this app's own dashboard reads back in
            // `models/ActivityFeed.kt:40-42` and
            // `data/ActivityFeedBuilder.kt:101-115` — a misspelling here
            // renders as an empty tour card. `tour_id` is added by
            // [RefillRepository.writeTourActivity] itself.
            val tourMachines = started.machines.filter { it.isPacked }
            val warehouseName = started.warehouses.find { it.id == warehouseId }?.name
            RefillRepository.writeTourActivity(
                action = "tour_started",
                machineId = null,
                machineName = null,
                tourId = tourId,
                warehouseId = warehouseId,
                extra = buildMap {
                    put("machine_count", JsonPrimitive(tourMachines.size))
                    put("machine_ids", JsonArray(tourMachines.map { JsonPrimitive(it.machine.id) }))
                    put("machine_names", JsonArray(tourMachines.map { JsonPrimitive(it.machine.displayName) }))
                    warehouseName?.let { put("warehouse_name", JsonPrimitive(it)) }
                },
                companyId = companyId
            )

            val next = _uiState.updateAndGet { state ->
                val ordered = RefillTourLogic.sortByVisitOrder(state.machines)
                state.copy(
                    machines = ordered,
                    step = RefillStep.REFILL,
                    currentMachineId = ordered.firstOrNull { it.isUnfinishedTourStop }?.machine?.id,
                    tourCompanyId = companyId,
                    isSaving = false
                ).withPackingList()
            }
            tourStore.save(next.toPersistedTourState())
        }
    }

    /** Packed, and neither refilled nor skipped yet — a stop the tour still owes a visit. */
    private val RefillMachine.isUnfinishedTourStop: Boolean
        get() = isPacked && !isRefilled && !isSkipped

    /**
     * Snapshot for [TourStore]. [PersistedTourState.currentMachineIndex] is
     * an index into the *remaining* machines (iOS `remainingMachines`), not
     * into [RefillUiState.machines] — resolved from [RefillUiState.currentMachineId]
     * so the two can't drift; it is 0 at tour start, as on iOS.
     */
    private fun RefillUiState.toPersistedTourState(): PersistedTourState {
        val remaining = machines.filter { it.isUnfinishedTourStop }
        return PersistedTourState(
            step = step,
            machines = machines,
            // -1 (no match) collapses to the first remaining stop. Unreachable
            // at tour start, where currentMachineId comes from this very list;
            // on a later save a drifted id would resume the driver at the
            // first remaining machine rather than fail loudly.
            currentMachineIndex = remaining
                .indexOfFirst { it.machine.id == currentMachineId }
                .coerceAtLeast(0),
            selectedWarehouseId = selectedWarehouseId,
            tourId = tourId,
            tourLog = tourLog,
            // Placeholder only: TourStore.save() re-stamps this from its own
            // clock, so the write and the expiry check share one time source.
            savedAt = ""
        )
    }

    /**
     * Re-derives every machine's `isPacked`: packed as soon as at least one
     * of its still-needed products (a product with `deficit > 0` in one of
     * its trays) is in `packedItems`. Also refreshes [RefillUiState.packingList]
     * since this changes `machines`. Ported from iOS `syncMachinePackedState`.
     */
    private fun RefillUiState.withSyncedPackedState(): RefillUiState {
        val newMachines = machines.map { rm ->
            val packedForMachine = packedItems[rm.machine.id] ?: emptySet()
            val neededProductIds = rm.trays.filter { it.deficit > 0 }.mapNotNull { it.tray.productId }.toSet()
            rm.copy(isPacked = neededProductIds.any { it in packedForMachine })
        }
        return copy(machines = newMachines).withPackingList()
    }

    // ---------------------------------------------------------------------
    // Refill step, skip, summary and resume (Task 9). Ported from iOS
    // `adjustFillAmount`/`fillTrayToCapacity`/`fillAllTrays`
    // (`RefillWizardViewModel.swift:1801-1828`), `applyRefillRPC` (L1848-1868),
    // `confirmRefill` (L1875-1950), `recordRefillSuccess` (L1952-1990),
    // `skipMachine` (L1991-2015), `advanceToNextMachine` (L2016-2024) and the
    // persistence block (L305-437).
    // ---------------------------------------------------------------------

    /**
     * Sets one tray's fill amount, clamped to what still fits
     * (`capacity - currentStock`). Ported from iOS `adjustFillAmount`.
     *
     * The clamp is written as `coerceAtMost(...).coerceAtLeast(0)` rather
     * than `coerceIn(0, maxFill)`: an over-stocked tray has a negative
     * `maxFill`, and `coerceIn` throws on an inverted range where iOS's
     * `max(0, min(maxFill, amount))` quietly yields 0.
     */
    fun adjustFillAmount(machineId: String, trayId: String, amount: Int) {
        _uiState.update { state ->
            state.withTrayFills(machineId, trayIds = setOf(trayId)) { tray ->
                amount.coerceAtMost(tray.maxFill).coerceAtLeast(0)
            }
        }
    }

    /** Fills one tray to capacity. Ported from iOS `fillTrayToCapacity`. */
    fun fillTrayToCapacity(machineId: String, trayId: String) {
        _uiState.update { state ->
            state.withTrayFills(machineId, trayIds = setOf(trayId)) { tray ->
                tray.maxFill.coerceAtLeast(0)
            }
        }
    }

    /**
     * Fills every *packed* tray of a machine to capacity. Ported from iOS
     * `fillAllTrays`.
     *
     * Restricted to `isInTour` trays: `RefillTourLogic.applyTourInclusion`
     * sets `isInTour = false, fillAmount = 0` for a tray whose product the
     * driver did not pack, and such a tray can still have `deficit > 0` /
     * `maxFill > 0`. Passing `trayIds = null` to [withTrayFills] would fill
     * those too — booking stock into trays that were never physically
     * loaded, and that the refill step does not even render a card for.
     */
    fun fillAllTrays(machineId: String) {
        _uiState.update { state ->
            val machine = state.machines.find { it.machine.id == machineId } ?: return@update state
            val inTourTrayIds = machine.trays.filter { it.isInTour }.map { it.tray.id }.toSet()
            state.withTrayFills(machineId, trayIds = inTourTrayIds) { tray -> tray.maxFill.coerceAtLeast(0) }
        }
    }

    /**
     * Makes [machineId] the machine the refill step shows. Ignores machines
     * the tour has already finished with (refilled or skipped) and machines
     * that were never packed — jumping to one would show the driver a stop
     * that [advanceToNextMachine] is not going to come back to. Persisted,
     * because the resume snapshot carries the current stop.
     */
    fun selectMachine(machineId: String) {
        val state = _uiState.value
        val target = state.machines.find { it.machine.id == machineId } ?: return
        if (!target.isUnfinishedTourStop) return
        if (state.currentMachineId == machineId) return
        val next = _uiState.updateAndGet { it.copy(currentMachineId = machineId) }
        tourStore.save(next.toPersistedTourState())
    }

    /**
     * Books one machine's fills through the atomic `refill_machine_trays`
     * RPC, then records the visit and moves on.
     *
     * Three attempts, sleeping 1 s before the second and 3 s before the
     * third. The retry is deliberately **blind** — no error classification:
     * the RPC dedupes per `(tour_id, tray_id)` (see the header comment of
     * `20260511120000_refill_machine_trays_rpc.sql`), so a retry of a call
     * that in fact committed is a no-op rather than a double booking. That
     * safety net is exactly why [RefillUiState.tourId] has to survive a
     * resume unchanged.
     *
     * After three failures the machine is **not** marked refilled, no tour
     * log entry and no audit row are written and the step does not change —
     * only [RefillUiState.error] is set, so the driver can confirm the same
     * machine again cleanly. (The pre-Task-9 Android code discarded the
     * write result and advanced regardless; that is the bug this removes.)
     *
     * A machine where no tray needs a write still records the visit — tour
     * log entry with 0/0 plus an `activity_log` row — so the history shows
     * the machine was opened. Ported from iOS `confirmRefill`.
     */
    fun confirmRefill(machineId: String) {
        val snapshot = _uiState.value
        val machine = snapshot.machines.find { it.machine.id == machineId } ?: return
        // Re-entrancy guards. iOS disables only its *Confirm* button on
        // `isSaving` (`RefillStepView.swift:447`); a second tap here would
        // write a second tour-log entry and a second audit row for one
        // visit, and the empty-tray path below never sets `isSaving` at all,
        // so the UI cannot block it on its own.
        //
        // These guards depend on `viewModelScope` dispatching through
        // `Dispatchers.Main.immediate`: the first state write inside the
        // launched block runs synchronously, before this function returns,
        // so a second tap in the same frame already sees it. Inserting a
        // suspending call ahead of that first write reopens the window —
        // and no test would catch it.
        if (snapshot.isSaving) return
        if (machine.isRefilled || machine.isSkipped) return

        // `isInTour` is defensive here, not load-bearing today: the fill
        // actions above no longer set `fillAmount > 0` on a not-packed tray.
        // But this is the single place that books stock, so it must not
        // trust that invariant transitively — a future fill-setting caller
        // that forgets the `isInTour` filter must not be able to book stock
        // into a tray the driver never packed (see `fillAllTrays` and
        // `RefillTourLogic.applyTourInclusion`, which is what makes a
        // not-packed tray `isInTour = false` in the first place).
        val traysToRefill = machine.trays.filter { it.fillAmount > 0 && it.isInTour }

        viewModelScope.launch {
            if (traysToRefill.isEmpty()) {
                // Clear a stale error from a previous machine — a visit that
                // needed no stock write is still a success, and leaving the
                // old message (or a stale [RefillUiState.refillFailedAttempts]
                // from an earlier failed confirm on this same machine) up
                // makes it read as a failure.
                _uiState.update { it.copy(error = null, refillFailedAttempts = null) }
                recordRefillSuccess(
                    machineId = machineId,
                    traysSnapshot = emptyList(),
                    traysCount = 0,
                    itemsAdded = 0
                )
                return@launch
            }

            // Clears any [RefillUiState.refillFailedAttempts] left by a
            // previous failed confirm on this same machine — this is a fresh
            // attempt, not a continuation of that failure.
            _uiState.update { it.copy(isSaving = true, error = null, refillFailedAttempts = null) }

            val payload = traysToRefill.map {
                RefillTrayPayload(trayId = it.tray.id, fillAmount = it.fillAmount)
            }
            var lastError: Throwable? = null

            for (attempt in 1..MAX_REFILL_ATTEMPTS) {
                if (attempt > 1) delay(REFILL_BACKOFF_MS[attempt - 2])

                val result = RefillRepository.refillMachineTrays(
                    machineId = machineId,
                    tourId = snapshot.tourId,
                    trays = payload
                )
                val rows = result.getOrElse { e ->
                    lastError = e
                    null
                } ?: continue

                // Server-authoritative numbers only: the local `fillAmount`
                // is what was *requested*, `new_stock` is what the machine
                // actually holds now (a concurrent sale, a clamp, or a
                // deduped replay all make the two differ).
                _uiState.update { it.withMirroredStock(machineId, rows) }
                val itemsAdded = rows.sumOf { (it.newStock - it.oldStock).coerceAtLeast(0) }

                recordRefillSuccess(
                    machineId = machineId,
                    traysSnapshot = traysToRefill,
                    traysCount = rows.size,
                    itemsAdded = itemsAdded
                )
                _uiState.update { it.copy(isSaving = false) }
                return@launch
            }

            // All attempts failed: the machine stays an unfinished tour stop
            // and the step stays put, so the same machine can be confirmed
            // again. `error` carries only the raw cause (or null — the UI
            // supplies its own "unknown error" fallback text);
            // `refillFailedAttempts` is the signal that tells the UI to wrap
            // it in the localized "could not be saved after N attempts"
            // sentence rather than showing it as a bare exception message.
            _uiState.update {
                it.copy(
                    isSaving = false,
                    error = lastError?.message,
                    refillFailedAttempts = MAX_REFILL_ATTEMPTS
                )
            }
        }
    }

    /**
     * Marks [machineId] skipped, logs the skip and moves on. The
     * `stock_refill_tour_skip` activity row carries **no** extra metadata,
     * matching iOS and the PWA. Ported from iOS `skipMachine`.
     */
    fun skipMachine(machineId: String) {
        val snapshot = _uiState.value
        val machine = snapshot.machines.find { it.machine.id == machineId } ?: return
        // Same guards as `confirmRefill`, and `isSaving` matters most here:
        // neither iOS nor this app's UI disables Skip while a confirm sits in
        // its 1s/3s retry window (`RefillStepView.swift:416-425` has no
        // `.disabled`), so a Skip landing mid-retry would mark the machine
        // skipped, log it, advance — and then the succeeding RPC would log the
        // same visit a second time as refilled. One machine, counted as both.
        if (snapshot.isSaving) return
        if (machine.isRefilled || machine.isSkipped) return

        viewModelScope.launch {
            val state = _uiState.updateAndGet { current ->
                current.copy(
                    machines = current.machines.map {
                        if (it.machine.id == machineId) it.copy(isSkipped = true) else it
                    },
                    tourLog = current.tourLog + TourLogEntry(
                        machineId = machineId,
                        machineName = machine.machine.displayName,
                        traysRefilled = 0,
                        totalAdded = 0,
                        skipped = true
                    )
                ).withPackingList()
            }

            RefillRepository.writeTourActivity(
                action = "stock_refill_tour_skip",
                machineId = machineId,
                machineName = machine.machine.displayName,
                tourId = state.tourId,
                warehouseId = state.selectedWarehouseId,
                extra = emptyMap(),
                companyId = state.tourCompanyId
            )

            advanceToNextMachine()
        }
    }

    /**
     * Restores a tour the app was killed in the middle of. Everything the
     * running tour needs comes back, **including [RefillUiState.tourId]** —
     * without it a post-resume retry of a call that had in fact committed
     * would book a second time, because the RPC dedupes on
     * `(tour_id, tray_id)`.
     *
     * The persisted `currentMachineIndex` indexes the *remaining* stops of
     * the **urgency-sorted** tour list (the order [startTour] establishes via
     * [RefillTourLogic.sortByVisitOrder]), not the repository's fetch order —
     * so the machines are sorted first and only then indexed, or the driver
     * is sent to the wrong machine. An out-of-range index (the tour ended up
     * with fewer remaining stops than when it was saved) falls back to the
     * first remaining stop rather than leaving the refill step blank.
     *
     * Ported from iOS `resumeTour` (`RefillWizardViewModel.swift:369-403`).
     */
    fun resumeTour() {
        val saved = tourStore.load()
        if (saved == null) {
            _uiState.update { it.copy(hasSavedTour = false) }
            return
        }

        // A resumed tour is state worth protecting: closing the entry gate
        // stops a later tab re-entry from calling loadData() over it.
        didRunInitialLoad = true

        val ordered = RefillTourLogic.sortByVisitOrder(saved.machines)
        val remaining = ordered.filter { it.isUnfinishedTourStop }
        val current = remaining.getOrNull(saved.currentMachineIndex) ?: remaining.firstOrNull()

        _uiState.update { state ->
            state.copy(
                isLoading = false,
                isSaving = false,
                step = saved.step,
                machines = ordered,
                selectedWarehouseId = saved.selectedWarehouseId,
                tourId = saved.tourId,
                tourLog = saved.tourLog,
                currentMachineId = current?.machine?.id,
                hasSavedTour = false,
                error = null,
                refillFailedAttempts = null
            ).withPackingList()
        }

        // The rest of this tour still writes audit rows, so its company id is
        // resolved once here too — same reason as at tour start.
        viewModelScope.launch {
            val companyId = resolveTourCompanyId()
            _uiState.update { it.copy(tourCompanyId = companyId) }
        }
    }

    /** Throws away the saved tour without resuming it. Ported from iOS `clearSavedTour`. */
    fun discardSavedTour() {
        tourStore.clear()
        _uiState.update { it.copy(hasSavedTour = false) }
    }

    /**
     * Drops the whole wizard back to a clean slate: no tour in memory, no
     * saved tour on disk, entry gate re-armed so the next visit loads fresh.
     *
     * Both inputs of [isTourInMemory] — `step` and `tourId` — must come back
     * to their defaults here, which is why the state is replaced wholesale
     * rather than patched field by field: leaving either one set latches
     * [startTour]'s re-entrancy guard shut and it would silently never start
     * a tour again. Ported from iOS `reset`.
     */
    fun reset() {
        tourStore.clear()
        didRunInitialLoad = false
        _uiState.value = RefillUiState()
    }

    /**
     * Shared post-success bookkeeping: mark refilled, append the tour log
     * entry, write the audit row, advance (which persists). Ported from iOS
     * `recordRefillSuccess`.
     *
     * [traysCount] and [itemsAdded] are the server's numbers (row count and
     * `new_stock - old_stock`), passed in by [confirmRefill]; [traysSnapshot]
     * is the local request, used only for the human-readable `products`
     * breakdown, exactly as on iOS.
     */
    private suspend fun recordRefillSuccess(
        machineId: String,
        traysSnapshot: List<RefillTray>,
        traysCount: Int,
        itemsAdded: Int
    ) {
        val machineName = _uiState.value.machines
            .find { it.machine.id == machineId }?.machine?.displayName.orEmpty()

        val state = _uiState.updateAndGet { current ->
            current.copy(
                machines = current.machines.map {
                    if (it.machine.id == machineId) it.copy(isRefilled = true) else it
                },
                tourLog = current.tourLog + TourLogEntry(
                    machineId = machineId,
                    machineName = machineName,
                    traysRefilled = traysCount,
                    totalAdded = itemsAdded,
                    skipped = false
                )
            ).withPackingList()
        }

        // `activity_log.metadata` is a typed cross-client contract
        // (PWA/iOS/Android). These keys and their types are what this app's
        // own dashboard reads back for a `stock_refill_tour` row:
        // `trays_refilled` and `total_added` as Int and `products` as an
        // array of `{product_name, quantity}` objects — see
        // `models/ActivityFeed.kt:37-38` and `:59-63`, consumed in
        // `data/ActivityFeedBuilder.kt:86-100`. `product_id` is written for
        // the PWA/iOS readers; this app's decoder ignores it. A misspelling
        // here renders as an empty refill card.
        RefillRepository.writeTourActivity(
            action = "stock_refill_tour",
            machineId = machineId,
            machineName = machineName,
            tourId = state.tourId,
            warehouseId = state.selectedWarehouseId,
            extra = buildMap {
                put("trays_refilled", JsonPrimitive(traysCount))
                put("total_added", JsonPrimitive(itemsAdded))
                put(
                    "products",
                    JsonArray(
                        traysSnapshot.map { refillTray ->
                            buildJsonObject {
                                put(
                                    "product_id",
                                    refillTray.tray.productId
                                        ?.let { JsonPrimitive(it) } ?: JsonNull
                                )
                                put("product_name", JsonPrimitive(refillTray.productLabel))
                                put("quantity", JsonPrimitive(refillTray.fillAmount))
                            }
                        }
                    )
                )
            },
            companyId = state.tourCompanyId
        )

        advanceToNextMachine()
    }

    /**
     * Moves to the first remaining stop of the tour, or to
     * [RefillStep.SUMMARY] when none is left, then persists.
     *
     * "First remaining" is meaningful because [RefillUiState.machines] is
     * already in visit order by the time the refill step runs — [startTour]
     * sorts it, and [resumeTour] re-sorts what it restores. iOS expresses
     * the same thing as `currentMachineIndex = 0`.
     */
    private fun advanceToNextMachine() {
        val next = _uiState.updateAndGet { state ->
            val remaining = state.machines.filter { it.isUnfinishedTourStop }
            state.copy(
                step = if (remaining.isEmpty()) RefillStep.SUMMARY else state.step,
                currentMachineId = remaining.firstOrNull()?.machine?.id
            )
        }
        tourStore.save(next.toPersistedTourState())
    }

    /**
     * The tour's `company_id`, resolved once per tour. `null` on failure —
     * [RefillRepository.writeTourActivity] then falls back to resolving it
     * per row, so a failure here costs edge-function round trips, not audit
     * rows.
     */
    private suspend fun resolveTourCompanyId(): String? =
        AuthRepository.fetchOrganization().getOrNull()?.organization?.id

    /**
     * Applies [fill] to one machine's trays — either the trays in [trayIds]
     * or, when it is `null`, all of them. Shared by the three fill actions
     * so the "find machine, find tray, rebuild the list" plumbing exists
     * once. Refreshes the packing list because it changes `machines`.
     */
    private fun RefillUiState.withTrayFills(
        machineId: String,
        trayIds: Set<String>?,
        fill: (RefillTray) -> Int
    ): RefillUiState {
        if (machines.none { it.machine.id == machineId }) return this
        val newMachines = machines.map { machine ->
            if (machine.machine.id != machineId) return@map machine
            machine.copy(
                trays = machine.trays.map { tray ->
                    if (trayIds != null && tray.tray.id !in trayIds) tray
                    else tray.copy(fillAmount = fill(tray))
                }
            )
        }
        return copy(machines = newMachines).withPackingList()
    }

    /**
     * Writes the RPC's returned `new_stock` into the machine's local trays.
     * Trays the server did not report back (nothing to apply, or already
     * applied under this tour id) are left untouched.
     */
    private fun RefillUiState.withMirroredStock(
        machineId: String,
        rows: List<TrayApplicationResult>
    ): RefillUiState {
        if (rows.isEmpty()) return this
        val byTrayId = rows.associateBy { it.trayId }
        val newMachines = machines.map { machine ->
            if (machine.machine.id != machineId) return@map machine
            machine.copy(
                trays = machine.trays.map { tray ->
                    val row = byTrayId[tray.tray.id]
                    if (row == null) tray
                    else tray.copy(tray = tray.tray.copy(currentStock = row.newStock))
                }
            )
        }
        return copy(machines = newMachines).withPackingList()
    }

    private companion object {
        /** Attempts per `refill_machine_trays` call — iOS `for attempt in 1...3`. */
        const val MAX_REFILL_ATTEMPTS = 3

        /** Sleep before attempt 2 and attempt 3 — iOS `backoffSeconds = [1.0, 3.0]`. */
        val REFILL_BACKOFF_MS = longArrayOf(1_000L, 3_000L)
    }
}

/**
 * Product name for a tray's audit-log line, falling back to the slot number
 * for an unassigned slot — the same value iOS writes (`Tray.productName`,
 * `ios/VMflow/Models/Tray.swift:43-45`). Deliberately **not** localized: it
 * is persisted metadata read back by three clients in whatever language the
 * reader is running, not UI text.
 */
private val RefillTray.productLabel: String
    get() = tray.products?.name ?: "Slot ${tray.itemNumber}"
