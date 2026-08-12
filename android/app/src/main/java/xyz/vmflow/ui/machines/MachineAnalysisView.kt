package xyz.vmflow.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import xyz.vmflow.BuildConfig
import xyz.vmflow.R
import xyz.vmflow.data.AnalysisGridSlot
import xyz.vmflow.data.MachineAnalysis
import xyz.vmflow.data.SlotTier
import xyz.vmflow.data.Suggestion
import xyz.vmflow.data.SuggestionKind
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

/**
 * Product-centric performance analysis for a machine tab — Android
 * counterpart of `ios/VMflow/Views/Machines/MachineAnalysisView.swift`. A
 * springboard-style layout grid coloured by product tier, a "products to
 * review" list with combined KPIs + machine tenure, and one-click
 * replacement against fleet bestsellers or never-sold newcomers.
 *
 * Deliberately out of scope (not present in the iOS/web source either, and
 * not requested by the task briefs): AI recommendations
 * (`machine-insights`).
 *
 * Divergence from iOS: applying a swap is destructive-ish (it resets the
 * slot's stock to 0), so — unlike the iOS sheet, which applies immediately
 * on tap — Android always shows a confirmation [AlertDialog] naming the
 * slot and both products before writing anything.
 */
@Composable
fun MachineAnalysisTab(
    machineId: String,
    trays: List<Tray>,
    catalogue: List<Product>,
    onSwapApplied: () -> Unit,
    viewModel: MachineAnalysisViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var replacingSlot by remember { mutableStateOf<AnalysisGridSlot?>(null) }
    var pendingSwap by remember { mutableStateOf<PendingSwap?>(null) }

    // Keyed on machineId + the tray/catalogue content itself (not just the
    // machine id): re-runs once on first composition, and again whenever the
    // caller hands down freshly-refreshed trays (e.g. after a swap), but not
    // on every recomposition. Mirrors iOS's `.task { await reload() }`.
    LaunchedEffect(machineId, trays, catalogue) {
        viewModel.analyze(machineId, trays, catalogue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when {
            uiState.isLoading && uiState.products.isEmpty() -> LoadingState()
            uiState.rowCount == 0 -> EmptyState()
            else -> {
                DaysPicker(
                    selected = uiState.days,
                    onSelect = { days -> viewModel.analyze(machineId, trays, catalogue, windowDays = days) },
                )
                GridSection(
                    rowCount = uiState.rowCount,
                    slots = uiState.slots,
                    onSlotClick = { slot -> replacingSlot = slot },
                )
                LegendSection()
                WeakProductsSection(products = uiState.weakProducts)
            }
        }
    }

    replacingSlot?.let { slot ->
        ReplaceProductSheet(
            suggestions = uiState.fillSuggestions.filter { it.productId != slot.productId },
            catalogue = catalogue.filter { it.id != slot.productId },
            onDismiss = { replacingSlot = null },
            onSelect = { choice ->
                pendingSwap = PendingSwap(slot, choice)
                replacingSlot = null
            },
        )
    }

    pendingSwap?.let { pending ->
        SwapConfirmDialog(
            pending = pending,
            onConfirm = {
                viewModel.applySwap(pending.slot.trayId, pending.choice.productId) { success ->
                    if (success) onSwapApplied()
                }
                pendingSwap = null
            },
            onDismiss = { pendingSwap = null },
        )
    }

    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.analysis_error_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}

private data class ReplacementChoice(val productId: String, val name: String)
private data class PendingSwap(val slot: AnalysisGridSlot, val choice: ReplacementChoice)

// ─── Loading / empty states ─────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.analysis_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.BarChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.analysis_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Days picker ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysPicker(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        7 to stringResource(R.string.analysis_window_7d),
        30 to stringResource(R.string.analysis_window_30d),
        90 to stringResource(R.string.analysis_window_90d),
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "" },
    ) {
        options.forEachIndexed { index, (days, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(label) },
            )
        }
    }
}

// ─── Grid ────────────────────────────────────────────────────────────────

