package xyz.vmflow.models

import kotlinx.serialization.Serializable

/**
 * One Supabase backend the app can talk to.
 *
 * The default entry comes from the build configuration and is not
 * editable in the app; everything else is user-defined. Mirrors
 * ServerEntry.swift on iOS so both clients accept the same QR payload.
 */
@Serializable
data class ServerEntry(
    val id: String,
    val name: String,
    val url: String,
    val anonKey: String,
    val isDefault: Boolean,
) {
    /** Trailing slashes break Supabase's URL joining, so drop them. */
    val sanitizedUrl: String
        get() = url.trimEnd('/')

    val isValid: Boolean
        get() {
            if (name.isBlank() || url.isBlank() || anonKey.isBlank()) return false
            val parsed = runCatching { java.net.URI(sanitizedUrl) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            return !parsed.host.isNullOrBlank()
        }
}
