package xyz.vmflow.ui.refill

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillTray
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import xyz.vmflow.ui.theme.StockYellow

/**
 * Refill step — the screen a driver stands in front of a vending machine
 * with. One machine at a time: a header with the tour's progress and a way
 * to jump to another stop, a card per tray that still wants stock, the
 * already-full trays folded into a single expandable row, and a bottom bar
 * with skip/confirm.
 *
 * Ported from iOS `RefillStepView.swift` (`machineHeader`, `machinePicker`,
 * `refillTrayCard`, `fullTrayRow`, `bottomActionBar`), adapted to Android
 * idiom: a Material [ModalBottomSheet] for the machine picker, and the
 * disclosure group replaced by a hoisted expand/collapse row.
 *
 * **Touch targets are deliberately larger than the rest of the app.** The
 * driver is standing at a machine, often one-handed, sometimes in gloves —
 * the steppers are 64 dp circles (iOS uses 52 pt), "Max" and the bottom bar
 * buttons have a 56/60 dp minimum height. `defaultMinSize` throughout, never
 * a fixed height, so a long German label or a large font scale grows the row
 * instead of clipping it.
 *
 * No ViewModel reference, same contract as [PackingStep]: immutable state in,
 * bound callbacks out. Per-row state is resolved once in this body (see
 * [trayRows]) rather than inside the `LazyColumn` item bodies.
 *
 * **The whole screen is inert while [isSaving].** `RefillViewModel.confirmRefill`
 * and `skipMachine` both drop a call that arrives while a write is in flight,
 * and `isSaving` deliberately stays set across the audit write that follows a
 * successful booking — a tap silently swallowed in that window would read as
 * a broken app, so every control that could produce one is visibly disabled
 * and Confirm carries a spinner (same treatment as `BatchAdjustSheet`).
 *
 * @param machine the current stop, `RefillUiState.currentMachineId` resolved
 *   by the caller.
 * @param remainingMachines the tour's unfinished stops, in visit order —
 *   the machine picker's contents. `RefillViewModel.selectMachine` rejects
 *   anything else, so the sheet must not offer anything else.
 * @param machineNumber 1-based position of this stop in the tour, for
 *   "Machine 2 of 5".
 * @param progressFraction stops already finished / total stops, for the bar.
 */
