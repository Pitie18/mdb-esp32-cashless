package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.WarehouseIntakeLogic
import xyz.vmflow.models.AdjustReason
import xyz.vmflow.models.WarehouseStockBatch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Which way the batch's quantity moves. Purely local UI state — the sign is folded into [onConfirm]'s `quantityChange` before it ever reaches the ViewModel. */
private enum class AdjustDirection { REMOVE, ADD }

/** The four [AdjustReason] values paired with their display label resource, in the order they're offered to the user. */
private val REASON_OPTIONS = listOf(
    AdjustReason.DAMAGE to R.string.warehouse_adjust_reason_damage,
    AdjustReason.EXPIRED to R.string.warehouse_adjust_reason_expired,
    AdjustReason.CORRECTION to R.string.warehouse_adjust_reason_correction,
    AdjustReason.REFILL_RETURN to R.string.warehouse_adjust_reason_refill_return,
)

/**
 * Bottom sheet for adjusting one batch's quantity by a signed delta.
 * Mirrors iOS `BatchAdjustSheet` (`ios/VMflow/Views/Warehouse/BatchAdjustSheet.swift`):
 * a direction toggle (remove/add) combined with a positive quantity field
 * folds into the signed `quantityChange` int handed to [onConfirm], a reason
 * picker (all four [AdjustReason] values, always available regardless of
 * direction — a deliberate simplification of iOS's direction-filtered
 * reason list, since the DB doesn't enforce a direction/reason pairing
 * either), and an optional notes field.
 *
 * There is no confirmation dialog — per the task brief, iOS has none
 * either; the sheet itself is the confirmation. [onConfirm] is fired and
 * the sheet dismissed immediately; the caller (`WarehouseScreen.kt`) is
 * responsible for actually calling `WarehouseViewModel.adjustBatch(...)`.
 *
 * The quantity field reuses [WarehouseIntakeLogic.evaluateQuantityExpression]
 * so operators can type "2*12" here exactly like on the intake form.
 * Server-side clamping at zero (`WarehouseRepository.adjustBatch`) is not
 * duplicated here — the raw signed delta is passed straight through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAdjustSheet(
    batch: WarehouseStockBatch,
    onConfirm: (quantityChange: Int, reason: AdjustReason, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var direction by remember { mutableStateOf(AdjustDirection.REMOVE) }
    var reason by remember { mutableStateOf(AdjustReason.DAMAGE) }
    var quantityText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val evaluatedQuantity = remember(quantityText) { WarehouseIntakeLogic.evaluateQuantityExpression(quantityText) }
    val exceedsStock = direction == AdjustDirection.REMOVE && evaluatedQuantity != null && evaluatedQuantity > batch.quantity
    val canSubmit = evaluatedQuantity != null && !exceedsStock

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.warehouse_adjust_title), style = MaterialTheme.typography.titleLarge)

            // Read-only batch header: batch number + expiration on the left, current quantity on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = batch.batchNumber?.takeIf { it.isNotBlank() } ?: stringResource(R.string.warehouse_batch_no_number),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    batch.expirationDate?.let { exp ->
                        Text(
                            text = formatAdjustDate(exp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = batch.quantity.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.warehouse_adjust_current_stock_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Direction toggle.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    selected = direction == AdjustDirection.REMOVE,
                    onClick = { direction = AdjustDirection.REMOVE },
                    label = { Text(stringResource(R.string.warehouse_adjust_direction_remove)) },
                )
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    selected = direction == AdjustDirection.ADD,
                    onClick = { direction = AdjustDirection.ADD },
                    label = { Text(stringResource(R.string.warehouse_adjust_direction_add)) },
                )
            }

            // Quantity (supports expressions: 2*12, 100+50 — same evaluator as the intake form).
            Column {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = {
                        Text(
                            stringResource(
                                if (direction == AdjustDirection.REMOVE) {
                                    R.string.warehouse_adjust_quantity_remove_label
                                } else {
                                    R.string.warehouse_adjust_quantity_add_label
                                }
                            )
                        )
                    },
                    placeholder = { Text(stringResource(R.string.warehouse_intake_quantity_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (exceedsStock) {
                    Text(
                        text = stringResource(R.string.warehouse_adjust_exceeds_stock, batch.quantity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else if (evaluatedQuantity != null && quantityText.any { it in "+-*/x×" }) {
                    Text(
                        text = stringResource(R.string.warehouse_intake_quantity_preview, evaluatedQuantity),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Reason.
            Column {
                Text(
                    text = stringResource(R.string.warehouse_adjust_reason_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                REASON_OPTIONS.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reason = value }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == value, onClick = { reason = value })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }

            // Notes.
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.warehouse_adjust_notes_label)) },
                placeholder = { Text(stringResource(R.string.warehouse_intake_optional_hint)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val quantity = evaluatedQuantity ?: return@Button
                    val signedDelta = if (direction == AdjustDirection.REMOVE) -quantity else quantity
                    onConfirm(signedDelta, reason, notes.trim().ifEmpty { null })
                    onDismiss()
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (direction == AdjustDirection.REMOVE) {
                            R.string.warehouse_adjust_submit_remove
                        } else {
                            R.string.warehouse_adjust_submit_add
                        }
                    )
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

private fun formatAdjustDate(dateIso: String): String {
    return try {
        val date = java.time.LocalDate.parse(dateIso)
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    } catch (_: Exception) {
        dateIso
    }
}
