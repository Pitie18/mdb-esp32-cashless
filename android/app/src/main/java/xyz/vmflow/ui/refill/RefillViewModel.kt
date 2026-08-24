package xyz.vmflow.ui.refill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.data.MachineRepository
import xyz.vmflow.data.RefillRepository
import xyz.vmflow.models.MachineWithStats
import xyz.vmflow.models.LegacyRefillItem
import xyz.vmflow.models.LegacyRefillMachine
import xyz.vmflow.models.LegacyRefillSummary

enum class RefillStep {
    PACKING, REFILL, SUMMARY
}

data class RefillUiState(
    val isLoading: Boolean = true,
    val step: RefillStep = RefillStep.PACKING,
    val machines: List<MachineWithStats> = emptyList(),
    val refillMachines: List<LegacyRefillMachine> = emptyList(),
    val currentMachineIndex: Int = 0,
    val packedMachineIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val summary: LegacyRefillSummary? = null,
    val error: String? = null
) {
    val currentRefillMachine: LegacyRefillMachine?
        get() = refillMachines.getOrNull(currentMachineIndex)

    val machineProgress: String
        get() = "${currentMachineIndex + 1} / ${refillMachines.size}"

    val progressFraction: Float
        get() = if (refillMachines.isEmpty()) 0f
        else (currentMachineIndex.toFloat()) / refillMachines.size.toFloat()
}

class RefillViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RefillUiState())
    val uiState: StateFlow<RefillUiState> = _uiState.asStateFlow()

    init {
        loadMachines()
    }

    private fun loadMachines() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = MachineRepository.fetchMachinesWithStats()
            result.fold(
                onSuccess = { machines ->
                    val refillMachines = RefillRepository.buildRefillPlan(machines)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        machines = machines,
                        refillMachines = refillMachines
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            )
        }
    }

    fun toggleMachinePacked(machineId: String) {
        val current = _uiState.value.packedMachineIds
        _uiState.value = _uiState.value.copy(
            packedMachineIds = if (machineId in current) current - machineId else current + machineId
        )
    }

    fun startTour() {
        // Filter refill machines to only packed ones
        val packedMachines = _uiState.value.refillMachines.filter {
            it.machine.id in _uiState.value.packedMachineIds
        }
        if (packedMachines.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            step = RefillStep.REFILL,
            refillMachines = packedMachines,
            currentMachineIndex = 0
        )
    }

    fun updateFillAmount(trayId: String, amount: Int) {
        val machines = _uiState.value.refillMachines.toMutableList()
        val currentIdx = _uiState.value.currentMachineIndex
        val machine = machines.getOrNull(currentIdx) ?: return

        val updatedItems = machine.items.map { item ->
            if (item.tray.id == trayId) {
                item.copy(fillAmount = amount.coerceIn(0, item.maxFillAmount))
            } else item
        }
        machines[currentIdx] = machine.copy(items = updatedItems)
        _uiState.value = _uiState.value.copy(refillMachines = machines)
    }

    fun fillAllTrays() {
        val machines = _uiState.value.refillMachines.toMutableList()
        val currentIdx = _uiState.value.currentMachineIndex
        val machine = machines.getOrNull(currentIdx) ?: return

        val updatedItems = machine.items.map { item ->
            item.copy(fillAmount = item.maxFillAmount)
        }
        machines[currentIdx] = machine.copy(items = updatedItems)
        _uiState.value = _uiState.value.copy(refillMachines = machines)
    }

    fun fillTrayFull(trayId: String) {
        val machines = _uiState.value.refillMachines.toMutableList()
        val currentIdx = _uiState.value.currentMachineIndex
        val machine = machines.getOrNull(currentIdx) ?: return

        val updatedItems = machine.items.map { item ->
            if (item.tray.id == trayId) {
                item.copy(fillAmount = item.maxFillAmount)
            } else item
        }
        machines[currentIdx] = machine.copy(items = updatedItems)
        _uiState.value = _uiState.value.copy(refillMachines = machines)
    }

    fun nextMachine() {
        viewModelScope.launch {
            val current = _uiState.value.currentRefillMachine ?: return@launch
            _uiState.value = _uiState.value.copy(isSaving = true)

            // Apply refill for current machine
            RefillRepository.applyRefill(current.items)

            val nextIdx = _uiState.value.currentMachineIndex + 1
            if (nextIdx >= _uiState.value.refillMachines.size) {
                // All done
                finishTour()
            } else {
                _uiState.value = _uiState.value.copy(
                    currentMachineIndex = nextIdx,
                    isSaving = false
                )
            }
        }
    }

    fun skipMachine() {
        val nextIdx = _uiState.value.currentMachineIndex + 1
        if (nextIdx >= _uiState.value.refillMachines.size) {
            finishTour()
        } else {
            _uiState.value = _uiState.value.copy(currentMachineIndex = nextIdx)
        }
    }

    private fun finishTour() {
        val machines = _uiState.value.refillMachines
        val totalTrays = machines.sumOf { m -> m.items.count { it.fillAmount > 0 } }
        val totalItems = machines.sumOf { m -> m.items.sumOf { it.fillAmount } }

        _uiState.value = _uiState.value.copy(
            step = RefillStep.SUMMARY,
            isSaving = false,
            summary = LegacyRefillSummary(
                machinesVisited = machines.size,
                traysRefilled = totalTrays,
                totalItemsAdded = totalItems
            )
        )
    }
}
