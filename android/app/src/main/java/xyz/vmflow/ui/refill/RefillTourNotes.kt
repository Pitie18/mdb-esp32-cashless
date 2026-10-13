package xyz.vmflow.ui.refill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.MachineTourNote
import xyz.vmflow.data.TourNotes
import xyz.vmflow.ui.machines.relativeTimeAgo
import xyz.vmflow.ui.theme.VMflowBlue

/**
 * Packing step: the open "notes for the next tour" ("Notizen vom letzten
 * Mal"), shown at the very top. Starting the tour checks them off.
 *
 * @param machineNames `machineId -> display name` fallback for a note whose
 *   `vendingMachine(name)` join came back without a name.
 */
@Composable
fun TourNotesCard(
    notes: List<MachineTourNote>,
    machineNames: Map<String, String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VMflowBlue.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = VMflowBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.refill_tour_notes_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = VMflowBlue
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.refill_tour_notes_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            notes.forEachIndexed { index, note ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                else Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = note.machineName
                            ?: machineNames[note.machineId]
                            ?: stringResource(R.string.product_unnamed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (note.createdAt.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = relativeTimeAgo(note.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = note.note,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Refill step: write the machine's note for the next tour. Prefilled with
 * this tour's existing note once it has loaded; saving an empty text deletes
 * it. Errors stay inline, success closes the sheet (the ViewModel does both).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourNoteSheet(
    state: TourNoteEditorState,
    machineName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Keyed on the loaded text: the prefill arrives after the sheet opened,
    // and must replace the empty field it opened with (once).
    var text by rememberSaveable(state.machineId, state.isLoading, state.initialText) {
        mutableStateOf(state.initialText)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.refill_tour_note_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = machineName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.refill_tour_note_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(TourNotes.MAX_LENGTH) },
                    placeholder = { Text(stringResource(R.string.refill_tour_note_placeholder)) },
                    enabled = !state.isSaving,
                    minLines = 4,
                    maxLines = 8,
                    supportingText = { Text("${text.length} / ${TourNotes.MAX_LENGTH}") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.error?.let { message ->
                Text(
                    text = if (message.isBlank()) {
                        stringResource(R.string.refill_tour_note_save_failed)
                    } else {
                        stringResource(R.string.refill_tour_note_save_failed_detail, message)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onSave(text) },
                    enabled = !state.isLoading && !state.isSaving
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
