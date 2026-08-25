package xyz.vmflow.ui.refill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.RefillTourLogic
import xyz.vmflow.models.CombinedPackingItem
import xyz.vmflow.models.MachineNeed
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.Warehouse
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed

/**
 * Pack step — the screen a driver uses to load the van from a warehouse
 * before setting out. Product-centric: one card per product, each listing
 * the machines that need it with a per-machine quantity stepper.
 *
 * Ported from iOS `PackingStepView.swift` (its `warehousePicker`, `ChipBar`,
 * `HeaderStrip`, `AllPackingList`, `MachinePackingList` and `bottomBar`),
 * adapted to Android idiom: an `ExposedDropdownMenuBox` for the warehouse
 * picker, Material `FilterChip`s in a **wrapping** [FlowRow] for the chip
 * bar (a plain `Row` pushed the fourth chip off a 360 dp screen in the
 * warehouse module — a Phase 4 review finding), and one card composable for
 * both chip modes instead of iOS's two near-identical list subviews.
 *
 * **Every quantity comes from the ViewModel.** [displayQuantity] fills the
 * stepper's value, [maxPackingQuantity] its upper bound; nothing here
 * recomputes a packing quantity from trays or stock. The only arithmetic in
 * this file sums those helper outputs for a product's total/shortfall badge,
 * exactly as iOS's view does.
 *
 * No ViewModel reference: like `WarehouseStockTab`, this takes the immutable
 * [RefillUiState] plus already-bound callbacks.
 *
 * @param visiblePackingList `RefillViewModel.visiblePackingList()` — rows
 *   with no warehouse stock left and nothing packed are already dropped
 *   there, so an unavailable product is **hidden**, not greyed out (unless
 *   something is packed for it, in which case it stays adjustable).
 * @param onReloadWarehouseStock re-fetches the warehouse's stock and pick
 *   order (and the warehouse list itself when there is no selection) — the
 *   retry path for a failed stock fetch, and deliberately *not* a reload of
 *   the machine list. See [StockCapWarningCard].
 */
