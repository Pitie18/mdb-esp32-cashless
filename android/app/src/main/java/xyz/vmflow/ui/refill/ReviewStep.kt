package xyz.vmflow.ui.refill

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ReplacementReason
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.ReasonDiscontinuedDark
import xyz.vmflow.ui.theme.ReasonDiscontinuedLight
import xyz.vmflow.ui.theme.ReasonExpiredDark
import xyz.vmflow.ui.theme.ReasonExpiredLight
import xyz.vmflow.ui.theme.ReasonNoStockDark
import xyz.vmflow.ui.theme.ReasonNoStockLight
import xyz.vmflow.ui.theme.ReasonUnassignedDark
import xyz.vmflow.ui.theme.ReasonUnassignedLight
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed

/**
 * Review step — the wizard's first screen, and the last chance to take a
 * slot's dead product out of a machine before the van is packed for it. One
 * card per [xyz.vmflow.data.ReplacementSuggestion]: why the slot surfaced,
 * where it is, what sits in it now, and the driver's decision — a
 * replacement product, or an explicit skip.
 *
 * Ported from iOS `ReviewStepView.swift` (header L21-33, `replacementCard`
 * L88-259, `reasonBadge` L271-289, `bottomBar` L395-437), adapted to Android
 * idiom: a [LazyColumn] rather than `ScrollView` + `ForEach`, Material
 * buttons, and a wrapping [FlowRow] wherever two labels sit side by side (a
 * plain `Row` has pushed content off a 360 dp screen twice in this module
 * already — see [PackingStep]'s chip bar).
 *
 * **No ViewModel reference**, like [PackingStep] and [RefillStepContent]:
 * immutable [RefillUiState] in, already-bound callbacks out.
 *
 * Three deliberate divergences from iOS, all about *changing* a decision
 * rather than only making one:
 *
 *  1. **A chosen card can still be skipped.** iOS shows only "Change" once a
 *     replacement is picked, so a chosen slot can never be un-chosen. That is
 *     not survivable here: a tray deleted server-side between detection and
 *     apply makes its write fail *permanently*
 *     ([RefillViewModel.applyReplacementsAndContinue] documents this), and
 *     because the write loop stops at the first failure, that one dead slot
 *     blocks every slot behind it on every retry. The only escape is
 *     un-choosing it, so [onSkipReplacement] stays reachable from the chosen
 *     state too ([RefillViewModel.skipReplacement] clears
 *     `replacementProductId`, which is exactly that un-choose). A slot that
 *     already *committed* is the opposite case and stays out of it — see
 *     [AppliedDecision]; the dead slot this divergence exists for is never
 *     one of those, since its write is precisely the one that failed.
 *  2. **A skipped card offers "choose a replacement"**, not iOS's "Undo".
 *     iOS's Undo assigns `isSkipped = false` on the published array directly;
 *     this ViewModel exposes no such action, and adding one is out of scope.
 *     [RefillViewModel.setReplacement] clears `isSkipped` by itself, so
 *     picking a product *is* the undo — and "undecided" is not a state
 *     anything needs to get back to: all it does is disable Continue.
 *  3. **A chosen product missing from [RefillUiState.availableProducts] still
 *     renders as chosen**, under the shared "unknown product" label. iOS's
 *     `if let product = availableProducts.first(...)` falls through to its
 *     needs-action branch instead, which contradicts itself: the decision
 *     *is* made (`allReplacementsHandled` true, Continue enabled) while the
 *     card claims it is not.
 *
 * @param uiState drives everything: [RefillUiState.replacements] the cards,
 *   [RefillUiState.availableProducts] the chosen product's name and image,
 *   [RefillUiState.isApplyingReplacements] every control's enabled state and
 *   the bottom bar's spinner, [allReplacementsHandled] the Continue gate.
 * @param onOpenPicker opens the replacement picker for one tray — the one
 *   thing this screen cannot do itself. See the call site in
 *   [RefillWizardScreen] for the seam the picker task fills.
 * @param onSkipReplacement `RefillViewModel::skipReplacement`.
 * @param onSkipAll `RefillViewModel::skipReview` — skips every *undecided*
 *   card and then applies the replacements already chosen; it is not
 *   "discard everything".
 * @param onContinue `RefillViewModel::applyReplacementsAndContinue`.
 */
