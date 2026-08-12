package xyz.vmflow.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.models.ServerEntry

object SupabaseService {

    private fun build(server: ServerEntry): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = server.sanitizedUrl,
            supabaseKey = server.anonKey
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }

    private val _clientFlow = MutableStateFlow(build(ServerStoreHolder.instance.selectedServer.value))

    /**
     * The active client. Observe this rather than capturing [client] in a
     * `val`: switching servers replaces the instance, and anything holding
     * the old one silently stops receiving updates.
     */
    val clientFlow: StateFlow<SupabaseClient> = _clientFlow.asStateFlow()

    val client: SupabaseClient
        get() = _clientFlow.value

    // Scoped only to close orphaned clients in the background; never used
    // to observe or cancel anything tied to a particular client.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Rebuilds the client against [server]. Only valid while signed out. */
    fun reconfigure(server: ServerEntry) {
        val old = _clientFlow.value
        // Publish the new client before closing the old one: closing first
        // would let an observer briefly read a closed client. Closing after
        // also stops the old client's realtime socket and auth auto-refresh
        // coroutine, which would otherwise keep refreshing the previous
        // server's tokens in the background. SupabaseClient.close() is a
        // suspend fun, so it runs on cleanupScope; a failure to close must
        // not prevent the switch, hence runCatching.
        _clientFlow.value = build(server)
        cleanupScope.launch { runCatching { old.close() } }
    }
}