@Composable
fun PackingStep(
    uiState: RefillUiState,
    visiblePackingList: List<CombinedPackingItem>,
    displayQuantity: (machineId: String, productId: String) -> Int,
    maxPackingQuantity: (machineId: String, productId: String) -> Int,
    isPacked: (machineId: String, productId: String) -> Boolean,
    isOutOfStockForMachine: (machineId: String, productId: String) -> Boolean,
    chipItemCount: (machineId: String?) -> Int,
    chipIsFullyPacked: (machineId: String?) -> Boolean,
    onSelectWarehouse: (String) -> Unit,
    onReloadWarehouseStock: () -> Unit,
    onSelectChip: (machineId: String?) -> Unit,
    onTogglePackedAll: (productId: String) -> Unit,
    onTogglePackedForMachine: (machineId: String, productId: String) -> Unit,
    onSetPackingQuantity: (machineId: String, productId: String, quantity: Int) -> Unit,
    onPackEverything: () -> Unit,
    onPackAllForMachine: (machineId: String) -> Unit,
    onStartTour: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // Chips: "All" plus one per machine that actually has a need. iOS builds
    // its chip order from every machine in `machines`, but its machine list
    // is pre-filtered to machines needing a refill; Android's
    // `RefillRepository.fetchRefillMachines` returns every machine that has
    // trays, so filtering by need here is what reproduces iOS's chip set.
    val machineIdsWithNeeds = uiState.packingList
        .flatMap { item -> item.machineNeeds.map { it.machineId } }
        .toSet()
    val chipMachines = uiState.machines.filter { it.machine.id in machineIdsWithNeeds }

    // A chip whose machine has since lost every need (or vanished) falls back
    // to "All" rather than rendering an empty list under a dead chip.
    val activeChip = uiState.activeChip?.takeIf { id -> chipMachines.any { it.machine.id == id } }
    val activeMachineName = chipMachines
        .firstOrNull { it.machine.id == activeChip }?.machine?.displayName

    // …and the correction is written back, or the dead id stays selected in
    // the ViewModel and the filter silently snaps back to it the moment that
    // machine regains a need. iOS writes the snap-back too
    // (`RefillWizardViewModel.swift:1084`).
    val chipSelectionIsStale = uiState.activeChip != null && activeChip == null
    LaunchedEffect(chipSelectionIsStale) {
        if (chipSelectionIsStale) onSelectChip(null)
    }

    // The "All" chip's tick, derived here rather than read from
    // `chipIsFullyPacked(null)`: that folds over the ViewModel's *unfiltered*
    // machine list, and a machine with no need at all never counts as packed
    // (its `hadAnyNeed` stays false). Android's list contains every machine
    // with any tray — unlike iOS's, which is pre-filtered to machines needing
    // a refill — so the tick could never light, and via `isPartial` the header
    // stayed orange on a fully packed van forever.
    val allChipFullyPacked = chipMachines.isNotEmpty() &&
        chipMachines.all { chipIsFullyPacked(it.machine.id) }
    val isActiveChipFullyPacked =
        if (activeChip == null) allChipFullyPacked else chipIsFullyPacked(activeChip)

    // Chip label state resolved here, next to the row states and for the same
    // reason (see the note below `PackingStep`): a leaf composable that called
    // the ViewModel read-helpers itself would freeze its count and tick the
    // moment anything made it skippable.
    val chipStates = buildList {
        add(
            ChipState(
                machineId = null,
                name = stringResource(R.string.refill_pack_chip_all),
                count = chipItemCount(null),
                isFullyPacked = allChipFullyPacked
            )
        )
        chipMachines.forEach { machine ->
            val id = machine.machine.id
            add(
                ChipState(
                    machineId = id,
                    name = machine.machine.displayName,
                    count = chipItemCount(id),
                    isFullyPacked = chipIsFullyPacked(id)
                )
            )
        }
    }

    val rows = packRows(
        uiState = uiState,
        visiblePackingList = visiblePackingList,
        activeChip = activeChip,
        displayQuantity = displayQuantity,
        maxPackingQuantity = maxPackingQuantity,
        isPacked = isPacked,
        isOutOfStockForMachine = isOutOfStockForMachine
    )

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.warehouses.size > 1) {
                item(key = "warehouse-picker") {
                    WarehousePickerField(
                        warehouses = uiState.warehouses,
                        selectedWarehouseId = uiState.selectedWarehouseId,
                        onSelect = onSelectWarehouse
                    )
                }
            }

            // Not gated on a selected warehouse: the stock fetch failing, the
            // *warehouses* fetch failing (which leaves no selection at all)
            // and a company with zero warehouses all read as "no stock
            // loaded" to the capping logic, so all of them need the same
            // warning and the same way out. No flash during the initial load
            // — the wizard shell replaces this whole step with a spinner
            // while `isLoading`.
            if (!uiState.stockLoaded) {
                item(key = "stock-warning") {
                    StockCapWarningCard(onReload = onReloadWarehouseStock)
                }
            }

            if (chipMachines.isNotEmpty()) {
                item(key = "chip-row") {
                    PackChipRow(
                        chips = chipStates,
                        activeChip = activeChip,
                        onSelectChip = { id ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectChip(id)
                        }
                    )
                }
            }

            item(key = "header-strip") {
                PackHeaderStrip(
                    activeChip = activeChip,
                    activeMachineName = activeMachineName,
                    itemCount = chipItemCount(activeChip),
                    isFullyPacked = isActiveChipFullyPacked,
                    anyPacked = rows.any { it.anyPacked }
                )
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    PackEmptyState(isMachineChip = activeChip != null)
                }
            } else {
                items(items = rows, key = { it.productId }) { row ->
                    ProductPackCard(
                        row = row,
                        showAllMachinesToggle = activeChip == null,
                        onTogglePackedAll = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePackedAll(row.productId)
                        },
                        onTogglePackedForMachine = { machineId ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePackedForMachine(machineId, row.productId)
                        },
                        onSetQuantity = { machineId, quantity ->
                            onSetPackingQuantity(machineId, row.productId, quantity)
                        }
                    )
                }
            }
        }

        PackBottomBar(
            packedMachineCount = uiState.machines.count { it.isPacked },
            totalMachineCount = chipMachines.size,
            activeMachineName = activeMachineName,
            // Nothing on the list means nothing to pack: an enabled "pack
            // everything" under the "all stocked" empty state is a button
            // that can only do nothing.
            showPackAll = rows.isNotEmpty(),
            isSaving = uiState.isSaving,
            onPackAll = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (activeChip == null) onPackEverything() else onPackAllForMachine(activeChip)
            },
            onStartTour = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStartTour()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Row state. Resolved once per recomposition in [PackingStep]'s body — which
