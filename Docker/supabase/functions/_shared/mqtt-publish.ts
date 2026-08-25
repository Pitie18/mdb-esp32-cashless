/**
 * Shared MQTT publish helper for edge functions.
 *
 * Uses npm:mqtt@5 over WebSocket (ws://) because the Supabase Edge Runtime
 * sandboxes Deno.connect() — raw TCP is blocked, but WebSocket (HTTP upgrade)
 * is allowed. Mosquitto listens on port 9001 for WebSocket connections.
 */
import mqtt from 'npm:mqtt@5'

const MQTT_CONNECT_TIMEOUT_MS = 5_000

/**
 * Read an env var, treating an empty/whitespace value as absent.
 *
 * `Deno.env.get(x) ?? fallback` only catches undefined. A variable that is
 * declared but resolves to an empty string slips straight through, and the
 * failure is silent and ugly: an unset MQTT_WS_PORT builds `ws://host:`
 * rather than falling back to 9001. That is a live risk for the Supabase CLI
 * dev stack, where config.toml declares MQTT_WS_PORT as env(MQTT_WS_PORT)
 * and nothing guarantees the key exists in Docker/supabase/.env.
 */
function envOr(name: string, fallback: string): string {
  const v = Deno.env.get(name)?.trim()
  return v ? v : fallback
}

/**
 * Connect to the MQTT broker via WebSocket, publish a message, and disconnect.
 * Throws on connection failure or timeout.
 */
export async function mqttPublish(
  topic: string,
  payload: string | Uint8Array,
  options?: { qos?: 0 | 1 | 2 },
): Promise<void> {
  // Server-side address: always the internal broker, never the public
  // hostname devices use (that one is MQTT_PUBLIC_HOST, and it is only ever
  // handed out by claim-device -- never dialled from here).
  const host = envOr('MQTT_HOST', 'broker')
  const wsPort = envOr('MQTT_WS_PORT', '9001')
  const user = envOr('MQTT_ADMIN_USER', 'admin')
  const pass = envOr('MQTT_ADMIN_PASS', 'admin')

  const client = mqtt.connect(`ws://${host}:${wsPort}`, {
    username: user,
    password: pass,
    connectTimeout: MQTT_CONNECT_TIMEOUT_MS,
  })

  try {
    // Wait for connection (or fail fast)
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`MQTT connection timeout after ${MQTT_CONNECT_TIMEOUT_MS}ms`)),
        MQTT_CONNECT_TIMEOUT_MS,
      )
      client.on('connect', () => { clearTimeout(timer); resolve() })
      client.on('error', (err: Error) => { clearTimeout(timer); reject(err) })
    })

    // Publish
    await client.publishAsync(topic, payload, { qos: options?.qos ?? 1 })
  } finally {
    // Graceful disconnect — waits for in-flight messages to be sent
    try { await client.endAsync() } catch (_) { /* best-effort cleanup */ }
  }
}
