package xyz.vmflow.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Rebuilds the client against [server]. Only valid while signed out. */
    fun reconfigure(server: ServerEntry) {
        _clientFlow.value = build(server)
    }
}
