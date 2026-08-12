package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEntryTest {

    private fun entry(
        name: String = "My Server",
        url: String = "https://supabase.example.com",
        anonKey: String = "eyJhbGciOi",
    ) = ServerEntry(id = "id-1", name = name, url = url, anonKey = anonKey, isDefault = false)

    @Test
    fun `sanitizedUrl strips a single trailing slash`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com/").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl strips repeated trailing slashes`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com///").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl leaves a clean url alone`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl keeps a path segment`() {
        assertEquals("https://a.example.com/api", entry(url = "https://a.example.com/api/").sanitizedUrl)
    }

    @Test
    fun `a fully populated https entry is valid`() {
        assertTrue(entry().isValid)
    }

    @Test
    fun `http is accepted for lan servers`() {
        assertTrue(entry(url = "http://10.0.1.181:8000").isValid)
    }

    @Test
    fun `blank fields are invalid`() {
        assertFalse(entry(name = "").isValid)
        assertFalse(entry(url = "").isValid)
        assertFalse(entry(anonKey = "").isValid)
    }

    @Test
    fun `a url without a scheme is invalid`() {
        assertFalse(entry(url = "supabase.example.com").isValid)
    }

    @Test
    fun `a non http scheme is invalid`() {
        assertFalse(entry(url = "ftp://a.example.com").isValid)
    }

    @Test
    fun `a url without a host is invalid`() {
        assertFalse(entry(url = "https://").isValid)
    }
}
