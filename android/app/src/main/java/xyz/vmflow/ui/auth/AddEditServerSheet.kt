package xyz.vmflow.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
import xyz.vmflow.data.SupabaseService
import xyz.vmflow.models.ServerEntry
import java.util.UUID

/**
 * Create or edit a self-hosted server. The build-supplied default is
 * never passed here — it is not editable, matching iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerSheet(
    editing: ServerEntry?,
    onDismiss: () -> Unit,
) {
    val store = ServerStoreHolder.instance
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember { mutableStateOf(editing?.url.orEmpty()) }
    var anonKey by remember { mutableStateOf(editing?.anonKey.orEmpty()) }

    val draft = ServerEntry(
        id = editing?.id ?: "",
        name = name,
        url = url,
        anonKey = anonKey,
        isDefault = false,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(if (editing == null) R.string.server_add else R.string.server_edit),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_name)) },
                placeholder = { Text(stringResource(R.string.server_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text("https://supabase.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = anonKey,
                onValueChange = { anonKey = it },
                label = { Text(stringResource(R.string.server_anon_key)) },
                placeholder = { Text("eyJhbGciOi...") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (editing == null) {
                        store.addServer(draft.copy(id = UUID.randomUUID().toString()))
                    } else {
                        store.updateServer(draft)
                        // Editing the server we are currently pointed at has to
                        // rebuild the client, otherwise the app keeps talking to
                        // the old URL and key until the next launch.
                        if (store.selectedServer.value.id == draft.id) {
                            SupabaseService.reconfigure(store.selectedServer.value)
                        }
                    }
                    onDismiss()
                },
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
