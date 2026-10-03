package xyz.vmflow.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server configuration encoded in the QR code shown by the web
 * dashboard's /mobile-app page.
 *
 * The wire format is a cross-client contract already honoured by iOS
 * (`AddServerView.handleQRCode`): `{"v":1,"url":...,"anonKey":...}`.
 * Unknown keys are tolerated so the web side can extend it; an unknown
 * `v` is rejected outright rather than guessed at.
 */
data class ServerQrPayload(val url: String, val anonKey: String) {
    companion object {
        private const val SUPPORTED_VERSION = 1

        fun parse(raw: String): ServerQrPayload? {
            val obj = runCatching {
                Json.parseToJsonElement(raw).jsonObject
            }.getOrNull() ?: return null

            val version = runCatching { obj["v"]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()
            if (version != SUPPORTED_VERSION) return null

            val url = runCatching { obj["url"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val anonKey = runCatching { obj["anonKey"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (url.isBlank() || anonKey.isBlank()) return null

            return ServerQrPayload(url = url, anonKey = anonKey)
        }
    }
}
