package xyz.vmflow.ui.refill

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.PickerStockBucketKind
import xyz.vmflow.data.RefillTourLogic
import xyz.vmflow.data.ReplacementPickerLogic
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed

/**
 * The replacement product picker: what goes into a slot whose current
 * product has to stop selling. Opened from a [ReviewStep] card, closes by
 * picking a product or by dismissing the sheet.
 *
 * Ported from iOS `ReplacementProductPicker.swift`. Its structure — a search
 * field over the active catalogue, warehouse **stock buckets**, **category
 * groups** inside them with the current slot's category first, and a per-row
 * stock pill plus a badge for the slots this product already occupies in the
 * same machine — is carried over as-is; the grouping, sorting and search
 * themselves live in [ReplacementPickerLogic] where they can be unit-tested.
 *
 * Two adaptations, both Android idiom rather than a change of behaviour:
 *
 *  - **Groups collapse.** iOS's `List` sections are always open; here every
 *    bucket and every category header toggles, because a phone shows a third
 *    of what an iPad-sized `List` does and the driver is usually looking for
 *    one category. Everything starts expanded, so the collapsed state is
 *    never something the driver has to undo to see the catalogue.
 *  - **A [LazyColumn]**, not a scrolling `Column`: this list is the whole
 *    active product catalogue, not a search-narrowed handful.
 *
 * Deliberately **not** ported: iOS's machine-layout grid and its
 * spiral-capacity callout (`ReplacementProductPicker.swift` L342-383). Both
 * are separate features (a tappable layout grid, a capacity judgement aid),
 * neither is part of this task, and the layout grid already exists on the
 * analysis screen in a different shape.
 *
 * **No ViewModel reference**, like [ReviewStep], [PackingStep] and
 * [RefillStepContent]: immutable state in, one callback out.
 *
 * @param uiState read-only source for everything on screen:
 *   [RefillUiState.availableProducts] the candidates,
 *   [RefillUiState.productCategories] the group names,
 *   [RefillUiState.warehouseStock] (via
 *   [xyz.vmflow.data.RefillTourLogic.remainingWarehouseStock]) the stock
 *   pills and the bucket split, [RefillUiState.machines] the slot badges,
 *   [RefillUiState.replacements] the already-chosen product's tick.
 * @param trayId the slot being filled — the sheet's whole context. Rows,
 *   badges and the header are all derived from it.
 * @param currentCategoryId `RefillViewModel::categoryIdOfCurrentProduct` for
 *   [trayId]; that category is rendered first in each bucket. `null` is
 *   normal (an unassigned slot, an uncategorized or discontinued product).
 * @param onSelect the picked product — wired to
 *   `RefillViewModel::setReplacement` at the call site, which also closes
 *   the sheet.
 * @param onDismiss closed without picking anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplacementPickerSheet(
    uiState: RefillUiState,
    trayId: String,
    currentCategoryId: String?,
    onSelect: (productId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Keyed on the tray: opening the sheet for the next card starts with a
    // clean search box and every group open, rather than inheriting the
    // previous slot's state.
    var query by rememberSaveable(trayId) { mutableStateOf("") }
    var collapsed by rememberSaveable(trayId) { mutableStateOf(emptySet<String>()) }

    val model = pickerModel(
        uiState = uiState,
        trayId = trayId,
        currentCategoryId = currentCategoryId,
        query = query
    )
    val items = remember(model, collapsed) { flatten(model, collapsed) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.refill_picker_title),
                    style = MaterialTheme.typography.titleLarge
                )
                model.slotLabel?.let { label ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClear = { query = "" }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // `fill = false`: the list takes what it needs and no more, so a
            // catalogue of four products doesn't stretch the sheet to full
            // height — but it never grows past the sheet either, it scrolls.
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                if (items.isEmpty()) {
                    item(key = "empty") {
                        EmptyMessage(
                            query = query,
                            hasAnyProduct = uiState.availableProducts.isNotEmpty()
                        )
                    }
                }

                items(count = items.size, key = { items[it].key }) { index ->
                    when (val entry = items[index]) {
                        is PickerListItem.BucketHeader -> GroupHeaderRow(
                            title = entry.title,
                            countLabel = entry.countLabel,
                            countA11y = entry.countA11y,
                            isCollapsed = entry.isCollapsed,
                            isBucket = true,
                            isCurrent = false,
                            onToggle = { collapsed = collapsed.toggle(entry.collapseKey) }
                        )

                        is PickerListItem.GroupHeader -> GroupHeaderRow(
                            title = entry.title,
                            countLabel = entry.countLabel,
                            countA11y = entry.countA11y,
                            isCollapsed = entry.isCollapsed,
                            isBucket = false,
                            isCurrent = entry.isCurrent,
                            onToggle = { collapsed = collapsed.toggle(entry.collapseKey) }
                        )

                        is PickerListItem.ProductRow -> ProductRow(
                            row = entry,
                            onClick = { onSelect(entry.productId) }
                        )
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (contains(key)) this - key else this + key

// ─────────────────────────────────────────────────────────────────────────
// Row state. Resolved once in the sheet's body and handed down as plain
// values — the same split [ReviewStep]'s `reviewRows` and [PackingStep]'s
// `packRows` make, so no string lookup, plural or catalogue search happens
// inside a `LazyColumn` item body.
// ─────────────────────────────────────────────────────────────────────────

/** One rendered line of the picker. [key] is its `LazyColumn` identity. */
private sealed interface PickerListItem {
    val key: String