@Composable
private fun GridSection(rowCount: Int, slots: List<AnalysisGridSlot>, onSlotClick: (AnalysisGridSlot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.analysis_layout_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AnalysisLayoutGrid(rowCount = rowCount, slots = slots, onSlotClick = onSlotClick)
    }
}

private const val GRID_CELL_HEIGHT_DP = 56
private const val GRID_SPACING_DP = 4

/** One cell of the flattened row-major grid; `slot == null` is a gap/spacer column. */
private data class GridCellEntry(val id: String, val span: Int, val slot: AnalysisGridSlot?, val isGap: Boolean)

/**
 * Walks columns 0..9 for every row, same algorithm as iOS's
 * `AnalysisLayoutGrid.columnEntries` / `MachineLayoutGrid.columnContent`: an
 * occupied slot emits one entry spanning its width and advances the column
 * cursor by that width; an unoccupied column between occupied slots emits a
 * visible gap; columns past the last occupied slot in the row emit an
 * invisible spacer. Every row's spans sum to exactly [MachineAnalysis.COLUMNS_PER_ROW],
 * so a plain `GridCells.Fixed` grid renders each machine row as one grid row.
 */
private fun buildGridEntries(rowCount: Int, slots: List<AnalysisGridSlot>): List<GridCellEntry> {
    val entries = mutableListOf<GridCellEntry>()
    for (row in 0 until rowCount) {
        val rowSlots = slots.filter { it.row == row }.sortedBy { it.column }
        val lastOccupied = rowSlots.lastOrNull()?.let { it.column + it.width - 1 } ?: -1
        var column = 0
        var index = 0
        while (column < MachineAnalysis.COLUMNS_PER_ROW) {
            if (index < rowSlots.size && rowSlots[index].column == column) {
                val slot = rowSlots[index]
                entries += GridCellEntry(id = "slot-${slot.trayId}", span = slot.width, slot = slot, isGap = false)
                column += slot.width
                index++
            } else {
                entries += GridCellEntry(id = "gap-$row-$column", span = 1, slot = null, isGap = column <= lastOccupied)
                column++
            }
        }
    }
    return entries
}