@Composable
fun ReviewStep(
    uiState: RefillUiState,
    onOpenPicker: (trayId: String) -> Unit,
    onSkipReplacement: (trayId: String) -> Unit,
    onSkipAll: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = reviewRows(uiState)

    // The whole screen goes inert while the writes are in flight. The
    // ViewModel's own guards (`if (snapshot.isApplyingReplacements) return`)
    // stop a second *apply*, but nothing there stops a decision being
    // changed underneath one. So the UI owns this: one apply in flight, no
    // edits beneath it.
    //
    // This covers the in-flight window only. The window that *outlives* it —
    // a partial failure, where the screen unlocks with some slots already
    // committed — is handled per card by [AppliedDecision], because a
    // committed slot must stop being editable permanently, not just while a
    // write is running.
    val locked = uiState.isApplyingReplacements

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") { ReviewHeader() }

            items(items = rows, key = { it.trayId }) { row ->
                ReplacementCard(
                    row = row,
                    enabled = !locked,
                    onOpenPicker = { onOpenPicker(row.trayId) },
                    onSkip = { onSkipReplacement(row.trayId) }
                )
            }
        }

        ReviewBottomBar(
            isApplying = locked,
            canContinue = uiState.allReplacementsHandled,
            hasAnyReplacement = rows.any { it.chosen != null },
            onSkipAll = onSkipAll,
            onContinue = onContinue
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Row state. Resolved once in [ReviewStep]'s body — which never skips, since
// it holds the unstable ui state — and handed to the leaf composables as
// plain values, the same split as [PackingStep]'s `packRows`. Keeps the
// string and product-catalogue lookups out of the `LazyColumn` item bodies.
// ─────────────────────────────────────────────────────────────────────────

/** The product a driver picked for a slot, once they have picked one. */
private data class ChosenProduct(
    val name: String,
    val imagePath: String?
)

/** One review card. */
private data class ReviewRowState(
    val trayId: String,
    val machineName: String,
    val slotNumber: Int,
    val reason: ReplacementReason,
    /**
     * The product sitting in the slot now, already resolved: a missing name
     * becomes the localized `R.string.machine_card_unassigned_slot` with the
     * slot number, the same convention `CombinedPackingItem.productName` and
     * `IntakeEntry.productName` follow. `ReplacementSuggestion.currentProductName`
     * is nullable by design — `RefillReviewLogic` stays pure and does not
     * synthesize display text.
     */
    val currentProductLabel: String,
    val currentProductImage: String?,
    val currentStock: Int,
    /** No product in the slot at all: nothing to strike through, "Assign" not "Replace". */
    val isUnassigned: Boolean,
    val isSkipped: Boolean,
    /**
     * This slot's write already committed — [xyz.vmflow.data.ReplacementSuggestion.isApplied].
     * Not a decision any more, and it outranks [isSkipped] and [chosen] when
     * the card is rendered: see [AppliedDecision] for why leaving it editable
     * silently dropped the driver's newer choice.
     */
    val isApplied: Boolean,
    /** `null` until the driver picks a replacement. */
    val chosen: ChosenProduct?
)

@Composable
private fun reviewRows(uiState: RefillUiState): List<ReviewRowState> {
    val context = LocalContext.current
    val unknownProduct = stringResource(R.string.refill_pack_unknown_product)

    return uiState.replacements.map { suggestion ->
        val chosen = suggestion.replacementProductId?.let { productId ->
            val product = uiState.availableProducts.find { it.id == productId }
            ChosenProduct(
                // A product that is not in `availableProducts` is still a
                // decision — label it rather than pretend no choice was made
                // (divergence 3 in this file's header).
                name = product?.name?.takeIf { it.isNotBlank() } ?: unknownProduct,
                imagePath = product?.imagePath
            )
        }

        ReviewRowState(
            trayId = suggestion.trayId,
            machineName = suggestion.machineName,
            slotNumber = suggestion.slotNumber,
            reason = suggestion.reason,
            currentProductLabel = suggestion.currentProductName?.takeIf { it.isNotBlank() }
                ?: context.getString(
                    R.string.machine_card_unassigned_slot,
                    suggestion.slotNumber
                ),
            currentProductImage = suggestion.currentProductImage,
            currentStock = suggestion.currentStock,
            isUnassigned = suggestion.reason == ReplacementReason.UNASSIGNED,
            isSkipped = suggestion.isSkipped,
            isApplied = suggestion.isApplied,
            chosen = chosen
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            // The heading right below says the same thing.
            contentDescription = null,
            tint = StockOrange,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.refill_review_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.refill_review_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Card
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ReplacementCard(
    row: ReviewRowState,
    enabled: Boolean,
    onOpenPicker: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reasonColor = row.reason.reasonColor()

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // Top, not centre: the text column grows to three or four
                // lines on a narrow screen with a long product and machine
                // name, and a centred badge would then float.
                verticalAlignment = Alignment.Top
            ) {
                SlotBadge(slotNumber = row.slotNumber, color = reasonColor)
                Spacer(modifier = Modifier.width(12.dp))
                ProductImage(
                    imagePath = row.currentProductImage,
                    contentDescription = row.currentProductLabel,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.currentProductLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // iOS strikes the dead product through; an unassigned
                        // slot has no product to strike.
                        textDecoration = if (row.isUnassigned) {
                            null
                        } else {
                            TextDecoration.LineThrough
                        },
                        color = if (row.isUnassigned) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Badge + machine name wrap rather than clip: machine
                    // names are operator-chosen and routinely long.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ReasonBadge(reason = row.reason, color = reasonColor)
                        Text(
                            text = row.machineName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // Stock still physically in the machine. Not a detail: it
                    // is the difference between "swap it now" and "the driver
                    // sells the rest first".
                    if (row.currentStock > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.refill_review_stock_remaining,
                                row.currentStock,
                                row.currentStock
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = StockOrange
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.refill_review_stock_empty),
                            style = MaterialTheme.typography.labelMedium,
                            color = StockRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            when {
                // First, ahead of both decision branches: an applied slot is
                // no longer a decision, whatever `isSkipped`/`chosen` say.
                row.isApplied -> AppliedDecision(chosen = row.chosen)

                row.isSkipped -> SkippedDecision(
                    isUnassigned = row.isUnassigned,
                    enabled = enabled,
                    onOpenPicker = onOpenPicker
                )

                row.chosen != null -> ChosenDecision(
                    chosen = row.chosen,
                    enabled = enabled,
                    onOpenPicker = onOpenPicker,
                    onSkip = onSkip
                )

                else -> UndecidedDecision(
                    isUnassigned = row.isUnassigned,
                    enabled = enabled,
                    onOpenPicker = onOpenPicker,
                    onSkip = onSkip
                )
            }
        }
    }
}

/**
 * Slot number in the reason's colour — the card's anchor, and iOS's
 * `badgeColor(for:)` circle. Solid fill with an inverted content colour,
 * because the tokens are pale hues in dark mode (the same
 * `primary`/`onPrimary` inversion the Material scheme itself makes); white on
 * a pale red would not carry.
 *
 * A bare number reads as "5" to a screen reader, so the badge is relabelled
 * "Slot 5" — the existing string, reused as-is.
 */
@Composable
private fun SlotBadge(slotNumber: Int, color: Color) {
    val label = stringResource(R.string.machine_card_unassigned_slot, slotNumber)
    Surface(
        modifier = Modifier
            .defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
            .clearAndSetSemantics { contentDescription = label },
        shape = RoundedCornerShape(percent = 50),
        color = color
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = slotNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSystemInDarkTheme()) Color.Black else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/** iOS `reasonBadge` — the label in the reason's colour on a 15 % wash of it. */
@Composable
private fun ReasonBadge(reason: ReplacementReason, color: Color) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(reason.labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * "Skipped — keeping current product", plus the way back out of that
 * decision: picking a product clears the skip in the ViewModel, so this
 * button *is* iOS's Undo (divergence 2 in this file's header).
 */
@Composable
private fun SkippedDecision(
    isUnassigned: Boolean,
    enabled: Boolean,
    onOpenPicker: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isUnassigned) {
                    stringResource(R.string.refill_review_skipped_unassigned)
                } else {
                    stringResource(R.string.refill_review_skipped_keeping)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onOpenPicker,
            enabled = enabled,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.refill_review_action_choose))
        }
    }
}

/**
 * A slot whose write already **committed**: the tray now holds this product
 * and its stock has been zeroed. A done state, with no controls at all.
 *
 * This exists because of what a *partial* apply leaves behind. The write loop
 * in [RefillViewModel.applyReplacementsAndContinue] stops at the first
 * failure, marking every slot it got through as
 * [xyz.vmflow.data.ReplacementSuggestion.isApplied]; the screen then unlocks
 * (`isApplyingReplacements` is false again) and the driver retries. Rendering
 * those committed cards as ordinary decisions was silently lossy in both
 * directions:
 *
 *  - "Change" on a committed card kept `isApplied = true`, so the retry's
 *    filter (`!it.isApplied`) excluded it, the write never happened, and the
 *    slot quietly kept the *first* product — wrong products in wrong slots,
 *    with a green tick claiming otherwise.
 *  - "Skip" on a committed card claimed the slot keeps its current product,
 *    for a slot whose product had already been replaced and zeroed.
 *
 * Editing is therefore not merely disabled, it is *gone*: the decision has
 * already been executed and audit-logged from this suggestion's original
 * `currentProductId`/`currentProductName`, so re-deciding it would need the
 * ViewModel to clear `isApplied` **and** restamp those two fields to what was
 * actually written — otherwise the second audit row asserts a transition that
 * never happened. Nothing needs that; the slot can be changed again from the
 * machine's own tray screen after the tour.
 */
@Composable
private fun AppliedDecision(chosen: ChosenProduct?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StockGreen,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // `chosen` is non-null for every slot the write loop can have
        // applied (it only writes suggestions with a `replacementProductId`);
        // the null branch keeps the done state readable rather than blank if
        // that ever stops holding.
        if (chosen != null) {
            ProductImage(
                imagePath = chosen.imagePath,
                contentDescription = chosen.name,
                size = 32.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (chosen != null) {
                Text(
                    text = chosen.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = stringResource(R.string.refill_review_applied),
                style = MaterialTheme.typography.labelMedium,
                color = StockGreen
            )
        }
    }
}

/**
 * The picked product, with both ways out of the decision: change it, or drop
 * it and keep what the slot has. See divergence 1 in this file's header for
 * why the second one is not optional.
 */
@Composable
private fun ChosenDecision(
    chosen: ChosenProduct,
    enabled: Boolean,
    onOpenPicker: () -> Unit,
    onSkip: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StockGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            ProductImage(
                imagePath = chosen.imagePath,
                contentDescription = chosen.name,
                size = 32.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = chosen.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        DecisionButtons(
            primaryLabel = stringResource(R.string.refill_review_action_change),
            primaryIcon = Icons.Default.SwapHoriz,
            enabled = enabled,
            onPrimary = onOpenPicker,
            onSkip = onSkip
        )
    }
}

/** Nothing decided yet: assign/replace, or skip. iOS's needs-action branch. */
@Composable
private fun UndecidedDecision(
    isUnassigned: Boolean,
    enabled: Boolean,
    onOpenPicker: () -> Unit,
    onSkip: () -> Unit
) {
    DecisionButtons(
        primaryLabel = if (isUnassigned) {
            stringResource(R.string.refill_review_action_assign)
        } else {
            stringResource(R.string.refill_review_action_replace)
        },
        primaryIcon = if (isUnassigned) Icons.Default.Add else Icons.Default.SwapHoriz,
        enabled = enabled,
        onPrimary = onOpenPicker,
        onSkip = onSkip
    )
}

/**
 * The per-card control pair. A [FlowRow] with both buttons weighted: they
 * share the width when they fit and stack when they don't, which is what
 * makes "Zuweisen" next to "Überspringen" safe at 360 dp without a fixed
 * width anywhere.
 */
@Composable
private fun DecisionButtons(
    primaryLabel: String,
    primaryIcon: ImageVector,
    enabled: Boolean,
    onPrimary: () -> Unit,
    onSkip: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPrimary,
            enabled = enabled,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) {
            Icon(
                imageVector = primaryIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = primaryLabel)
        }
        OutlinedButton(
            onClick = onSkip,
            enabled = enabled,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.refill_review_action_skip))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Bottom bar
// ─────────────────────────────────────────────────────────────────────────

/**
 * "Skip rest" and "Apply & continue". Stacked full-width rather than placed
 * side by side, the same call [PackingStep]'s `PackBottomBar` made: the two
 * German labels ("Rest überspringen", "Anwenden & weiter") do not fit on one
 * 360 dp line, and a bottom bar that clips its primary action is worse than
 * a tall one.
 *
 * Both buttons are disabled while [isApplying] — the ViewModel's guards stop
 * a duplicated *write*, but only the UI can stop the driver from tapping a
 * button that looks live and does nothing.
 */
@Composable
private fun ReviewBottomBar(
    isApplying: Boolean,
    canContinue: Boolean,
    hasAnyReplacement: Boolean,
    onSkipAll: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = onSkipAll,
                enabled = !isApplying,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.refill_review_skip_all))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onContinue,
                // Same gate as the ViewModel's own
                // (`if (!snapshot.allReplacementsHandled) return`), mirrored
                // here so an undecided card reads as a disabled button rather
                // than a dead tap.
                enabled = canContinue && !isApplying,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (hasAnyReplacement) {
                            stringResource(R.string.refill_review_apply_continue)
                        } else {
                            stringResource(R.string.refill_review_continue)
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Reason styling
// ─────────────────────────────────────────────────────────────────────────

/**
 * Fixed hues, never `MaterialTheme.colorScheme`: this brand palette's
 * `primary`/`secondary`/`tertiary` collapse into near-identical tones in dark
 * mode (the finding that made `MachineAnalysisView`'s tier grid and
 * `RefillStep`'s stock levels use fixed tokens too), and four reasons that
 * look alike defeat the badge. Light/dark pairs from `ui/theme/Color.kt`,
 * matching iOS's red / orange / purple / blue.
 *
 * The reason is never carried by colour alone — [ReasonBadge] spells it out
 * next to the swatch.
 */
@Composable
private fun ReplacementReason.reasonColor(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        ReplacementReason.DISCONTINUED ->
            if (dark) ReasonDiscontinuedDark else ReasonDiscontinuedLight
        ReplacementReason.EXPIRED -> if (dark) ReasonExpiredDark else ReasonExpiredLight
        ReplacementReason.NO_STOCK -> if (dark) ReasonNoStockDark else ReasonNoStockLight
        ReplacementReason.UNASSIGNED ->
            if (dark) ReasonUnassignedDark else ReasonUnassignedLight
    }
}

/** iOS `reasonBadge`'s labels. */
private val ReplacementReason.labelRes: Int
    get() = when (this) {
        ReplacementReason.DISCONTINUED -> R.string.refill_review_reason_discontinued
        ReplacementReason.EXPIRED -> R.string.refill_review_reason_expired
        ReplacementReason.NO_STOCK -> R.string.refill_review_reason_no_stock
        ReplacementReason.UNASSIGNED -> R.string.refill_review_reason_unassigned
    }