// never skips, since it holds the (unstable) ui state — and handed to the
// leaf composables as plain values. Leaf composables must not call the
// ViewModel read-helpers themselves: those read a StateFlow's current value
// without Compose observing it, so a skipped leaf would keep rendering a
// stale quantity.
// ─────────────────────────────────────────────────────────────────────────

/** One machine's need line inside a product card. */
private data class NeedRowState(
    val machineId: String,
    val machineName: String,
    /** Units this machine's trays are short of — the ask, from `MachineNeed`. */
    val needQuantity: Int,
    val capacity: Int,
    /** What to pack, from `RefillViewModel.displayQuantity`. */
    val quantity: Int,
    /** Stepper's upper bound, from `RefillViewModel.maxPackingQuantity`. */
    val maxQuantity: Int,
    val isPacked: Boolean,
    val isOutOfStock: Boolean
)

/** One product card. */
private data class PackRowState(
    val productId: String,
    val productLabel: String,
    val imagePath: String?,
    /** Sum of [NeedRowState.quantity] across the card's rows. */
    val totalQuantity: Int,
    /** Sum of [NeedRowState.needQuantity] — what the machines actually ask for. */
    val neededQuantity: Int,
    /**
     * What the **whole fleet** asks for (`CombinedPackingItem.totalQuantity`),
     * unaffected by the active chip. [neededQuantity] is chip-filtered, so
     * only this number can decide whether the warehouse is short — iOS
     * compares against `item.totalQuantity` too (`PackingStepView.swift:432`).
     */
    val fleetNeededQuantity: Int,
    /** Units the card is short of what the machines ask for, 0 when met. */
    val shortfall: Int,
    /** Total units in the selected warehouse, `null` while stock isn't loaded. */
    val stockTotal: Int?,
    /** Units left after other machines' commitments, `null` while stock isn't loaded. */
    val remainingStock: Int?,
    /** Units already committed to packed machines, `null` while stock isn't loaded. */
    val committedStock: Int?,
    val anyPacked: Boolean,
    val allPacked: Boolean,
    val allNeedsMet: Boolean,
    val isOutOfStock: Boolean,
    val needs: List<NeedRowState>
) {
    val isUnderpacked: Boolean get() = !isOutOfStock && anyPacked && !allNeedsMet
}

@Composable
private fun packRows(
    uiState: RefillUiState,
    visiblePackingList: List<CombinedPackingItem>,
    activeChip: String?,
    displayQuantity: (machineId: String, productId: String) -> Int,
    maxPackingQuantity: (machineId: String, productId: String) -> Int,
    isPacked: (machineId: String, productId: String) -> Boolean,
    isOutOfStockForMachine: (machineId: String, productId: String) -> Boolean
): List<PackRowState> {
    val context = LocalContext.current
    val unknownProduct = stringResource(R.string.refill_pack_unknown_product)
    val stockLoaded = uiState.stockLoaded

    return visiblePackingList.mapNotNull { item ->
        val needs = if (activeChip == null) {
            item.machineNeeds
        } else {
            item.machineNeeds.filter { it.machineId == activeChip }
        }
        if (needs.isEmpty()) return@mapNotNull null

        val needStates = needs.map { need ->
            NeedRowState(
                machineId = need.machineId,
                machineName = need.machineName,
                needQuantity = need.quantity,
                capacity = need.capacity,
                quantity = displayQuantity(need.machineId, item.productId),
                maxQuantity = maxPackingQuantity(need.machineId, item.productId),
                isPacked = isPacked(need.machineId, item.productId),
                isOutOfStock = isOutOfStockForMachine(need.machineId, item.productId)
            )
        }

        // Scoped to one machine, a row the driver can neither pack nor adjust
        // is dropped rather than shown dead — iOS `visibleItemsForActiveChip`
        // does the same. In "All" mode the row stays: another machine may
        // still be able to take it.
        if (activeChip != null && needStates.all { it.isOutOfStock && !it.isPacked }) {
            return@mapNotNull null
        }

        val allPacked = needStates.all { it.isPacked }
        val remaining = if (stockLoaded) {
            RefillTourLogic.remainingWarehouseStock(
                machines = uiState.machines,
                productId = item.productId,
                packedItems = uiState.packedItems,
                customQuantities = uiState.customQuantities,
                warehouseStock = uiState.warehouseStock
            )
        } else {
            null
        }
        val committed = if (stockLoaded) {
            RefillTourLogic.committedQuantity(
                machines = uiState.machines,
                productId = item.productId,
                packedItems = uiState.packedItems,
                customQuantities = uiState.customQuantities
            )
        } else {
            null
        }

        val needed = needStates.sumOf { it.needQuantity }
        val packedUnits = needStates.filter { it.isPacked }.sumOf { it.quantity }

        PackRowState(
            productId = item.productId,
            productLabel = item.productName?.takeIf { it.isNotBlank() }
                ?: slotNumberFor(uiState.machines, needs, item.productId)
                    ?.let { context.getString(R.string.machine_card_unassigned_slot, it) }
                ?: unknownProduct,
            imagePath = item.imagePath,
            totalQuantity = needStates.sumOf { it.quantity },
            neededQuantity = needed,
            fleetNeededQuantity = item.totalQuantity,
            shortfall = (needed - packedUnits).coerceAtLeast(0),
            stockTotal = if (stockLoaded) (uiState.warehouseStock[item.productId] ?: 0) else null,
            remainingStock = remaining,
            committedStock = committed,
            anyPacked = needStates.any { it.isPacked },
            allPacked = allPacked,
            allNeedsMet = needStates.all { it.isPacked && it.quantity >= it.needQuantity },
            isOutOfStock = !allPacked && remaining != null && remaining <= 0,
            needs = needStates
        )
    }
}