@Composable
private fun AnalysisLayoutGrid(rowCount: Int, slots: List<AnalysisGridSlot>, onSlotClick: (AnalysisGridSlot) -> Unit) {
    val entries = remember(rowCount, slots) { buildGridEntries(rowCount, slots) }
    val gridHeight = (GRID_CELL_HEIGHT_DP * rowCount + GRID_SPACING_DP * (rowCount - 1).coerceAtLeast(0)).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(MachineAnalysis.COLUMNS_PER_ROW),
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        userScrollEnabled = false,
    ) {
        items(entries, key = { it.id }, span = { GridItemSpan(it.span) }) { entry ->
            val slot = entry.slot
            if (slot != null) {
                AnalysisGridCell(slot = slot, onClick = { onSlotClick(slot) })
            } else if (entry.isGap) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                )
            } else {
                Spacer(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun AnalysisGridCell(slot: AnalysisGridSlot, onClick: () -> Unit) {
    val tierColor = slot.tier.tierColor()
    val description = slot.productName ?: stringResource(R.string.analysis_slot_empty, slot.itemNumber)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(tierColor.copy(alpha = if (slot.tier == SlotTier.EMPTY) 0.15f else 0.35f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        val imagePath = slot.imagePath
        if (!imagePath.isNullOrEmpty()) {
            AsyncImage(
                model = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/product-images/$imagePath",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = slot.itemNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ─── Legend ──────────────────────────────────────────────────────────────

@Composable
private fun LegendSection() {
    val tiers = listOf(SlotTier.STRONG, SlotTier.OK, SlotTier.TESTING, SlotTier.WEAK, SlotTier.DEAD)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiers.forEach { tier ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tier.tierColor()),
                )
                Text(
                    text = tier.tierLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Products to review ─────────────────────────────────────────────────

@Composable
private fun WeakProductsSection(products: List<ProductAnalysis>) {
    if (products.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.analysis_products_to_review),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        products.forEach { product -> WeakProductRow(product) }
    }
}

@Composable
private fun WeakProductRow(product: ProductAnalysis) {
    val tierColor = product.tier.tierColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tierColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = product.tier.tierLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tierColor,
                )
            }
        }
        val tenureSuffix = product.tenureDays?.let { stringResource(R.string.analysis_tenure_suffix, it) }.orEmpty()
        Text(
            text = stringResource(R.string.analysis_sell_through_sold, product.sellThroughPct.toInt(), product.unitsSold) + tenureSuffix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (product.suggestions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.analysis_try_instead, product.suggestions.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Replace-product sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceProductSheet(
    suggestions: List<Suggestion>,
    catalogue: List<Product>,
    onDismiss: () -> Unit,
    onSelect: (ReplacementChoice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val unnamed = stringResource(R.string.product_unnamed)
    val filtered = remember(query, catalogue) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            catalogue.filter { (it.name ?: "").contains(trimmed, ignoreCase = true) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.analysis_replace_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (suggestions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.analysis_replace_suggestions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                suggestions.forEach { suggestion ->
                    ListItem(
                        modifier = Modifier.clickable {
                            onSelect(ReplacementChoice(suggestion.productId, suggestion.name))
                        },
                        headlineContent = { Text(suggestion.name) },
                        supportingContent = {
                            Text(
                                text = if (suggestion.kind == SuggestionKind.BESTSELLER) {
                                    stringResource(R.string.analysis_replace_bestseller)
                                } else {
                                    stringResource(R.string.analysis_replace_newcomer)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent = {
                            SuggestionThumbnail(imagePath = suggestion.imagePath)
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.analysis_replace_search),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.analysis_replace_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isNotBlank() && filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.analysis_replace_no_matches),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            filtered.forEach { product ->
                ListItem(
                    modifier = Modifier.clickable {
                        onSelect(ReplacementChoice(product.id, product.name ?: unnamed))
                    },
                    headlineContent = { Text(product.name ?: unnamed) },
                    leadingContent = { SuggestionThumbnail(imagePath = product.imagePath) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionThumbnail(imagePath: String?) {
    if (imagePath.isNullOrEmpty()) return
    AsyncImage(
        model = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/product-images/$imagePath",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp)),
    )
}

// ─── Swap confirmation ────────────────────────────────────────────────────

/**
 * A swap resets the slot's stock to 0 and writes an audit-log entry, so —
 * unlike iOS, which applies on tap — Android always confirms first, naming
 * the slot and both products (task 25 brief, context note 3).
 */
@Composable
private fun SwapConfirmDialog(pending: PendingSwap, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val oldName = pending.slot.productName
    val message = if (oldName != null) {
        stringResource(R.string.analysis_confirm_message, oldName, pending.slot.itemNumber, pending.choice.name)
    } else {
        stringResource(R.string.analysis_confirm_message_empty, pending.choice.name, pending.slot.itemNumber)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analysis_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ─── Tier styling — derived from the Material colour scheme, never hardcoded hex ───

@Composable
private fun SlotTier.tierColor(): Color = when (this) {
    SlotTier.STRONG -> MaterialTheme.colorScheme.primary
    SlotTier.OK -> MaterialTheme.colorScheme.tertiary
    SlotTier.TESTING -> MaterialTheme.colorScheme.secondary
    SlotTier.WEAK -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    SlotTier.DEAD -> MaterialTheme.colorScheme.error
    SlotTier.EMPTY -> MaterialTheme.colorScheme.outline
}

@Composable
private fun SlotTier.tierLabel(): String = when (this) {
    SlotTier.STRONG -> stringResource(R.string.analysis_legend_strong)
    SlotTier.OK -> stringResource(R.string.analysis_legend_ok)
    SlotTier.TESTING -> stringResource(R.string.analysis_legend_testing)
    SlotTier.WEAK -> stringResource(R.string.analysis_legend_weak)
    SlotTier.DEAD -> stringResource(R.string.analysis_legend_dead)
    SlotTier.EMPTY -> stringResource(R.string.analysis_legend_empty)
}
