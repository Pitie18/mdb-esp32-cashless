package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerQrPayloadTest {

    /** Exactly what management-frontend's /mobile-app page emits. */
    private val valid = """{"v":1,"url":"https://supabase.example.com","anonKey":"eyJhbGciOi"}"""

    @Test
    fun `parses the payload the web dashboard produces`() {
        val parsed = ServerQrPayload.parse(valid)
        assertEquals("https://supabase.example.com", parsed?.url)
        assertEquals("eyJhbGciOi", parsed?.anonKey)
    }

    @Test
    fun `tolerates extra keys so the web side can add fields`() {
        val withExtra = """{"v":1,"url":"https://a.example.com","anonKey":"k","name":"Prod"}"""
        assertEquals("https://a.example.com", ServerQrPayload.parse(withExtra)?.url)
    }

    @Test
    fun `rejects an unknown version`() {
        assertNull(ServerQrPayload.parse("""{"v":2,"url":"https://a.example.com","anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing version`() {
        assertNull(ServerQrPayload.parse("""{"url":"https://a.example.com","anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing url`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing anon key`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"https://a.example.com"}"""))
    }

    @Test
    fun `rejects blank values`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"","anonKey":"k"}"""))
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"https://a.example.com","anonKey":""}"""))
    }

    @Test
    fun `rejects text that is not json`() {
        assertNull(ServerQrPayload.parse("https://a.example.com"))
        assertNull(ServerQrPayload.parse(""))
        assertNull(ServerQrPayload.parse("{not json"))
    }

    @Test
    fun `rejects a json array`() {
        assertNull(ServerQrPayload.parse("""[{"v":1,"url":"https://a.example.com","anonKey":"k"}]"""))
    }
}
