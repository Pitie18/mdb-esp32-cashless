package xyz.vmflow.ui.refill

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.vmflow.VMflowApp
import xyz.vmflow.data.KeyValueStore
import xyz.vmflow.data.RefillRepository
import xyz.vmflow.data.RefillTourLogic
import xyz.vmflow.data.TourStore
import xyz.vmflow.data.WarehouseRepository
import xyz.vmflow.models.CombinedPackingItem
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillStep
import xyz.vmflow.models.TourLogEntry
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
 *   `stockLoaded = warehouseStock.isNotEmpty()`.
 * @property pickOrder `productId -> index` in warehouse walk order. Empty
 *   when the warehouse has no configured layout *or* when reading it failed;
 *   either way the packing list falls back to quantity-descending sorting.
 * @property packedItems `machineId -> productIds the driver packed for it`.
 * @property customQuantities `machineId -> productId -> pinned quantity`.
 * @property activeChip `null` = the "all machines" chip, otherwise a machineId.
 * @property currentMachineId the machine being refilled during
 *   [RefillStep.REFILL].
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
    val tourLog: List<TourLogEntry> = emptyList(),
    val hasSavedTour: Boolean = false,
    val error: String? = null,
)

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

    /**
     * Not a constructor parameter: `viewModel()` instantiates through the
     * no-arg constructor, and Kotlin does **not** emit one for a class whose
     * only parameter has a default value (verified with `javap` — the
     * compiled class carries `(TourStore)` and the synthetic
     * `(TourStore, int, DefaultConstructorMarker)` only), so an injected
     * seam here would crash on entering the refill tab. No DI framework in
     * this app, and no ViewModel unit tests that would need to swap it.
     */
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
     * Deliberately public so a later pull-to-refresh can force a reload
     * without going through the entry gate.
     */
    fun loadData() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, hasSavedTour = tourStore.hasSavedTour)
            }

            RefillRepository.fetchRefillMachines().fold(
                onSuccess = { machines ->
                    _uiState.update { it.copy(machines = machines).withPackingList() }
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

    /** Clears a surfaced error after the UI has shown it (e.g. via a snackbar). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
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
     * driver. "In memory" means the wizard has left the pack step or has a
     * tour id.
     */
    private fun rearmEntryGateUnlessTourInMemory() {
        val state = _uiState.value
        val tourInMemory = state.step != RefillStep.PACKING || state.tourId.isNotEmpty()
        if (!tourInMemory) didRunInitialLoad = false
    }
}

/**
 * App-wide [TourStore], backed by SharedPreferences — same shape as
 * [xyz.vmflow.data.ServerStoreHolder], kept next to its only consumer.
 */
object RefillTourStoreHolder {
    private const val PREFS = "vmflow_refill_tour"

    val instance: TourStore by lazy {
        val prefs = VMflowApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TourStore(
            storage = object : KeyValueStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String?) {
                    prefs.edit().putString(key, value).apply()
                }
            }
        )
    }
}