    data class BucketHeader(
        override val key: String,
        val collapseKey: String,
        val title: String,
        val countLabel: String,
        val countA11y: String,
        val isCollapsed: Boolean
    ) : PickerListItem

    data class GroupHeader(
        override val key: String,
        val collapseKey: String,
        val title: String,
        val countLabel: String,
        val countA11y: String,
        val isCurrent: Boolean,
        val isCollapsed: Boolean
    ) : PickerListItem

    data class ProductRow(
        override val key: String,
        val productId: String,
        val name: String,
        val imagePath: String?,
        /** `null` when there is no warehouse stock context at all. */
        val stockLabel: String?,
        val isOutOfStock: Boolean,
        /** `null` unless this product already sits in other slots here. */
        val slotLabel: String?,
        val slotA11y: String?,
        val isSelected: Boolean,
        val selectedLabel: String
    ) : PickerListItem
}

/** One bucket's headers and rows, all text already localized. */
private data class PickerModel(
    /** "Slot 15 · Foyer" — which slot this sheet is filling. */
    val slotLabel: String?,
    val showBucketHeaders: Boolean,
    val buckets: List<BucketModel>
)

private data class BucketModel(
    val collapseKey: String,
    val title: String,
    val countLabel: String,
    val countA11y: String,
    val groups: List<GroupModel>
)

private data class GroupModel(
    val collapseKey: String,
    val title: String,
    val countLabel: String,
    val countA11y: String,
    val isCurrent: Boolean,
    val rows: List<PickerListItem.ProductRow>
)

/**
 * Turns [RefillUiState] plus the search box into fully-resolved display
 * values. The grouping/sorting itself is [ReplacementPickerLogic]'s; this
 * only adds the localized text, which that layer cannot produce.
 *
 * `remember`ed on its real inputs: re-grouping and re-sorting the whole
 * catalogue on every unrelated recomposition of the wizard would be wasted
 * work on a screen the driver is typing into.
 */