/**
 * Slot number to name an unassigned product by. [CombinedPackingItem] is
 * grouped by product across machines and carries no slot, so the number is
 * read back out of the first tray holding this product — the fallback its
 * own doc comment prescribes.
 */
private fun slotNumberFor(
    machines: List<RefillMachine>,
    needs: List<MachineNeed>,
    productId: String
): Int? = needs.firstNotNullOfOrNull { need ->
    machines.firstOrNull { it.machine.id == need.machineId }
        ?.trays?.firstOrNull { it.tray.productId == productId }?.tray?.itemNumber
}

// ─────────────────────────────────────────────────────────────────────────
// Warehouse picker + stock warning
// ─────────────────────────────────────────────────────────────────────────

/** Same idiom as the warehouse module's picker; rendered only for more than one warehouse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarehousePickerField(
    warehouses: List<Warehouse>,
    selectedWarehouseId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = warehouses.firstOrNull { it.id == selectedWarehouseId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.name ?: selected?.id.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.warehouse_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            warehouses.forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text(warehouse.name ?: warehouse.id) },
                    onClick = {
                        onSelect(warehouse.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Warning + retry for "no warehouse stock loaded".
 *
 * A failed stock fetch — or a failed *warehouses* fetch, or a company with no
 * warehouse at all — leaves `warehouseStock` empty, and empty is
 * indistinguishable from "not loaded" for the capping logic: the caps lift
 * and the driver is told to pack more than the warehouse holds. The error
 * itself is transient (a snackbar) and `selectWarehouse` early-returns for
 * the already-selected id, so the picker can't retry — this banner is the
 * visible way back, and it stays up for as long as the state lasts.
 */
@Composable
private fun StockCapWarningCard(
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StockOrange.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                // Decorative: the title beside it says the same thing.
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = StockOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.refill_pack_stock_unavailable_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.refill_pack_stock_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onReload) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.refill_pack_stock_retry),
                    maxLines = 1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Chip bar + header strip
// ─────────────────────────────────────────────────────────────────────────

/** One chip's resolved label state. [machineId] `null` is the "All" chip. */
private data class ChipState(
    val machineId: String?,
    val name: String,
    /** Outstanding units for this chip, from `RefillViewModel.chipItemCount`. */
    val count: Int,
    val isFullyPacked: Boolean
)

/**
 * "All" plus one chip per machine with a need, each carrying its outstanding
 * count and a tick once fully packed.
 *
 * [FlowRow], not [Row]: four chips already overflowed a 360 dp screen in the
 * warehouse module, and German machine names are longer still.
 */
@Composable
private fun PackChipRow(
    chips: List<ChipState>,
    activeChip: String?,
    onSelectChip: (machineId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            PackChip(
                name = chip.name,
                count = chip.count,
                isSelected = activeChip == chip.machineId,
                isFullyPacked = chip.isFullyPacked,
                onClick = { onSelectChip(chip.machineId) }
            )
        }
    }
}

