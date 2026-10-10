package xyz.vmflow.ui.refill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import xyz.vmflow.R
import xyz.vmflow.data.LeftoverDestination
import xyz.vmflow.data.RebuildAction
import xyz.vmflow.data.RebuildSlot
import xyz.vmflow.data.SlotChangeItem
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.deals.formatEuro
import xyz.vmflow.ui.theme.RebuildViolet
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed

/**
 * Slot re-assignment ("Fächer umbelegen") in the refill tour — the two
 * pieces of UI the change request adds:
 *
 *  - [ChangeNoteCard] on the pack step: the machine's change note. The
 *    refiller accepts (default) or declines each slot; accepted slots add
 *    their rebuild units to the van — as violet lines in the normal packing
 *    list, not in the note. Web `RefillChangeNote.vue`.
 *  - [RebuildSection] on the refill step: rebuild each accepted slot at the
 *    machine — count what comes out, fill the new product, set the price,
 *    mark it rebuilt or not. Nothing is booked until the machine is
 *    confirmed; what is left over rides in the van. Web `RefillRebuildSection.vue`.
 *  - [TourLeftoverCard] on the summary: the leftovers of every rebuild of
 *    the tour, counted and booked back at the warehouse in one go.
 *
 * Same contract as the steps: plain state in, callbacks out, no ViewModel.
 * Accent colour is [StockOrange] (the web uses amber) — a fixed token, never
 * a scheme role, for the same dark-mode reason as the rest of the wizard.
 */

/** One machine's change note on the pack step. */
data class ChangeNoteState(
    val machineId: String,
    val machineName: String,
    val items: List<SlotChangeItem>,
    /** The accepted slots need goods from the warehouse — they are in the packing list, marked violet. */
    val hasRebuildGoods: Boolean
)

@Composable
fun ChangeNoteCard(
    note: ChangeNoteState,
    enabled: Boolean,
    onToggleItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val acceptedCount = note.items.count { it.accepted }
    Card(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, StockOrange.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(
            containerColor = StockOrange.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = StockOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.refill_rebuild_change_note),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = StockOrange
                    )
                    Text(
                        text = note.machineName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.refill_rebuild_accepted_of, acceptedCount, note.items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.refill_rebuild_change_note_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            note.items.forEach { item ->
                ChangeNoteItemRow(item = item, enabled = enabled, onToggle = { onToggleItem(item.id) })
            }

            if (note.hasRebuildGoods) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.refill_rebuild_goods_in_pack_list),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RebuildViolet
                )
            }
        }
    }
}

