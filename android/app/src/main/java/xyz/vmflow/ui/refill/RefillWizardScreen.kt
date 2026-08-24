package xyz.vmflow.ui.refill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.vmflow.R
import xyz.vmflow.models.RefillStep

/**
 * Wizard shell: title bar, step indicator, the resume prompt, the
 * keep-screen-on window, and the error snackbar. The three steps themselves
 * live in [PackingStep], [RefillStepContent] and [RefillSummaryStep].
 *
 * Ported from iOS `RefillWizardView.swift` — its `stepIndicator` (L112), the
 * "Resume Tour?" alert and the load-once `.task`.
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

    // `RefillUiState.error` is a raw, non-localized message — this screen is
    // where it becomes user-facing text. Two shapes: a plain exception
    // message (shown as-is, same as every other error surfaced by this
    // ViewModel), or — when `refillFailedAttempts` is set — the cause of a
    // booking that exhausted its retries, which this composes into the
    // localized "could not be saved after N attempts" sentence. The
    // ViewModel deliberately does not formulate that sentence itself: it has
    // no access to localized string resources.
    val unknownRefillError = stringResource(R.string.refill_error_unknown)
    val displayedError = uiState.refillFailedAttempts?.let { attempts ->
        pluralStringResource(
            R.plurals.refill_error_max_attempts,
            attempts,
            attempts,
            uiState.error ?: unknownRefillError
        )
    } ?: uiState.error

    LaunchedEffect(displayedError) {
        displayedError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Warehouse deductions that did not go through at tour start. Announced,
    // never gated: the goods are in the van either way, so the driver gets a
    // snackbar and drives on — but somebody has to know the ledger is now
    // short, because only a human can correct it. Deliberately **not** routed
    // through `error`/`clearError`: it is not a failure of anything the driver
    // did, and it must survive on the summary screen rather than being
    // consumed by the first snackbar. Fires once, because
    // `failedDeductions` is written once per tour and only `reset()` clears it.
    val failedDeductionCount = uiState.failedDeductions.size
    val deductionWarning = if (failedDeductionCount > 0) {
        pluralStringResource(
            R.plurals.refill_deduction_failed,
            failedDeductionCount,
            failedDeductionCount
        )
    } else {
        null
    }
    LaunchedEffect(deductionWarning) {
        deductionWarning?.let { snackbarHostState.showSnackbar(it) }
    }

    // A tour is a physical errand: the phone must not sleep while the driver
    // is standing at a machine filling trays. Scoped to the refill step only
    // — see [KeepScreenOn].
    if (uiState.step == RefillStep.REFILL) {
        KeepScreenOn()
    }

    // Tour stops = the machines the tour packed for, in the visit order
    // `startTour`/`resumeTour` established. `machines` also holds machines
    // that were never packed, which are not stops.
    val tourStops = uiState.machines.filter { it.isPacked }
    val remainingStops = tourStops.filter { !it.isRefilled && !it.isSkipped }
    val finishedStops = tourStops.size - remainingStops.size
    val progressFraction =
        if (tourStops.isEmpty()) 0f else finishedStops.toFloat() / tourStops.size.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.refill_wizard_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StepIndicator(currentStep = uiState.step)

            if (uiState.isLoading) {
                LoadingState()
            } else {
                when (uiState.step) {
                    // The review screen and its replacement picker are a
                    // later task in this phase; the ViewModel's review state
                    // and actions landed first. Renders nothing until then —
                    // and this branch of the wizard is unusable until it does,
                    // so the review UI has to land before this work merges.
                    RefillStep.REVIEW -> Unit

                    RefillStep.PACKING -> PackingStep(
                        uiState = uiState,
                        visiblePackingList = viewModel.visiblePackingList(),
                        displayQuantity = viewModel::displayQuantity,
                        maxPackingQuantity = viewModel::maxPackingQuantity,
                        isPacked = viewModel::isPacked,
                        isOutOfStockForMachine = viewModel::isOutOfStockForMachine,
                        chipItemCount = viewModel::chipItemCount,
                        chipIsFullyPacked = viewModel::chipIsFullyPacked,
                        onSelectWarehouse = viewModel::selectWarehouse,
                        // Retry path for a failed stock fetch: `selectWarehouse`
                        // early-returns for the already-selected warehouse, so
                        // the picker cannot re-trigger the load. Narrow on
                        // purpose — `loadData()` would also replace `machines`
                        // and wipe every machine's derived `isPacked`, leaving
                        // Start Tour disabled under a screen full of ticks.
                        onReloadWarehouseStock = viewModel::reloadWarehouseStock,
                        onSelectChip = viewModel::selectChip,
                        onTogglePackedAll = viewModel::togglePackedAll,
                        onTogglePackedForMachine = viewModel::togglePackedForMachine,
                        onSetPackingQuantity = viewModel::setPackingQuantity,
                        onPackEverything = viewModel::packEverything,
                        onPackAllForMachine = viewModel::packAllForMachine,
                        onStartTour = viewModel::startTour
                    )

                    RefillStep.REFILL -> {
                        val currentMachine = uiState.machines
                            .firstOrNull { it.machine.id == uiState.currentMachineId }
                        if (currentMachine == null) {
                            // `advanceToNextMachine` switches to SUMMARY in the
                            // same update that clears `currentMachineId`, so this
                            // is a one-frame state at most — but a blank screen
                            // during a write would look like a crash.
                            FinishingUpState()
                        } else {
                            RefillStepContent(
                                machine = currentMachine,
                                remainingMachines = remainingStops,
                                // 1-based position of this stop in the tour.
                                // Coerced so it can never exceed the total:
                                // `advanceToNextMachine` normally leaves for
                                // the summary once the last stop is done, but
                                // a stop that is somehow still current after
                                // that would otherwise read "Machine 6 of 5".
                                machineNumber = (finishedStops + 1)
                                    .coerceAtMost(tourStops.size.coerceAtLeast(1)),
                                machineTotal = tourStops.size,
                                progressFraction = progressFraction,
                                isSaving = uiState.isSaving,
                                onSelectMachine = viewModel::selectMachine,
                                onAdjustFillAmount = { trayId, amount ->
                                    viewModel.adjustFillAmount(
                                        machineId = currentMachine.machine.id,
                                        trayId = trayId,
                                        amount = amount
                                    )
                                },
                                onFillTrayToCapacity = { trayId ->
                                    viewModel.fillTrayToCapacity(
                                        machineId = currentMachine.machine.id,
                                        trayId = trayId
                                    )
                                },
                                onFillAllTrays = {
                                    viewModel.fillAllTrays(currentMachine.machine.id)
                                },
                                onConfirmRefill = {
                                    viewModel.confirmRefill(currentMachine.machine.id)
                                },
                                onSkipMachine = {
                                    viewModel.skipMachine(currentMachine.machine.id)
                                }
                            )
                        }
                    }

                    RefillStep.SUMMARY -> RefillSummaryStep(
                        tourLog = uiState.tourLog,
                        // Same information as the tour-start snackbar, which
                        // the driver may well have missed while standing at a
                        // machine — the summary is the one screen someone
                        // reads to the end.
                        failedDeductionCount = failedDeductionCount,
                        // reset() then leave, in that order: it re-arms the
                        // entry gate and leaves `isLoading = true`, which is
                        // only correct if the screen is actually left and
                        // re-entered afterwards — staying here would show a
                        // permanent spinner.
                        onDone = {
                            viewModel.reset()
                            onDone()
                        }
                    )
                }
            }
        }
    }

    // ── Resume prompt ────────────────────────────────────────────────────
    // Gated on `!isLoading`, and that is a hard requirement, not polish.
    // `loadData` publishes `hasSavedTour` in the *same* update that sets
    // `isLoading = true` and then overwrites `machines` (and with it every
    // `isRefilled`/`isSkipped`/`fillAmount`) unconditionally when the fetch
    // lands. A prompt shown the moment `hasSavedTour` flips could therefore
    // put `resumeTour()` inside an in-flight load, which would then discard
    // exactly the progress the driver was resuming.
    //
    // `hasSavedTour = true` is only ever written together with
    // `isLoading = true`, and the state starts out `isLoading = true`, so
    // this condition cannot become true while a load is running — it can
    // only become true after the load's terminal update (success or
    // failure). No latch, no generation token, no timing assumption: it is
    // false by construction for the whole duration of the load.
    if (!uiState.isLoading && uiState.hasSavedTour) {
        ResumeTourDialog(
            onResume = viewModel::resumeTour,
            onDiscard = {
                viewModel.discardSavedTour()
                // Both failure branches of `loadData` (RefillViewModel.kt
                // ~212, ~232) can leave `isLoading = false` with
                // `hasSavedTour` still true and `machines` empty — the
                // initial load that would have populated the pack step never
                // finished. Discarding alone would leave "New tour" looking
                // like a dead tap; retry the load so the pack step actually
                // has something to show. A no-op when machines already
                // loaded (the success-path reasoning this used to rely on
                // exclusively still holds there).
                if (uiState.machines.isEmpty()) viewModel.loadData()
            }
        )
    }
}

/**
 * Holds the screen awake while composed. Attached only under
 * `step == REFILL`, so leaving the refill step (to the summary, to another
 * tab, or off the screen entirely) removes this composable and `onDispose`
 * clears the flag — the flag is never left set for the whole wizard.
 *
 * Keyed on the view: if the composition is ever re-attached to a different
 * `View`, the old one is cleared before the new one is set.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * "Resume tour?" — the driver was in the middle of a tour when the app was
 * killed. Not dismissible by tapping outside or by back: both answers are a
 * one-way door (resume restores the tour id the refill RPC dedupes on;
 * discard throws the snapshot away), and a stray dismissal would leave the
 * prompt to reappear on the next recomposition anyway, since it is derived
 * from `hasSavedTour`.
 */
