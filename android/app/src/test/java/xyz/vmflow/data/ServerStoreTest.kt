package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.vmflow.models.ServerEntry

class ServerStoreTest {

    private class FakeStorage : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val default = ServerEntry(
        id = "00000000-0000-0000-0000-000000000001",
        name = "VMflow Cloud",
        url = "https://supabase.vmflow.xyz",
        anonKey = "factory-key",
        isDefault = true,
    )

    private lateinit var storage: FakeStorage
    private lateinit var store: ServerStore

    private fun custom(id: String, url: String = "https://a.example.com") =
        ServerEntry(id = id, name = "Server $id", url = url, anonKey = "k", isDefault = false)

    @Before
    fun setUp() {
        storage = FakeStorage()
        store = ServerStore(storage, default)
    }

    @Test
    fun `a fresh store offers only the default and selects it`() {
        assertEquals(listOf(default), store.allServers)
        assertEquals(default, store.selectedServer.value)
    }

    @Test
    fun `added servers appear after the default`() {
        store.addServer(custom("a"))
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "a"), store.allServers.map { it.id })
    }

    @Test
    fun `added servers are stored with the url sanitized`() {
        store.addServer(custom("a", url = "https://a.example.com//"))
        assertEquals("https://a.example.com", store.allServers.first { it.id == "a" }.url)
    }

    @Test
    fun `custom servers survive a new store over the same storage`() {
        store.addServer(custom("a"))
        val reopened = ServerStore(storage, default)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "a"), reopened.allServers.map { it.id })
    }

    @Test
    fun `the selection survives a new store over the same storage`() {
        val a = custom("a")
        store.addServer(a)
        store.selectServer(a)
        val reopened = ServerStore(storage, default)
        assertEquals("a", reopened.selectedServer.value.id)
    }

    @Test
    fun `updating a server replaces it in place`() {
        store.addServer(custom("a"))
        store.updateServer(custom("a").copy(name = "Renamed"))
        assertEquals("Renamed", store.allServers.first { it.id == "a" }.name)
        assertEquals(2, store.allServers.size)
    }

    @Test
    fun `deleting the selected server falls back to the default`() {
        val a = custom("a")
        store.addServer(a)
        store.selectServer(a)
        store.deleteServer(a)
        assertEquals(default, store.selectedServer.value)
        assertEquals(listOf(default), store.allServers)
    }

    @Test
    fun `the default server cannot be deleted`() {
        store.deleteServer(default)
        assertTrue(store.allServers.contains(default))
    }

    @Test
    fun `a selection pointing at a deleted server falls back to the default`() {
        storage.putString("selectedServerId", "ghost")
        val reopened = ServerStore(storage, default)
        assertEquals(default, reopened.selectedServer.value)
    }

    @Test
    fun `corrupt stored json is ignored rather than crashing`() {
        storage.putString("savedServers", "{not json")
        val reopened = ServerStore(storage, default)
        assertEquals(listOf(default), reopened.allServers)
    }
}