@Composable
private fun PackChip(
    name: String,
    count: Int,
    isSelected: Boolean,
    isFullyPacked: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            // Two Texts, not one formatted string: [FlowRow] constrains the
            // chip to the container width, so a single ellipsised Text eats
            // the count — the actionable number — on a long German machine
            // name at 360 dp. Only the name may shrink and ellipsise
            // (`fill = false` so a short name doesn't stretch the chip); the
            // count always survives. iOS keeps them as two Texts in an
            // HStack (`PackingStepView.swift:513-520`).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.refill_pack_chip_count, count),
                    maxLines = 1
                )
            }
        },
        leadingIcon = if (isFullyPacked) {
            {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.refill_pack_status_complete),
                    tint = StockGreen,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            null
        }
    )
}

/** Status line for the active chip: what it holds and whether its box is done. */
@Composable
private fun PackHeaderStrip(
    activeChip: String?,
    activeMachineName: String?,
    itemCount: Int,
    isFullyPacked: Boolean,
    anyPacked: Boolean,
    modifier: Modifier = Modifier
) {
    val isPartial = !isFullyPacked && anyPacked
    // Fixed status tokens, not scheme roles: the brand palette's
    // primary/secondary/tertiary collapse into each other in dark mode.
    val accent: Color = when {
        isFullyPacked -> StockGreen
        isPartial -> StockOrange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = stringResource(
        when {
            isFullyPacked -> R.string.refill_pack_status_complete
            isPartial -> R.string.refill_pack_status_partial
            else -> R.string.refill_pack_status_pending
        }
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    isFullyPacked -> Icons.Default.CheckCircle
                    isPartial -> Icons.Default.Warning
                    else -> Icons.Default.Inventory2
                },
                // Decorative: [statusLabel] is rendered as text below.
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeMachineName?.takeIf { activeChip != null }
                        ?: stringResource(R.string.refill_pack_header_all),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.refill_pack_items, itemCount, itemCount),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Product card
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ProductPackCard(
    row: PackRowState,
    showAllMachinesToggle: Boolean,
    onTogglePackedAll: () -> Unit,
    onTogglePackedForMachine: (machineId: String) -> Unit,
    onSetQuantity: (machineId: String, quantity: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor: Color? = when {
        row.isOutOfStock -> null
        row.allNeedsMet -> StockGreen
        row.isUnderpacked -> StockOrange
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = borderColor?.let { BorderStroke(1.5.dp, it) }
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showAllMachinesToggle) {
                    IconButton(
                        onClick = onTogglePackedAll,
                        enabled = !row.isOutOfStock
                    ) {
                        Icon(
                            imageVector = when {
                                row.isOutOfStock -> Icons.Default.DisabledByDefault
                                row.allPacked -> Icons.Default.CheckCircle
                                else -> Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = stringResource(
                                R.string.refill_pack_toggle_all_machines,
                                row.productLabel
                            ),
                            tint = when {
                                row.isOutOfStock -> StockRed
                                row.allPacked -> StockGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }

                ProductImage(
                    imagePath = row.imagePath,
                    contentDescription = null,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.productLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    WarehouseStockBadge(row = row)
                }
                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    QuantityPill(
                        text = stringResource(R.string.refill_pack_total_quantity, row.totalQuantity),
                        // Fixed status hues throughout, never scheme roles:
                        // this brand's primary/secondary/tertiary collapse
                        // into near-identical tones in dark mode, and
                        // out-of-stock is StockRed everywhere else in this
                        // file (the two toggle icons and the stock badge).
                        color = when {
                            row.isOutOfStock -> StockRed
                            row.isUnderpacked -> StockOrange
                            else -> StockGreen
                        }
                    )
                    if (row.isUnderpacked && row.shortfall > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.refill_pack_short,
                                row.shortfall,
                                row.shortfall
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = StockOrange
                        )
                    }
                }
            }

            row.needs.forEach { need ->
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                MachineNeedRow(
                    need = need,
                    onToggle = { onTogglePackedForMachine(need.machineId) },
                    onSetQuantity = { quantity -> onSetQuantity(need.machineId, quantity) }
                )
            }
        }
    }
}

@Composable
private fun WarehouseStockBadge(row: PackRowState) {
    val total = row.stockTotal ?: return
    val remaining = row.remainingStock ?: return
    val committed = row.committedStock ?: 0

    // No `remaining <= 0` branch of its own: `remaining` is `total - committed`
    // and the totals map only aggregates batches with `quantity > 0`, so
    // `remaining <= 0` with nothing committed implies `total <= 0` — the first
    // branch already has it.
    //
    // The shortfall threshold is the **fleet-wide** ask, not the active chip's
    // slice of it: otherwise a product the warehouse cannot cover across all
    // machines still reads green while a single machine's chip is selected.
    val (text, color) = when {
        total <= 0 -> stringResource(R.string.refill_pack_stock_none) to StockRed
        remaining <= 0 && committed > 0 ->
            stringResource(R.string.refill_pack_stock_committed, committed, total) to StockOrange
        remaining < row.fleetNeededQuantity ->
            stringResource(R.string.refill_pack_stock_left, total, remaining) to StockOrange
        else -> stringResource(R.string.refill_pack_stock_in, total) to StockGreen
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun MachineNeedRow(
    need: NeedRowState,
    onToggle: () -> Unit,
    onSetQuantity: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle, enabled = !need.isOutOfStock) {
            Icon(
                imageVector = when {
                    need.isOutOfStock -> Icons.Default.DisabledByDefault
                    need.isPacked -> Icons.Default.CheckBox
                    else -> Icons.Default.CheckBoxOutlineBlank
                },
                contentDescription = stringResource(
                    R.string.refill_pack_toggle_machine,
                    need.machineName
                ),
                tint = when {
                    need.isOutOfStock -> StockRed
                    need.isPacked -> StockGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = need.machineName,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.refill_pack_need,
                        need.needQuantity,
                        need.capacity
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val badge = when {
                    need.isOutOfStock -> stringResource(R.string.refill_pack_badge_no_stock) to StockRed
                    need.isPacked && need.quantity < need.needQuantity ->
                        stringResource(R.string.refill_pack_badge_partial) to StockOrange
                    else -> null
                }
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = badge.first,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = badge.second
                    )
                }
            }
        }

        QuantityStepper(
            quantity = need.quantity,
            maxQuantity = need.maxQuantity,
            enabled = !need.isOutOfStock,
            machineName = need.machineName,
            onSetQuantity = onSetQuantity
        )
    }
}