@Composable
fun RefillStepContent(
    machine: RefillMachine,
    remainingMachines: List<RefillMachine>,
    machineNumber: Int,
    machineTotal: Int,
    progressFraction: Float,
    isSaving: Boolean,
    onSelectMachine: (machineId: String) -> Unit,
    onAdjustFillAmount: (trayId: String, amount: Int) -> Unit,
    onFillTrayToCapacity: (trayId: String) -> Unit,
    onFillAllTrays: () -> Unit,
    onConfirmRefill: () -> Unit,
    onSkipMachine: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // Both keyed on the machine id: the driver moving to the next stop must
    // not inherit the previous machine's open sheet or expanded full-tray
    // list. `machine` is a value type, so keying on the id (not the object)
    // keeps the state across a stock update of the same machine.
    var showPicker by remember(machine.machine.id) { mutableStateOf(false) }
    var fullTraysExpanded by remember(machine.machine.id) { mutableStateOf(false) }

    val rows = trayRows(machine)
    val fullTrays = fullTrayRows(machine)
    val traysToRefill = machine.trays.count { it.fillAmount > 0 }
    val canFillMore = rows.any { it.canIncrement }

    Column(modifier = modifier.fillMaxSize()) {
        MachineHeader(
            machineName = machine.machine.displayName,
            machineNumber = machineNumber,
            machineTotal = machineTotal,
            traysToRefill = traysToRefill,
            progressFraction = progressFraction,
            // One remaining stop means there is nowhere to switch to — iOS
            // disables the header button on the same condition.
            canSwitchMachine = remainingMachines.size > 1 && !isSaving,
            onOpenPicker = { showPicker = true }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rows.isNotEmpty()) {
                item(key = "fill-all") {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onFillAllTrays()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                        enabled = canFillMore && !isSaving
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerticalAlignTop,
                            // Decorative: the button's own label says it.
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.refill_step_fill_all),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }

                items(items = rows, key = { it.trayId }) { row ->
                    RefillTrayCard(
                        row = row,
                        enabled = !isSaving,
                        onDecrement = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAdjustFillAmount(row.trayId, row.fillAmount - 1)
                        },
                        onIncrement = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAdjustFillAmount(row.trayId, row.fillAmount + 1)
                        },
                        onFillToCapacity = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onFillTrayToCapacity(row.trayId)
                        }
                    )
                }
            } else {
                item(key = "empty") { NothingToFill() }
            }

            if (fullTrays.isNotEmpty()) {
                item(key = "full-trays") {
                    FullTraysSection(
                        trays = fullTrays,
                        expanded = fullTraysExpanded,
                        onToggle = { fullTraysExpanded = !fullTraysExpanded }
                    )
                }
            }
        }

        RefillBottomBar(
            isSaving = isSaving,
            onSkip = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSkipMachine()
            },
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirmRefill()
            }
        )
    }

    if (showPicker) {
        MachinePickerSheet(
            machines = remainingMachines,
            currentMachineId = machine.machine.id,
            onSelect = { id ->
                onSelectMachine(id)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Row state. Resolved once per recomposition of [RefillStepContent] — whose
// body never skips, it holds the machine — and handed down as plain values,
// so a leaf composable never has to resolve a string or re-derive a number.
// Same pattern and reasoning as `PackingStep.packRows`.
// ─────────────────────────────────────────────────────────────────────────

/** One tray card. */
private data class TrayRowState(
    val trayId: String,
    val itemNumber: Int,
    val label: String,
    val imagePath: String?,
    val currentStock: Int,
    val targetStock: Int,
    val capacity: Int,
    val fillAmount: Int,
    /** `capacity - currentStock`, clamped: what still physically fits. */
    val maxFill: Int
) {
    val canDecrement: Boolean get() = fillAmount > 0
    val canIncrement: Boolean get() = fillAmount < maxFill
    val isEmptySlot: Boolean get() = currentStock <= 0
}

/** One line of the collapsed "already full" section. */
private data class FullTrayRowState(
    val trayId: String,
    val itemNumber: Int,
    val label: String
)

/**
 * The tray cards: every tray this tour brought stock for
 * ([RefillTray.isInTour]) that still has room.
 *
 * A tray the driver has dialled back to `fillAmount == 0` **stays** a card —
 * that is the only way to bring it back up (iOS says the same in
 * `RefillStepView.swift:36-38`). A tray that is genuinely full
 * (`deficit == 0`) moves to [fullTrayRows] instead: the brief asks for trays
 * that need nothing to be collapsed into one row rather than rendered as
 * cards, and `applyTourInclusion` marks a packed product's already-full tray
 * `isInTour = true`, so without this split such a tray would render twice —
 * as a dead card with a disabled stepper *and* in the full-trays list (iOS
 * has exactly that duplicate).
 */
@Composable
private fun trayRows(machine: RefillMachine): List<TrayRowState> {
    val context = LocalContext.current
    return machine.trays
        .filter { it.isInTour && it.deficit > 0 }
        .map { refillTray ->
            TrayRowState(
                trayId = refillTray.tray.id,
                itemNumber = refillTray.tray.itemNumber,
                label = refillTray.tray.products?.name?.takeIf { it.isNotBlank() }
                    ?: context.getString(
                        R.string.machine_card_unassigned_slot,
                        refillTray.tray.itemNumber
                    ),
                imagePath = refillTray.tray.products?.imagePath,
                currentStock = refillTray.tray.currentStock,
                targetStock = refillTray.targetStock,
                capacity = refillTray.tray.capacity,
                fillAmount = refillTray.fillAmount,
                maxFill = refillTray.maxFill.coerceAtLeast(0)
            )
        }
}

/** Trays that want nothing: nothing dialled in and no deficit. iOS's `fullTrays`. */
@Composable
private fun fullTrayRows(machine: RefillMachine): List<FullTrayRowState> {
    val context = LocalContext.current
    return machine.trays
        .filter { it.fillAmount == 0 && it.deficit == 0 }
        .map { refillTray ->
            FullTrayRowState(
                trayId = refillTray.tray.id,
                itemNumber = refillTray.tray.itemNumber,
                label = refillTray.tray.products?.name?.takeIf { it.isNotBlank() }
                    ?: context.getString(
                        R.string.machine_card_unassigned_slot,
                        refillTray.tray.itemNumber
                    )
            )
        }
}

/**
 * Stock-level colour. Fixed tokens, never `MaterialTheme.colorScheme`: the
 * brand scheme's primary/secondary/tertiary collapse into near-identical
 * tones in dark mode, which is useless for a bar you read at a glance.
 */
private fun stockColor(fraction: Float): Color = when {
    fraction < 0.25f -> StockRed
    fraction < 0.5f -> StockOrange
    fraction < 0.75f -> StockYellow
    else -> StockGreen
}

// ─────────────────────────────────────────────────────────────────────────
// Machine header + picker
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun MachineHeader(
    machineName: String,
    machineNumber: Int,
    machineTotal: Int,
    traysToRefill: Int,
    progressFraction: Float,
    canSwitchMachine: Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.refill_step_machine_progress,
                        machineNumber,
                        machineTotal
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.refill_step_trays_to_refill,
                        traysToRefill,
                        traysToRefill
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val animatedProgress by animateFloatAsState(
                targetValue = progressFraction.coerceIn(0f, 1f),
                animationSpec = tween(300),
                label = "tour_progress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(8.dp))

            // The whole strip is the switch control, not a small chevron: it
            // is hit standing at a machine. A `Surface(onClick)` merges its
            // descendants' semantics, so the chevron's contentDescription is
            // what a screen reader announces for the row.
            Surface(
                onClick = onOpenPicker,
                enabled = canSwitchMachine,
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = machineName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (canSwitchMachine) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.refill_step_change_machine),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Machine picker. The driver does not necessarily drive the planned order,
 * so any unfinished stop can be made the current one.
 *
 * Only [machines] — the tour's unfinished stops — are offered:
 * `RefillViewModel.selectMachine` silently rejects a finished or never-packed
 * machine, so a row for one would be a dead tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachinePickerSheet(
    machines: List<RefillMachine>,
    currentMachineId: String,
    onSelect: (machineId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // One flat LazyColumn rather than a Column wrapping a list: the title
        // and the cancel button ride along as items, so nothing can be pushed
        // out of reach by a long tour and there is no nested-scroll container
        // inside the sheet.
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.refill_step_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            items(items = machines, key = { it.machine.id }) { candidate ->
                val isCurrent = candidate.machine.id == currentMachineId
                val trays = candidate.trays.count { it.fillAmount > 0 }
                val units = candidate.trays.sumOf { it.fillAmount }

                Surface(
                    onClick = { onSelect(candidate.machine.id) },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 64.dp)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.machine.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = stringResource(
                                    R.string.refill_step_picker_summary,
                                    trays,
                                    units
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isCurrent) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(
                                    R.string.refill_step_picker_current
                                ),
                                tint = StockGreen
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            item(key = "cancel") {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Tray card
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun RefillTrayCard(
    row: TrayRowState,
    enabled: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onFillToCapacity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentFraction = if (row.capacity > 0) row.currentStock.toFloat() / row.capacity else 0f
    val targetFraction = if (row.capacity > 0) row.targetStock.toFloat() / row.capacity else 0f
    val currentColor = stockColor(currentFraction)
    val targetColor = stockColor(targetFraction)

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlotBadge(
                    itemNumber = row.itemNumber,
                    color = if (row.isEmptySlot) StockRed else StockOrange
                )
                Spacer(modifier = Modifier.width(12.dp))
                ProductImage(
                    imagePath = row.imagePath,
                    contentDescription = null,
                    size = 48.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.currentStock.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = currentColor
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            // Decorative: the two numbers beside it are the content.
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(14.dp)
                        )
                        Text(
                            text = row.targetStock.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = targetColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.refill_step_of_capacity, row.capacity),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            StockChangeBars(
                currentFraction = currentFraction,
                targetFraction = targetFraction,
                currentColor = currentColor,
                targetColor = targetColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Large controls: standing at a machine, one-handed, in gloves.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onDecrement,
                    enabled = enabled && row.canDecrement,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = stringResource(
                            R.string.refill_step_decrement,
                            row.label
                        ),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.refill_step_fill_amount, row.fillAmount),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = if (row.fillAmount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.refill_step_fill_unit,
                            row.fillAmount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledIconButton(
                    onClick = onIncrement,
                    enabled = enabled && row.canIncrement,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(
                            R.string.refill_step_increment,
                            row.label
                        ),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onFillToCapacity,
                    enabled = enabled && row.canIncrement,
                    modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.refill_step_fill_max),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Slot number, the machine's own label for the tray. */
@Composable
private fun SlotBadge(itemNumber: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(32.dp),
        shape = RoundedCornerShape(percent = 50),
        color = color
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = itemNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }
    }
}

/** Before/after stock bars, iOS's two `StockBar`s with an arrow between them. */
@Composable
private fun StockChangeBars(
    currentFraction: Float,
    targetFraction: Float,
    currentColor: Color,
    targetColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedCurrent by animateFloatAsState(
        targetValue = currentFraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "current_bar"
    )
    val animatedTarget by animateFloatAsState(
        targetValue = targetFraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "target_bar"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            progress = { animatedCurrent },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = currentColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            // Decorative: the numbers above say current → target.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(14.dp)
        )
        LinearProgressIndicator(
            progress = { animatedTarget },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = targetColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Full trays + empty state + bottom bar
// ─────────────────────────────────────────────────────────────────────────

/**
 * Trays that want nothing, folded into one expandable row instead of a
 * screenful of dead cards. Collapsed by default; the expanded state is
 * hoisted into [RefillStepContent] so it survives the `LazyColumn` recycling
 * this item.
 */
@Composable
private fun FullTraysSection(
    trays: List<FullTrayRowState>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StockGreen.copy(alpha = 0.08f))
    ) {
        Column {
            Surface(
                onClick = onToggle,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        // Decorative: the row's text says the same.
                        contentDescription = null,
                        tint = StockGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.refill_step_full_trays,
                            trays.size,
                            trays.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.refill_step_full_trays_collapse
                            } else {
                                R.string.refill_step_full_trays_expand
                            }
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded) {
                trays.forEach { tray ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlotBadge(itemNumber = tray.itemNumber, color = StockGreen)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = tray.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Nothing dialled in for this machine. Not an error: the driver can still
 * confirm the visit (the ViewModel records a 0/0 stop) or skip it.
 */
@Composable
private fun NothingToFill(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StockGreen,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.refill_step_no_trays),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Skip + confirm.
 *
 * Both are disabled for the whole [isSaving] window — which the ViewModel
 * holds across the audit write that follows a successful booking — and
 * Confirm shows a spinner while it lasts, so a tap the re-entrancy guard
 * would have dropped can't be made in the first place.
 */
@Composable
private fun RefillBottomBar(
    isSaving: Boolean,
    onSkip: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !isSaving,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 60.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.refill_step_skip),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            }
            Button(
                onClick = onConfirm,
                enabled = !isSaving,
                modifier = Modifier
                    .weight(2f)
                    .defaultMinSize(minHeight = 60.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.refill_step_saving),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.refill_step_confirm),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
