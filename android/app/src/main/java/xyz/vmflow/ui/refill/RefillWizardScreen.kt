package xyz.vmflow.ui.refill

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefillWizardScreen(
    onDone: () -> Unit,
    viewModel: RefillViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val stepTitle = when (uiState.step) {
        RefillStep.PACKING -> "Pack Items"
        RefillStep.REFILL -> "Refill"
        RefillStep.SUMMARY -> "Summary"
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stepTitle) })
        }
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
                        refillMachines = uiState.refillMachines,
                        packedMachineIds = uiState.packedMachineIds,
                        onTogglePacked = { viewModel.toggleMachinePacked(it) },
                        onStartTour = { viewModel.startTour() }
                    )
                    RefillStep.REFILL -> {
                        val currentMachine = uiState.currentRefillMachine
                        if (currentMachine != null) {
                            RefillStepContent(
                                refillMachine = currentMachine,
                                machineProgress = uiState.machineProgress,
                                progressFraction = uiState.progressFraction,
                                isSaving = uiState.isSaving,
                                onUpdateFillAmount = { trayId, amount ->
                                    viewModel.updateFillAmount(trayId, amount)
                                },
                                onFillTrayFull = { viewModel.fillTrayFull(it) },
                                onFillAllTrays = { viewModel.fillAllTrays() },
                                onNextMachine = { viewModel.nextMachine() },
                                onSkipMachine = { viewModel.skipMachine() }
                            )
                        }
                    }
                    RefillStep.SUMMARY -> {
                        uiState.summary?.let { summary ->
                            RefillSummaryStep(
                                summary = summary,
                                onDone = onDone
                            )
                        }
                    }
                }
            }
        }
    }
}