/**
 * −/value/+ stepper. The upper bound is [maxQuantity] — the ViewModel's
 * `maxPackingQuantity`, already capped by tray capacity and remaining
 * warehouse stock — so a row can never advertise more than the warehouse
 * holds. (The ViewModel clamps again on write; this only stops the driver
 * pressing a button that would do nothing.)
 */
@Composable
private fun QuantityStepper(
    quantity: Int,
    maxQuantity: Int,
    enabled: Boolean,
    machineName: String,
    onSetQuantity: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSetQuantity(quantity - 1)
            },
            enabled = enabled && quantity > 0
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = stringResource(R.string.refill_pack_decrement, machineName)
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 36.dp, minHeight = 32.dp)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = quantity.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSetQuantity(quantity + 1)
            },
            enabled = enabled && quantity < maxQuantity
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.refill_pack_increment, machineName)
            )
        }
    }
}

@Composable
private fun QuantityPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Empty state + bottom bar
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun PackEmptyState(isMachineChip: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StockGreen,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (isMachineChip) {
            Text(
                text = stringResource(R.string.refill_pack_empty_machine),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = stringResource(R.string.refill_pack_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.refill_pack_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Pack-all for the active scope, plus Start Tour. Stacked rather than
 * side-by-side: "Alles für <Automatenname> packen" does not fit beside a
 * second button at 360 dp.
 */
@Composable
private fun PackBottomBar(
    packedMachineCount: Int,
    totalMachineCount: Int,
    activeMachineName: String?,
    showPackAll: Boolean,
    isSaving: Boolean,
    onPackAll: () -> Unit,
    onStartTour: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(modifier = Modifier.padding(16.dp)) {
            if (showPackAll) {
                OutlinedButton(
                    onClick = onPackAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    enabled = !isSaving
                ) {
                    Icon(
                        imageVector = Icons.Default.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeMachineName
                            ?.let { stringResource(R.string.refill_pack_pack_all_for, it) }
                            ?: stringResource(R.string.refill_pack_pack_all)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluralStringResource(
                        R.plurals.refill_pack_machines_packed,
                        totalMachineCount,
                        packedMachineCount,
                        totalMachineCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onStartTour,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    enabled = packedMachineCount > 0 && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LocalShipping,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.refill_pack_start_tour),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