@Composable
private fun ResumeTourDialog(
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.refill_resume_title)) },
        text = { Text(stringResource(R.string.refill_resume_message)) },
        confirmButton = {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.refill_resume_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.refill_resume_discard))
            }
        }
    )
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.refill_loading_machines),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FinishingUpState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.refill_finishing_up),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Step indicator
// ─────────────────────────────────────────────────────────────────────────

/**
 * Review → Pack → Refill → Summary, with the completed steps ticked and the
 * connector to a completed step filled in. Ported from iOS
 * `RefillWizardView.swift:112`.
 *
 * **Deliberately not tappable.** iOS's `canNavigateTo` allows exactly one
 * backward jump — refill → packing — and this ViewModel has no action to
 * express it (there is no step setter, and adding one is out of scope for this
 * task). Nor would it be safe if it did: `startTour` latches on
 * `isTourInMemory` (`step == REFILL || step == SUMMARY || tourId != ""`), so a
 * driver sent back to the pack step after the tour had started
 * would find "Start tour" silently doing nothing, with no way forward and a
 * warehouse already charged. A progress read-out that cannot mislead beats a
 * navigation control that strands the driver; a real backward jump needs a
 * ViewModel action that rewinds the tour, not just the step.
 */
@Composable
private fun StepIndicator(currentStep: RefillStep, modifier: Modifier = Modifier) {
    val steps = RefillStep.entries

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, step ->
                StepBubble(
                    step = step,
                    isActive = step == currentStep,
                    isComplete = step.ordinal < currentStep.ordinal
                )
                if (index < steps.lastIndex) {
                    // Filled once the step on its left is behind us.
                    val connectorColor = if (step.ordinal < currentStep.ordinal) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(connectorColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StepBubble(
    step: RefillStep,
    isActive: Boolean,
    isComplete: Boolean
) {
    val filled = isActive || isComplete
    val bubbleColor = if (filled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (filled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(percent = 50),
            color = bubbleColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isComplete) Icons.Default.Check else step.icon,
                    // Non-interactive: the step's title is rendered right
                    // below, so an icon label would only be read twice. The
                    // tick is the exception — "completed" is information the
                    // title doesn't carry.
                    contentDescription = if (isComplete) {
                        stringResource(R.string.refill_step_completed)
                    } else {
                        null
                    },
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(step.titleRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center
        )
    }
}

/** Step label. iOS `RefillStep.title`. */
private val RefillStep.titleRes: Int
    get() = when (this) {
        RefillStep.REVIEW -> R.string.refill_step_title_review
        RefillStep.PACKING -> R.string.refill_step_title_packing
        RefillStep.REFILL -> R.string.refill_step_title_refill
        RefillStep.SUMMARY -> R.string.refill_step_title_summary
    }

/** Step glyph. iOS `RefillStep.icon`. */
private val RefillStep.icon: ImageVector
    get() = when (this) {
        // iOS `exclamationmark.triangle` (`RefillWizardViewModel.swift:208`).
        RefillStep.REVIEW -> Icons.Default.Warning
        RefillStep.PACKING -> Icons.Default.Inventory2
        RefillStep.REFILL -> Icons.Default.LocalShipping
        RefillStep.SUMMARY -> Icons.AutoMirrored.Filled.ListAlt
    }
