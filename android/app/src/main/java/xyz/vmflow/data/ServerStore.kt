package xyz.vmflow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import xyz.vmflow.BuildConfig
import xyz.vmflow.VMflowApp
import xyz.vmflow.models.ServerEntry

/** Minimal persistence seam so the store is testable without an Android runtime. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * The set of backends the user can pick from.
 *
 * The default entry is supplied by the build and is neither editable nor
 * deletable — matching ServerStore.swift. Switching servers is only
 * offered while signed out.
 */
class ServerStore(
    private val storage: KeyValueStore,
    val defaultServer: ServerEntry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _customServers = MutableStateFlow(loadCustomServers())
    val customServers: StateFlow<List<ServerEntry>> = _customServers.asStateFlow()

    private val _selectedServer = MutableStateFlow(loadSelectedServer())
    val selectedServer: StateFlow<ServerEntry> = _selectedServer.asStateFlow()

    val allServers: List<ServerEntry>
        get() = listOf(defaultServer) + _customServers.value

    fun selectServer(server: ServerEntry) {
        storage.putString(SELECTED_KEY, server.id)
        _selectedServer.value = server
    }

    fun addServer(server: ServerEntry) {
        _customServers.value = _customServers.value + server.copy(url = server.sanitizedUrl)
        persistCustomServers()
    }

    fun updateServer(server: ServerEntry) {
        // The build-supplied default is read-only, same as deleteServer.
        // Without this the selected entry could be replaced by a mutated
        // copy that is not in allServers and never gets persisted.
        if (server.isDefault) return
        val sanitized = server.copy(url = server.sanitizedUrl)
        _customServers.value = _customServers.value.map { if (it.id == server.id) sanitized else it }
        persistCustomServers()
        if (_selectedServer.value.id == server.id) _selectedServer.value = sanitized
    }

    fun deleteServer(server: ServerEntry) {
        if (server.isDefault) return
        _customServers.value = _customServers.value.filterNot { it.id == server.id }
        persistCustomServers()
        if (_selectedServer.value.id == server.id) selectServer(defaultServer)
    }

    private fun loadCustomServers(): List<ServerEntry> {
        val raw = storage.getString(SERVERS_KEY) ?: return emptyList()
        // Corrupt or older-format data must not brick the login screen.
        return runCatching { json.decodeFromString<List<ServerEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSelectedServer(): ServerEntry {
        val id = storage.getString(SELECTED_KEY) ?: return defaultServer
        return allServers.firstOrNull { it.id == id } ?: defaultServer
    }

    private fun persistCustomServers() {
        storage.putString(SERVERS_KEY, json.encodeToString(_customServers.value))
    }

    private companion object {
        const val SERVERS_KEY = "savedServers"
        const val SELECTED_KEY = "selectedServerId"
    }
}

/** The app-wide instance, backed by SharedPreferences. */
object ServerStoreHolder {
    private const val PREFS = "vmflow_servers"

    /** Fixed id so the default entry keeps its identity across launches. */
    const val DEFAULT_SERVER_ID = "00000000-0000-0000-0000-000000000001"

    val instance: ServerStore by lazy {
        val prefs = VMflowApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ServerStore(
            storage = object : KeyValueStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String?) {
                    prefs.edit().putString(key, value).apply()
                }
            },
            defaultServer = ServerEntry(
                id = DEFAULT_SERVER_ID,
                name = "VMflow Cloud",
                url = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
                isDefault = true,
            ),
        )
    }
}
