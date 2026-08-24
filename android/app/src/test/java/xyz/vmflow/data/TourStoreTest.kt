package xyz.vmflow.data

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.vmflow.models.PersistedTourState
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillStep
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TourLogEntry
import xyz.vmflow.models.VendingMachineWithEmbedded

/**
 * Mirrors `ios/VMflow/ViewModels/RefillWizardViewModel.swift` L305-437
 * (`PersistedTourState`, `checkForSavedTour`, `resumeTour`, `saveTourState`).
 */
class TourStoreTest {

    private class FakeStorage : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val fixedNow = Instant.parse("2026-08-24T12:00:00Z")

    private lateinit var storage: FakeStorage
    private lateinit var clock: FakeClock
    private lateinit var store: TourStore

    @Before
    fun setUp() {
        storage = FakeStorage()
        clock = FakeClock(fixedNow)
        store = TourStore(storage, clock)
    }

    private fun machine(id: String = "m1") = RefillMachine(
        machine = VendingMachineWithEmbedded(id = id, name = "Machine $id"),
        trays = listOf(
            RefillTray(
                tray = Tray(id = "t1", machineId = id, itemNumber = 1, productId = "p1", capacity = 10, currentStock = 4),
                fillAmount = 6
            )
        )
    )

    private fun state(
        step: RefillStep = RefillStep.REFILL,
        savedAt: Instant = fixedNow,
        tourId: String = "tour-1"
    ) = PersistedTourState(
        step = step,
        machines = listOf(machine()),
        currentMachineIndex = 0,
        selectedWarehouseId = "wh-1",
        tourId = tourId,
        tourLog = listOf(TourLogEntry("m1", "Machine m1", traysRefilled = 1, totalAdded = 6, skipped = false)),
        savedAt = savedAt.toString()
    )

    // ─── roundtrip ──────────────────────────────────────────────────────

    @Test
    fun `save then load returns an equal state`() {
        val original = state()
        store.save(original)
        assertEquals(original, store.load())
    }

    @Test
    fun `load after clear is null`() {
        store.save(state())
        store.clear()
        assertNull(store.load())
    }

    // ─── corrupt payload ────────────────────────────────────────────────

    @Test
    fun `load returns null for unparseable json`() {
        storage.putString("refill-tour-state", "{not json")
        assertNull(store.load())
    }

    @Test
    fun `load clears the stored key after unparseable json`() {
        storage.putString("refill-tour-state", "{not json")
        store.load()
        assertNull(storage.getString("refill-tour-state"))
    }

    @Test
    fun `load returns null for valid json with an unparseable savedAt`() {
        val json = """
            {"step":"REFILL","machines":[],"currentMachineIndex":0,"selectedWarehouseId":null,
             "tourId":"tour-1","tourLog":[],"savedAt":"not-an-instant"}
        """.trimIndent()
        storage.putString("refill-tour-state", json)
        assertNull(store.load())
    }

    @Test
    fun `load clears the stored key for valid json with an unparseable savedAt`() {
        val json = """
            {"step":"REFILL","machines":[],"currentMachineIndex":0,"selectedWarehouseId":null,
             "tourId":"tour-1","tourLog":[],"savedAt":"not-an-instant"}
        """.trimIndent()
        storage.putString("refill-tour-state", json)
        store.load()
        assertNull(storage.getString("refill-tour-state"))
    }

    // ─── expiry ─────────────────────────────────────────────────────────

    @Test
    fun `load returns null for a state saved more than 24h ago`() {
        val old = state(savedAt = fixedNow - 25.hours)
        store.save(old)
        assertNull(store.load())
    }

    @Test
    fun `load clears the stored key for an expired state`() {
        val old = state(savedAt = fixedNow - 25.hours)
        store.save(old)
        store.load()
        assertNull(storage.getString("refill-tour-state"))
    }

    @Test
    fun `a state saved just under 24h ago is still loaded`() {
        val fresh = state(savedAt = fixedNow - 23.hours)
        store.save(fresh)
        assertEquals(fresh, store.load())
    }

    @Test
    fun `a state saved exactly 24h ago is still loaded`() {
        val boundary = state(savedAt = fixedNow - 24.hours)
        store.save(boundary)
        assertEquals(boundary, store.load())
    }

    // ─── forward compatibility ──────────────────────────────────────────

    @Test
    fun `load ignores unknown fields in the saved json`() {
        val json = """
            {"step":"REFILL","machines":[],"currentMachineIndex":0,"selectedWarehouseId":null,
             "tourId":"tour-1","tourLog":[],"savedAt":"$fixedNow","futureField":"ignored"}
        """.trimIndent()
        storage.putString("refill-tour-state", json)
        val loaded = store.load()
        assertEquals("tour-1", loaded?.tourId)
        assertEquals(RefillStep.REFILL, loaded?.step)
    }

    // ─── the pack-step guard ────────────────────────────────────────────

    @Test
    fun `save is a no-op while the tour is still in the pack step`() {
        store.save(state(step = RefillStep.PACKING))
        assertNull(store.load())
    }

    @Test
    fun `save persists during the refill step`() {
        store.save(state(step = RefillStep.REFILL))
        assertEquals(RefillStep.REFILL, store.load()?.step)
    }

    @Test
    fun `save persists during the summary step`() {
        store.save(state(step = RefillStep.SUMMARY))
        assertEquals(RefillStep.SUMMARY, store.load()?.step)
    }

    // ─── hasSavedTour ───────────────────────────────────────────────────

    @Test
    fun `hasSavedTour is false with nothing stored`() {
        assertFalse(store.hasSavedTour)
    }

    @Test
    fun `hasSavedTour is true after a valid save`() {
        store.save(state())
        assertTrue(store.hasSavedTour)
    }

    @Test
    fun `hasSavedTour is false once the state has expired`() {
        store.save(state(savedAt = fixedNow - 25.hours))
        assertFalse(store.hasSavedTour)
    }
}
