package xyz.vmflow.ui.refill

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.vmflow.models.RefillStep

/**
 * Wizard shell. **Interim state**: adapted to the new [RefillUiState] so the
 * tree compiles, deliberately not redesigned — the step indicator, the
 * resume prompt, keep-screen-on and the localization of the three step
 * titles are Task 11/12.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefillWizardScreen(
    onDone: () -> Unit,
    viewModel: RefillViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Entry gate: the ViewModel decides whether this actually loads (once
    // per ViewModel lifetime), so a tab re-selection that re-runs this
    // effect can't reset an in-progress tour.
    LaunchedEffect(Unit) { viewModel.loadDataIfNeeded() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val stepTitle = when (uiState.step) {
        RefillStep.PACKING -> "Pack Items"
        RefillStep.REFILL -> "Refill"
        RefillStep.SUMMARY -> "Summary"
    }

    // Interim progress figures for the refill step: the tour's real visit
    // order and per-machine progress arrive with startTour (Task 8) and the
    // rebuilt refill UI (Task 11).
    val packedMachines = uiState.machines.filter { it.isPacked }
    val completedCount = uiState.tourLog.size
    val machineProgress = "${(completedCount + 1).coerceAtMost(maxOf(packedMachines.size, 1))} / ${packedMachines.size}"
    val progressFraction =
        if (packedMachines.isEmpty()) 0f else completedCount.toFloat() / packedMachines.size.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stepTitle) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (uiState.step) {
                    RefillStep.PACKING -> PackingStep(
                        machines = uiState.machines,
                        packedMachineIds = packedMachines.map { it.machine.id }.toSet(),
                        // TODO(Task 7/10): togglePackedForMachine — no pack action exists yet.
                        onTogglePacked = { },
                        // TODO(Task 8/10): startTour.
                        onStartTour = { }
                    )
                    RefillStep.REFILL -> {
                        val currentMachine = uiState.machines
                            .firstOrNull { it.machine.id == uiState.currentMachineId }
                        if (currentMachine != null) {
                            RefillStepContent(
                                refillMachine = currentMachine,
                                machineProgress = machineProgress,
                                progressFraction = progressFraction,
                                isSaving = uiState.isSaving,
                                // TODO(Task 9/11): adjustFillAmount.
                                onUpdateFillAmount = { _, _ -> },
                                // TODO(Task 9/11): fillTrayToCapacity.
                                onFillTrayFull = { },
                                // TODO(Task 9/11): fillAllTrays.
                                onFillAllTrays = { },
                                // TODO(Task 9/11): confirmRefill.
                                onNextMachine = { },
                                // TODO(Task 9/11): skipMachine.
                                onSkipMachine = { }
                            )
                        }
                    }
                    RefillStep.SUMMARY -> RefillSummaryStep(
                        machinesVisited = uiState.tourLog.count { !it.skipped },
                        traysRefilled = uiState.tourLog.sumOf { it.traysRefilled },
                        totalItemsAdded = uiState.tourLog.sumOf { it.totalAdded },
                        // TODO(Task 9/12): reset() before leaving the wizard.
                        onDone = onDone
                    )
                }
            }
        }
    }
}