@Composable
private fun pickerModel(
    uiState: RefillUiState,
    trayId: String,
    currentCategoryId: String?,
    query: String
): PickerModel {
    val context = LocalContext.current
    val unnamed = stringResource(R.string.product_unnamed)
    val uncategorized = stringResource(R.string.refill_picker_uncategorized)
    val inStockTitle = stringResource(R.string.refill_picker_bucket_in_stock)
    val outOfStockTitle = stringResource(R.string.refill_picker_bucket_out_of_stock)
    val selectedLabel = stringResource(R.string.refill_picker_selected)

    val suggestion = uiState.replacements.find { it.trayId == trayId }

    // Slot numbers of every *other* tray in the same machine, per product.
    // `machines` carries the machine's full tray set (each tray flagged
    // `isInTour`), so this sees slots the tour itself skipped — the same
    // unfiltered view iOS's `allTraysByMachine` gives its picker.
    val existingSlots = remember(uiState.machines, suggestion?.machineId, trayId) {
        val trays = uiState.machines
            .find { it.machine.id == suggestion?.machineId }
            ?.trays
            ?.map { it.tray }
            ?: emptyList()
        ReplacementPickerLogic.existingSlotsByProduct(trays, trayId)
    }

    // Warehouse stock left per product, not the raw totals: parity with
    // iOS, which feeds the picker `remainingWarehouseStock`. At review time
    // nothing is packed yet, so the two agree — but the review is the one
    // step that can be re-entered with a state the packing step wrote, and
    // a pill that ignores commitments would then overstate the stock.
    val stockByProduct = remember(
        uiState.availableProducts,
        uiState.machines,
        uiState.packedItems,
        uiState.customQuantities,
        uiState.warehouseStock
    ) {
        if (!uiState.stockLoaded) {
            emptyMap()
        } else {
            uiState.availableProducts.associate { product ->
                product.id to RefillTourLogic.remainingWarehouseStock(
                    machines = uiState.machines,
                    productId = product.id,
                    packedItems = uiState.packedItems,
                    customQuantities = uiState.customQuantities,
                    warehouseStock = uiState.warehouseStock
                )
            }
        }
    }

    val buckets = remember(
        uiState.availableProducts,
        uiState.productCategories,
        stockByProduct,
        uiState.stockLoaded,
        existingSlots,
        currentCategoryId,
        query
    ) {
        ReplacementPickerLogic.buildBuckets(
            products = uiState.availableProducts,
            categories = uiState.productCategories,
            stockByProduct = stockByProduct,
            stockKnown = uiState.stockLoaded,
            existingSlotsByProduct = existingSlots,
            currentCategoryId = currentCategoryId,
            query = query
        )
    }

    val selectedProductId = suggestion?.replacementProductId

    return PickerModel(
        slotLabel = suggestion?.let {
            context.getString(R.string.refill_picker_for_slot, it.slotNumber, it.machineName)
        },
        // With a single bucket the split carries no information — iOS
        // suppresses its mega-headers on exactly this condition.
        showBucketHeaders = buckets.size > 1,
        buckets = buckets.map { bucket ->
            val bucketKey = "bucket:${bucket.kind.name}"
            BucketModel(
                collapseKey = bucketKey,
                title = when (bucket.kind) {
                    PickerStockBucketKind.IN_STOCK -> inStockTitle
                    PickerStockBucketKind.OUT_OF_STOCK -> outOfStockTitle
                },
                countLabel = bucket.totalCount.toString(),
                countA11y = productCount(context, bucket.totalCount),
                groups = bucket.groups.map { group ->
                    GroupModel(
                        collapseKey = "$bucketKey/${group.key}",
                        title = group.categoryName?.takeIf { it.isNotBlank() }
                            ?.let { name ->
                                if (group.isCurrent) {
                                    context.getString(
                                        R.string.refill_picker_category_current,
                                        name
                                    )
                                } else {
                                    name
                                }
                            }
                            ?: uncategorized,
                        countLabel = group.rows.size.toString(),
                        countA11y = productCount(context, group.rows.size),
                        isCurrent = group.isCurrent,
                        rows = group.rows.map { row ->
                            val slotParts = ReplacementPickerLogic.slotBadgeParts(row.existingSlots)
                            val slotNumbers = row.existingSlots.sorted().joinToString(", ")
                            PickerListItem.ProductRow(
                                key = "$bucketKey/${group.key}/${row.productId}",
                                productId = row.productId,
                                name = row.name?.takeIf { it.isNotBlank() } ?: unnamed,
                                imagePath = row.imagePath,
                                stockLabel = row.warehouseStock?.let { stock ->
                                    context.resources.getQuantityString(
                                        R.plurals.refill_picker_stock,
                                        stock,
                                        stock
                                    )
                                },
                                isOutOfStock = row.warehouseStock == 0,
                                slotLabel = slotParts?.let { parts ->
                                    val shown = parts.shown.joinToString(", ")
                                    if (parts.overflow > 0) {
                                        context.getString(
                                            R.string.refill_picker_slot_badge_overflow,
                                            shown,
                                            parts.overflow
                                        )
                                    } else {
                                        context.getString(
                                            R.string.refill_picker_slot_badge,
                                            shown
                                        )
                                    }
                                },
                                // Spells out every slot, including the ones
                                // the visible "+2" hides.
                                slotA11y = slotParts?.let {
                                    context.resources.getQuantityString(
                                        R.plurals.refill_picker_slot_badge_a11y,
                                        row.existingSlots.size,
                                        slotNumbers
                                    )
                                },
                                isSelected = row.productId == selectedProductId,
                                selectedLabel = selectedLabel
                            )
                        }
                    )
                }
            )
        }
    )
}

private fun productCount(context: Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.refill_picker_product_count, count, count)

