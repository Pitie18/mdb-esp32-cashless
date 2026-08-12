package xyz.vmflow.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
import xyz.vmflow.data.SupabaseService
import xyz.vmflow.models.ServerEntry

/**
 * Picks which backend the app talks to. Only reachable while signed
 * out — switching rebuilds the Supabase client.
 *
 * Edit and delete are visible icon buttons rather than swipe actions:
 * on Android a swipe-to-edit affordance is undiscoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectionSheet(onDismiss: () -> Unit) {
    val store = ServerStoreHolder.instance
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customServers by store.customServers.collectAsState()
    val selected by store.selectedServer.collectAsState()

    var editing by remember { mutableStateOf<ServerEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerEntry?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.server_select_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            (listOf(store.defaultServer) + customServers).forEach { server ->
                ListItem(
                    modifier = Modifier.clickable {
                        store.selectServer(server)
                        SupabaseService.reconfigure(server)
                    },
                    leadingContent = {
                        RadioButton(
                            selected = server.id == selected.id,
                            onClick = {
                                store.selectServer(server)
                                SupabaseService.reconfigure(server)
                            },
                        )
                    },
                    headlineContent = { Text(server.name) },
                    supportingContent = {
                        Text(server.url, style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        if (server.isDefault) {
                            Icon(Icons.Default.Cloud, contentDescription = null)
                        } else {
                            Row {
                                IconButton(onClick = { editing = server }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.server_edit),
                                    )
                                }
                                IconButton(onClick = { pendingDelete = server }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.server_delete),
                                    )
                                }
                            }
                        }
                    },
                )
            }

            ListItem(
                modifier = Modifier.clickable { adding = true },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.server_add)) },
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }

    if (adding) {
        AddEditServerSheet(editing = null, onDismiss = { adding = false })
    }

    editing?.let { server ->
        AddEditServerSheet(editing = server, onDismiss = { editing = null })
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.server_delete_confirm, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteServer(server)
                    SupabaseService.reconfigure(store.selectedServer.value)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
