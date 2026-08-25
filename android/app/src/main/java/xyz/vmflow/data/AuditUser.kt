package xyz.vmflow.data

import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a `user_metadata` value as a real string, treating JSON null as absent.
 *
 * `JsonNull` is itself a [JsonPrimitive] whose `content` is the literal string
 * `"null"`, so the obvious `(value as? JsonPrimitive)?.content` turns an
 * explicit `"first_name": null` into the four-character word — which then gets
 * written into an audit row and rendered as the person who did the thing.
 */
internal fun JsonElement?.asNonNullString(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * The name to record as the author of an `activity_log` row: first and last
 * name from the user metadata, falling back to the e-mail when neither is set.
 *
 * Lives here rather than in each writer because three of them derived it
 * independently, and one of them derived it wrongly (see [asNonNullString]) —
 * an audit trail that misnames who acted is worse than one that says nothing.
 * The result is written to the `_user_display` metadata key, which the PWA and
 * iOS both read.
 */
internal fun UserInfo.auditDisplayName(): String? {
    val firstName = userMetadata?.get("first_name").asNonNullString()
    val lastName = userMetadata?.get("last_name").asNonNullString()
    val fullName = listOfNotNull(firstName, lastName).joinToString(" ").trim()
    return fullName.ifEmpty { email }
}
