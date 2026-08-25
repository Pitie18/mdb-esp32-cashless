package xyz.vmflow.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bug this pins: `JsonNull` is itself a `JsonPrimitive`, so reading its
 * `content` yields the literal string `"null"` — which was being written into
 * `activity_log` as the person who performed the action.
 *
 * Only the JSON-null case discriminates against that guard being removed
 * (mutation-checked). The other four are characterization tests: they pin the
 * neighbouring behaviours a future edit to [asNonNullString] could otherwise
 * change unnoticed — identity pass-through, an absent value, a non-primitive,
 * and the empty string, which must stay an empty string here because the
 * display-name derivation is what turns it into the e-mail fallback.
 */
class AuditUserTest {

    @Test
    fun `a real string comes through unchanged`() {
        assertEquals("Lucien", JsonPrimitive("Lucien").asNonNullString())
    }

    @Test
    fun `an explicit JSON null reads as absent, not as the word null`() {
        assertNull(JsonNull.asNonNullString())
    }

    @Test
    fun `a missing value reads as absent`() {
        assertNull(null.asNonNullString())
    }

    @Test
    fun `a non-primitive value reads as absent`() {
        assertNull(JsonObject(emptyMap()).asNonNullString())
    }

    @Test
    fun `an empty string stays an empty string`() {
        // Not the same as absent: the display-name derivation trims and falls
        // back to the e-mail on empty, so this must not be swallowed here.
        assertEquals("", JsonPrimitive("").asNonNullString())
    }
}
