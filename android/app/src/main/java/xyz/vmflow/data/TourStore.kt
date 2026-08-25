package xyz.vmflow.data

import android.content.Context
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import xyz.vmflow.VMflowApp
import xyz.vmflow.models.PersistedTourState
import xyz.vmflow.models.RefillStep

/**
 * Persists and resumes an in-progress refill tour so an app kill mid-tour
 * doesn't lose it. Mirrors iOS `RefillWizardViewModel` L305-437
 * (`PersistedTourState`, `checkForSavedTour`, `resumeTour`, `saveTourState`).
 *
 * Saving is only meaningful once a tour is actually running: the review and
 * pack steps have nothing worth rescuing (no `tourId` yet, nothing booked).
 * iOS enforces
 * that with a guard at the top of `saveTourState()` — the function that
 * *builds* the snapshot from live view-model properties before saving it.
 * This Kotlin API instead receives an already-built [PersistedTourState]
 * from the caller, so there is no "build" step here to guard. The brief
 * still attributes this guard to the persistence layer's contract, so
 * [save] enforces it itself: it is a no-op unless [PersistedTourState.step]
 * is [RefillStep.REFILL] or [RefillStep.SUMMARY]. Because `step` is typed
 * as [RefillStep] rather than a free-form string, there is no longer a way
 * for a caller to pass a value the guard doesn't recognize.
 *
 * [clock] is an injected seam for "now" (used for the 24h expiry check
 * below) so tests can pin a fixed instant instead of sleeping or freezing
 * the system clock. It defaults to the real wall clock.
 */
class TourStore(
    private val storage: KeyValueStore,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * No-op unless [PersistedTourState.step] is [RefillStep.REFILL] or
     * [RefillStep.SUMMARY].
     *
     * Stamps [PersistedTourState.savedAt] from this store's own clock,
     * overwriting whatever the caller put there: [load]'s age check compares
     * against the same clock, and letting the write and the read side use
     * different time sources is how an expiry check quietly stops meaning
     * anything.
     */
    fun save(state: PersistedTourState) {
        val isResumable = when (state.step) {
            // Neither pre-tour step is worth rescuing: no `tourId` has been
            // minted, nothing has been booked or charged, and the review's
            // suggestion list is derived from live data (warehouse stock,
            // batch expiry, discontinued flags) that may well have moved on
            // by the time the app comes back — restoring a stale list would
            // be worse than recomputing it, which the next load does anyway.
            RefillStep.REVIEW, RefillStep.PACKING -> false
            RefillStep.REFILL, RefillStep.SUMMARY -> true
        }
        if (!isResumable) return
        val stamped = state.copy(savedAt = clock.now().toString())
        storage.putString(STORAGE_KEY, json.encodeToString(stamped))
    }

    /**
     * Returns the saved state, or `null` if there is none, it can't be
     * parsed, or it is older than [MAX_AGE] (24h — the same window iOS and
     * the PWA use, `RefillWizardViewModel.swift:314-315` "matches web" and
     * `useRefillWizard.ts`'s `MAX_AGE_MS`; this is parity, NOT an Android
     * deviation, whatever the plan's Task 5 prose claimed). A corrupt or
     * expired payload is cleared so the app doesn't trip over it again on
     * the next launch.
     */
    fun load(): PersistedTourState? {
        val raw = storage.getString(STORAGE_KEY) ?: return null
        val state = runCatching { json.decodeFromString<PersistedTourState>(raw) }.getOrNull()
        if (state == null) {
            clear()
            return null
        }
        val savedAt = runCatching { Instant.parse(state.savedAt) }.getOrNull()
        if (savedAt == null || clock.now() - savedAt > MAX_AGE) {
            clear()
            return null
        }
        return state
    }

    fun clear() {
        storage.putString(STORAGE_KEY, null)
    }

    /**
     * Whether a valid, non-expired saved tour exists.
     *
     * Reading this property can *write*: it calls [load], which clears the
     * stored blob when it turns out to be expired or corrupt. A debugger
     * watch or a UI binding that re-reads this repeatedly will re-attempt
     * that clear on every read — harmless, but worth knowing before you
     * treat this as a pure getter.
     */
    val hasSavedTour: Boolean
        get() = load() != null

    companion object {
        private const val STORAGE_KEY = "refill-tour-state"
        private val MAX_AGE = 24.hours
    }
}

/**
 * App-wide [TourStore], backed by SharedPreferences — same shape as
 * [ServerStoreHolder], kept next to the class it holds.
 */
object RefillTourStoreHolder {
    private const val PREFS = "vmflow_refill_tour"

    val instance: TourStore by lazy {
        val prefs = VMflowApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TourStore(
            storage = object : KeyValueStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String?) {
                    prefs.edit().putString(key, value).apply()
                }
            }
        )
    }
}