/** Applies the collapse state, producing the flat list the `LazyColumn` walks. */
private fun flatten(model: PickerModel, collapsed: Set<String>): List<PickerListItem> {
    val out = mutableListOf<PickerListItem>()
    for (bucket in model.buckets) {
        val bucketCollapsed = model.showBucketHeaders && bucket.collapseKey in collapsed
        if (model.showBucketHeaders) {
            out += PickerListItem.BucketHeader(
                key = bucket.collapseKey,
                collapseKey = bucket.collapseKey,
                title = bucket.title,
                countLabel = bucket.countLabel,
                countA11y = bucket.countA11y,
                isCollapsed = bucketCollapsed
            )
        }
        if (bucketCollapsed) continue

        for (group in bucket.groups) {
            val groupCollapsed = group.collapseKey in collapsed
            out += PickerListItem.GroupHeader(
                key = group.collapseKey,
                collapseKey = group.collapseKey,
                title = group.title,
                countLabel = group.countLabel,
                countA11y = group.countA11y,
                isCurrent = group.isCurrent,
                isCollapsed = groupCollapsed
            )
            if (!groupCollapsed) out += group.rows
        }
    }
    return out
}

// ─────────────────────────────────────────────────────────────────────────
// Widgets
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.refill_picker_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                // The placeholder says what the field is for.
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(
                            R.string.refill_picker_search_clear
                        )
                    )
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A collapsible header — the stock bucket's, or one category's inside it.
 * One composable for both because they differ only in emphasis: the bucket
 * is the louder of the two and sits at the outer indent.
 *
 * The whole row is the toggle rather than the chevron alone, so the target
 * is the full width at a 48 dp minimum height.
 */
@Composable
private fun GroupHeaderRow(
    title: String,
    countLabel: String,
    countA11y: String,
    isCollapsed: Boolean,
    isBucket: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit
) {
    val titleColor = when {
        // iOS's `Color.accentColor` for the current category — emphasis,
        // not a status colour, and the "· current" suffix carries the
        // meaning on its own.
        isCurrent -> MaterialTheme.colorScheme.primary
        isBucket -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isBucket) HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .defaultMinSize(minHeight = 48.dp)
                .padding(
                    start = if (isBucket) 0.dp else 8.dp,
                    end = 0.dp,
                    top = 4.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = if (isBucket) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // "12" alone is meaningless to a screen reader.
                modifier = Modifier.clearAndSetSemantics { contentDescription = countA11y }
            )
            Icon(
                imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = stringResource(
                    if (isCollapsed) {
                        R.string.refill_picker_expand_group
                    } else {
                        R.string.refill_picker_collapse_group
                    }
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * One candidate: image, name, warehouse stock, and the slots it already
 * occupies in this machine. The pills wrap under the name in a [FlowRow]
 * rather than sharing its line — a long product name next to two pills does
 * not fit 360 dp, and this module has already clipped content twice by
 * assuming it would.
 *
 * No fixed height: the name wraps to as many lines as it needs.
 */
@Composable
private fun ProductRow(
    row: PickerListItem.ProductRow,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProductImage(
            imagePath = row.imagePath,
            // The name is right next to it.
            contentDescription = null,
            size = 40.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (row.isOutOfStock) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (row.stockLabel != null || row.slotLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row.stockLabel?.let { label ->
                        Pill(
                            text = label,
                            // Red for nothing left, green for anything else:
                            // the pill is a go/no-go signal, not a gauge —
                            // and a third "low" tone would need a threshold
                            // nobody has defined.
                            color = if (row.isOutOfStock) StockRed else StockGreen
                        )
                    }
                    row.slotLabel?.let { label ->
                        Pill(
                            text = label,
                            color = StockOrange,
                            contentDescriptionOverride = row.slotA11y
                        )
                    }
                }
            }
        }
        if (row.isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = row.selectedLabel,
                tint = StockGreen,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The label in a fixed hue on a 15 % wash of it — same construction as
 * [ReviewStep]'s reason badge, and fixed tokens for the same reason: this
 * palette's `primary`/`secondary`/`tertiary` collapse into near-identical
 * tones in dark mode, so a status colour taken from the scheme stops being
 * a status colour.
 */
@Composable
private fun Pill(
    text: String,
    color: Color,
    contentDescriptionOverride: String? = null
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = color.copy(alpha = 0.15f),
        modifier = if (contentDescriptionOverride != null) {
            Modifier.clearAndSetSemantics { contentDescription = contentDescriptionOverride }
        } else {
            Modifier
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Nothing to show: either the search matched nothing, or the catalogue
 * itself is empty (a failed product fetch aborts the load, so this is the
 * "company has no active products" case).
 */
@Composable
private fun EmptyMessage(query: String, hasAnyProduct: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (query.isNotBlank() && hasAnyProduct) {
                stringResource(R.string.refill_picker_no_matches, query.trim())
            } else {
                stringResource(R.string.refill_picker_empty)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
