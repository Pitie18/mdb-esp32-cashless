package xyz.vmflow.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.data.AuthRepository
import xyz.vmflow.data.MachineRepository
import xyz.vmflow.data.TrayRepository
import xyz.vmflow.models.MachineWithStats
import xyz.vmflow.models.Product
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SuppressedSale
import xyz.vmflow.models.Tray

data class MachineDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val machineStats: MachineWithStats? = null,
    val sales: List<Sale> = emptyList(),
    val suppressedSales: List<SuppressedSale> = emptyList(),
    val products: List<Product> = emptyList(),
    /** Whether the caller is an org admin — gates the "restore suppressed sale" affordance. */
    val isAdmin: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0
)

class MachineDetailViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MachineDetailUiState())
    val uiState: StateFlow<MachineDetailUiState> = _uiState.asStateFlow()

    private var machineId: String = ""

    fun loadMachine(id: String) {
        machineId = id
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val detailResult = MachineRepository.fetchMachineDetail(id)
            var embeddedId: String? = null
            detailResult.fold(
                onSuccess = { stats ->
                    embeddedId = stats.machine.embeddeds?.id
                    _uiState.value = _uiState.value.copy(machineStats = stats)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )

            val salesResult = MachineRepository.fetchMachineSales(id)
            salesResult.onSuccess { sales ->
                _uiState.value = _uiState.value.copy(sales = sales)
            }

            // Best-effort, same as iOS: no linked device means no suppressed
            // sales are possible, and a failed fetch must not block the tab.
            val suppressedResult = embeddedId?.let { MachineRepository.fetchSuppressedSales(it) }
                ?: Result.success(emptyList())
            suppressedResult.onSuccess { suppressed ->
                _uiState.value = _uiState.value.copy(suppressedSales = suppressed)
            }

            val productsResult = TrayRepository.fetchProducts()
            productsResult.onSuccess { products ->
                _uiState.value = _uiState.value.copy(products = products)
            }

            // Best-effort: a failed role fetch just leaves isAdmin false, which
            // safely keeps the restore-suppressed-sale action hidden.
            AuthRepository.fetchOrganization().onSuccess { response ->
                _uiState.value = _uiState.value.copy(isAdmin = response.role == "admin")
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Promotes an auto-removed sale back into a real sale, then reloads the
     * whole detail so trays (stock -1), sales, and suppressedSales all
     * reflect the change. Mirrors iOS `restoreSuppressed(_:)`.
     */
    fun restoreSuppressedSale(suppressedId: String) {
        viewModelScope.launch {
            MachineRepository.restoreSuppressedSale(suppressedId).fold(
                onSuccess = { loadMachine(machineId) },
                onFailure = { e -> _uiState.value = _uiState.value.copy(error = e.message) }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            loadMachine(machineId)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun updateTrayStock(trayId: String, delta: Int) {
        viewModelScope.launch {
            val trays = _uiState.value.machineStats?.trays ?: return@launch
            val tray = trays.find { it.id == trayId } ?: return@launch
            val newStock = (tray.currentStock + delta).coerceIn(0, tray.capacity)
            TrayRepository.updateStock(trayId, newStock).onSuccess {
                // Update local state
                val updatedTrays = trays.map {
                    if (it.id == trayId) {
                        Tray(
                            id = it.id,
                            machineId = it.machineId,
                            itemNumber = it.itemNumber,
                            productId = it.productId,
                            capacity = it.capacity,
                            currentStock = newStock,
                            minStock = it.minStock,
                            fillWhenBelow = it.fillWhenBelow,
                            products = it.products
                        )
                    } else it
                }
                _uiState.value = _uiState.value.copy(
                    machineStats = _uiState.value.machineStats?.copy(trays = updatedTrays)
                )
            }
        }
    }

    /** Sets a tray's stock straight to its capacity in one write. Mirrors iOS `fillTray(_:)`. */
    fun fillTray(trayId: String) {
        viewModelScope.launch {
            val trays = _uiState.value.machineStats?.trays ?: return@launch
            val tray = trays.find { it.id == trayId } ?: return@launch
            if (tray.currentStock == tray.capacity) return@launch
            TrayRepository.updateStock(trayId, tray.capacity).onSuccess {
                val updatedTrays = trays.map {
                    if (it.id == trayId) it.copy(currentStock = tray.capacity) else it
                }
                _uiState.value = _uiState.value.copy(
                    machineStats = _uiState.value.machineStats?.copy(trays = updatedTrays)
                )
            }
        }
    }

    fun deleteTray(trayId: String) {
        viewModelScope.launch {
            TrayRepository.deleteTray(trayId).onSuccess {
                loadMachine(machineId)
            }
        }
    }

    /**
     * Sends free credit to the machine's linked device via the `send-credit`
     * edge function. Plain suspend function (not viewModelScope-launched) so
     * the sheet's own coroutine scope can await it and drive its local
     * `isSending` state, mirroring iOS `sendCredit(amount:) async -> Bool`.
     *
     * No device linked → returns false without calling the function, same as
     * iOS's guard clause. Unlike iOS (which can inline a localized string at
     * the call site), this plain `ViewModel` has no `Context` to resolve a
     * string resource, so the "no linked device" message is rendered by the
     * caller (`MachineDetailScreen`'s `SendCreditSheet`, which already knows
     * the link status and disables the action) rather than pushed through
     * [MachineDetailUiState.error].
     */
    suspend fun sendCredit(amount: Double): Boolean {
        val deviceId = _uiState.value.machineStats?.machine?.embeddeds?.id ?: return false
        return MachineRepository.sendCredit(deviceId, amount).fold(
            onSuccess = { true },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
                false
            }
        )
    }

    /** Clears a surfaced error once the caller has shown/dismissed it. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