@Composable
private fun ChangeNoteItemRow(item: SlotChangeItem, enabled: Boolean, onToggle: () -> Unit) {
    val emptyLabel = stringResource(R.string.refill_rebuild_empty_slot)
    val contentAlpha = if (item.accepted) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.accepted) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = stringResource(R.string.refill_rebuild_toggle_item, item.itemNumber),
            tint = if (item.accepted) StockGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "#${item.itemNumber}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            modifier = Modifier.width(36.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.fromName ?: emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(14.dp)
                )
                Text(
                    text = item.toName ?: emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            val details = buildList {
                if (item.capacityChanges) {
                    add(stringResource(R.string.refill_rebuild_change_spiral, item.fromCapacity, item.toCapacity))
                }
                val toPrice = item.toPrice
                if (item.hasPriceChange && toPrice != null) {
                    add(stringResource(R.string.refill_rebuild_new_price, formatEuro(toPrice)))
                }
                if (item.ageChanged) add(ageChangeLabel(item))
                if (!item.accepted) add(stringResource(R.string.refill_rebuild_not_this_tour))
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.accepted) StockOrange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// At the machine
// ─────────────────────────────────────────────────────────────────────────

/** Everything [RebuildSection] shows, resolved by the caller from `RefillUiState`. */
data class RebuildSectionState(
    val slots: List<RebuildSlot>,
    /**
     * Leftovers are booked back at the end of the tour, which needs a
     * warehouse; without one the "take it with you" hint is not shown.
     */
    val showReturnHint: Boolean
)

/** [RebuildSection]'s callbacks, bundled so the refill step's signature stays readable. */
data class RebuildActions(
    val onRemoved: (itemId: String, value: Int) -> Unit,
    val onFilled: (itemId: String, value: Int) -> Unit,
    val onAction: (itemId: String, action: RebuildAction?) -> Unit,
    val onPriceSet: (itemId: String, value: Boolean) -> Unit,
    val onAgeSet: (itemId: String, value: Boolean) -> Unit
)

@Composable
fun RebuildSection(
    state: RebuildSectionState,
    actions: RebuildActions,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = StockOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.refill_rebuild_at_machine_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = StockOrange
            )
        }

        if (state.showReturnHint) {
            Text(
                text = stringResource(R.string.refill_rebuild_take_along_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.slots.forEach { slot ->
            RebuildSlotCard(slot = slot, actions = actions, enabled = enabled)
        }
    }
}

@Composable
private fun RebuildSlotCard(slot: RebuildSlot, actions: RebuildActions, enabled: Boolean) {
    val item = slot.item
    val emptyLabel = stringResource(R.string.refill_rebuild_empty_slot)
    val borderColor = when (slot.action) {
        RebuildAction.DONE -> StockGreen
        RebuildAction.SKIP -> MaterialTheme.colorScheme.outlineVariant
        null -> StockOrange.copy(alpha = 0.6f)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "#${item.itemNumber}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                ProductImage(imagePath = item.fromImagePath, contentDescription = null, size = 32.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = item.fromName ?: emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(16.dp)
                )
                ProductImage(imagePath = item.toImagePath, contentDescription = null, size = 32.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = item.toName ?: emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(stringResource(R.string.refill_rebuild_live_stock, slot.liveStock, item.fromCapacity))
                    if (item.capacityChanges) {
                        append(" · ")
                        append(stringResource(R.string.refill_rebuild_change_spiral, item.fromCapacity, item.toCapacity))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (slot.action != RebuildAction.SKIP) {
                Spacer(modifier = Modifier.height(10.dp))
                NumberStepperField(
                    label = stringResource(R.string.refill_rebuild_removed),
                    value = slot.removed,
                    max = null,
                    enabled = enabled,
                    onValue = { actions.onRemoved(item.id, it) }
                )
                if (item.toProductId != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    NumberStepperField(
                        label = stringResource(R.string.refill_rebuild_filled, item.toCapacity),
                        value = slot.filled,
                        max = item.toCapacity,
                        enabled = enabled,
                        onValue = { actions.onFilled(item.id, it) }
                    )
                    Text(
                        text = stringResource(R.string.refill_rebuild_fill_source, slot.moved, slot.fromVan),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                val toPrice = item.toPrice
                if (item.hasPriceChange && toPrice != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StockOrange.copy(alpha = 0.10f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = enabled) { actions.onPriceSet(item.id, !slot.priceSet) }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = slot.priceSet,
                                onCheckedChange = { actions.onPriceSet(item.id, it) },
                                enabled = enabled
                            )
                            Text(
                                text = stringResource(R.string.refill_rebuild_price_set, item.itemNumber, formatEuro(toPrice)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                if (item.ageChanged) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StockOrange.copy(alpha = 0.10f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = enabled) { actions.onAgeSet(item.id, !slot.ageSet) }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = slot.ageSet,
                                onCheckedChange = { actions.onAgeSet(item.id, it) },
                                enabled = enabled
                            )
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    text = stringResource(R.string.refill_rebuild_age_set),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = ageChangeLabel(item),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StockOrange
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionButton(
                    label = stringResource(R.string.refill_rebuild_rebuilt),
                    selected = slot.action == RebuildAction.DONE,
                    selectedColor = StockGreen,
                    icon = Icons.Default.Check,
                    enabled = enabled && (slot.action == RebuildAction.DONE || !slot.needsAgeConfirmation),
                    onClick = {
                        actions.onAction(item.id, if (slot.action == RebuildAction.DONE) null else RebuildAction.DONE)
                    },
                    modifier = Modifier.weight(1f)
                )
                DecisionButton(
                    label = stringResource(R.string.refill_rebuild_not_rebuilt),
                    selected = slot.action == RebuildAction.SKIP,
                    selectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Default.Close,
                    enabled = enabled,
                    onClick = {
                        actions.onAction(item.id, if (slot.action == RebuildAction.SKIP) null else RebuildAction.SKIP)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (slot.action == null && slot.needsAgeConfirmation) {
                Text(
                    text = stringResource(R.string.refill_rebuild_age_confirm_first),
                    style = MaterialTheme.typography.labelSmall,
                    color = StockOrange,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (slot.action == RebuildAction.SKIP) {
                Text(
                    text = stringResource(R.string.refill_rebuild_stays_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** One age restriction as shown to the refiller: "ab 18" / "18+", or "ohne Altersgrenze". */
@Composable
private fun ageLabel(minAge: Int?): String =
    if (minAge == null) stringResource(R.string.refill_rebuild_age_none)
    else stringResource(R.string.refill_rebuild_age_min, minAge)

/** The age change of a slot spelled out, e.g. "ohne Altersgrenze → ab 18". */
@Composable
private fun ageChangeLabel(item: SlotChangeItem): String =
    "${ageLabel(item.fromMinAge)} → ${ageLabel(item.toMinAge)}"

@Composable
private fun DecisionButton(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) selectedColor else MaterialTheme.colorScheme.outline),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) selectedColor.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.defaultMinSize(minHeight = 48.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * −/field/+ for a count. The field takes typed numbers (a spiral of 14 is
 * a lot of taps); the buttons are for the usual ±1 correction. [max] `null`
 * means unbounded — "removed" is whatever physically came out.
 */
@Composable
internal fun NumberStepperField(
    label: String,
    value: Int,
    max: Int?,
    enabled: Boolean,
    onValue: (Int) -> Unit
) {
    // Re-seeded whenever the ViewModel's value changes (including a clamp),
    // so the field never shows a number the state doesn't hold for long.
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onValue((value - 1).coerceAtLeast(0)) },
            enabled = enabled && value > 0,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Remove, contentDescription = null)
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(4)
                text = digits
                digits.toIntOrNull()?.let(onValue)
            },
            label = { Text(label, maxLines = 1) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = { onValue(value + 1) },
            enabled = enabled && (max == null || value < max),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// End of the tour, back at the warehouse
// ─────────────────────────────────────────────────────────────────────────

/** One product of the summary's leftover card, with the refiller's current edits applied. */
data class TourLeftoverRow(
    val productId: String,
    val name: String?,
    val imagePath: String?,
    /** Unused (van) units — the computed default until the refiller changes it. */
    val van: Int,
    /** Units taken out of machines — likewise. */
    val machine: Int,
    val destination: LeftoverDestination,
    /** Best-before date for the machine units, `null` = none. */
    val expiry: String?
)

/** [TourLeftoverCard]'s callbacks. */
data class TourLeftoverActions(
    val onVan: (productId: String, value: Int) -> Unit,
    val onMachine: (productId: String, value: Int) -> Unit,
    val onDestination: (productId: String, destination: LeftoverDestination) -> Unit,
    val onExpiry: (productId: String, date: String?) -> Unit,
    val onReturn: () -> Unit
)

/**
 * "Leftover goods from rebuilds" — everything the tour's slot rebuilds left
 * in the van, per product across all machines. Shown on the summary, when
 * the refiller is back at the warehouse and can count what is really there:
 * both quantities are editable, then one "Book back" books everything
 * (`return_slot_change_leftovers`). Once booked only the confirmation stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourLeftoverCard(
    rows: List<TourLeftoverRow>,
    returned: Boolean,
    isReturning: Boolean,
    error: String?,
    actions: TourLeftoverActions,
    modifier: Modifier = Modifier
) {
    val enabled = !returned && !isReturning
    Card(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, (if (returned) StockGreen else StockOrange).copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (returned) Icons.Default.Check else Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = if (returned) StockGreen else StockOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (returned) R.string.refill_leftover_booked else R.string.refill_leftover_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (returned) return@Column

            Text(
                text = stringResource(R.string.refill_leftover_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp)) else Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(imagePath = row.imagePath, contentDescription = null, size = 32.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = row.name ?: stringResource(R.string.refill_pack_unknown_product),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                NumberStepperField(
                    label = stringResource(R.string.refill_leftover_van),
                    value = row.van,
                    max = null,
                    enabled = enabled,
                    onValue = { actions.onVan(row.productId, it) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                NumberStepperField(
                    label = stringResource(R.string.refill_leftover_machine),
                    value = row.machine,
                    max = null,
                    enabled = enabled,
                    onValue = { actions.onMachine(row.productId, it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = row.destination == LeftoverDestination.WAREHOUSE,
                        onClick = { actions.onDestination(row.productId, LeftoverDestination.WAREHOUSE) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.refill_rebuild_to_warehouse), maxLines = 1)
                    }
                    SegmentedButton(
                        selected = row.destination == LeftoverDestination.WASTE,
                        onClick = { actions.onDestination(row.productId, LeftoverDestination.WASTE) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = StockRed.copy(alpha = 0.15f),
                            activeContentColor = StockRed
                        )
                    ) {
                        Text(stringResource(R.string.refill_rebuild_write_off), maxLines = 1)
                    }
                }
                if (row.destination == LeftoverDestination.WAREHOUSE) {
                    if (row.machine > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        BestBeforeField(
                            dateIso = row.expiry,
                            enabled = enabled,
                            onChange = { actions.onExpiry(row.productId, it) }
                        )
                    }
                    val hints = buildList {
                        if (row.van > 0) add(stringResource(R.string.refill_rebuild_van_back_hint))
                        if (row.machine > 0) add(stringResource(R.string.refill_rebuild_machine_back_hint))
                    }
                    if (hints.isNotEmpty()) {
                        Text(
                            text = hints.joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.refill_leftover_error,
                        error.ifBlank { stringResource(R.string.refill_error_unknown) }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = StockRed
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = actions.onReturn,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                if (isReturning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.refill_leftover_book))
                }
            }
        }
    }
}

/**
 * Best-before date of goods taken out of a machine, pre-filled from the
 * batch of the last refill of that product into the machine. No lower
 * bound, unlike the warehouse intake's: goods coming *out* of a machine can
 * be past their date, and the record should say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BestBeforeField(dateIso: String?, enabled: Boolean, onChange: (String?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val label = stringResource(R.string.refill_rebuild_best_before)
    OutlinedButton(
        onClick = { showDialog = true },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: " + (dateIso?.let { formatBestBefore(it) } ?: stringResource(R.string.refill_rebuild_best_before_unset)),
            maxLines = 1
        )
    }

    if (showDialog) {
        val zone = ZoneOffset.UTC
        val initialMillis = remember(dateIso) {
            dateIso?.let { runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() }
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onChange(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString())
                    }
                    showDialog = false
                }) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onChange(null)
                    showDialog = false
                }) {
                    Text(stringResource(R.string.refill_rebuild_best_before_clear))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun formatBestBefore(dateIso: String): String =
    try {
        LocalDate.parse(dateIso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    } catch (_: Exception) {
        dateIso
    }
