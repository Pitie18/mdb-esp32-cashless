package xyz.vmflow.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Note for next tour": which open notes the packing step shows, and the
 * shape of a `machine_tour_notes` row with its `vendingMachine(name)` join.
 * Same rules as web and iOS.
 */
class TourNotesTest {

    private fun note(
        id: String,
        createdAt: String,
        writtenInTour: String? = null,
        doneAt: String? = null,
        machineId: String = "m1"
    ) = MachineTourNote(
        id = id,
        machineId = machineId,
        note = "bring spirals",
        writtenInTour = writtenInTour,
        createdAt = createdAt,
        doneAt = doneAt
    )

    @Test
    fun `only open notes, oldest first`() {
        val notes = listOf(
            note("b", "2026-10-12T10:00:00+00:00"),
            note("done", "2026-10-01T10:00:00+00:00", doneAt = "2026-10-05T10:00:00+00:00"),
            note("a", "2026-10-10T10:00:00.123456+00:00")
        )
        assertEquals(listOf("a", "b"), TourNotes.forPacking(notes, currentTourId = null).map { it.id })
    }

    @Test
    fun `sorts by instant, not by the raw string`() {
        // 09:00+02:00 is 07:00 UTC — earlier than 08:00 UTC.
        val notes = listOf(
            note("utc", "2026-10-12T08:00:00+00:00"),
            note("cest", "2026-10-12T09:00:00+02:00")
        )
        assertEquals(listOf("cest", "utc"), TourNotes.forPacking(notes, null).map { it.id })
    }

    @Test
    fun `excludes notes written in the current tour`() {
        val notes = listOf(
            note("old", "2026-10-10T10:00:00+00:00", writtenInTour = "tour-1"),
            note("mine", "2026-10-12T10:00:00+00:00", writtenInTour = "tour-2"),
            note("legacy", "2026-10-11T10:00:00+00:00", writtenInTour = null)
        )
        assertEquals(listOf("old", "legacy"), TourNotes.forPacking(notes, "tour-2").map { it.id })
        // No tour yet (packing step): nothing is excluded.
        assertEquals(listOf("old", "legacy", "mine"), TourNotes.forPacking(notes, "").map { it.id })
    }

    @Test
    fun `normalizes text for save`() {
        assertEquals("", TourNotes.normalizedForSave("   \n "))
        assertEquals("risers", TourNotes.normalizedForSave("  risers \n"))
        assertEquals(TourNotes.MAX_LENGTH, TourNotes.normalizedForSave("x".repeat(2500)).length)
    }

    @Test
    fun `decodes a row with the machine join`() {
        val json = Json { ignoreUnknownKeys = true }
        val row = json.decodeFromString<MachineTourNote>(
            """
            {"id":"n1","machine_id":"m1","note":"Dosen-Erhöhung","written_in_tour":"t1",
             "created_at":"2026-10-12T10:00:00+00:00","done_at":null,
             "vendingMachine":{"name":"Foyer"}}
            """.trimIndent()
        )
        assertEquals("Foyer", row.machineName)
        assertEquals("t1", row.writtenInTour)
        assertNull(row.doneAt)

        val bare = json.decodeFromString<MachineTourNote>(
            """{"id":"n2","machine_id":"m2","note":"x","created_at":"2026-10-12T10:00:00+00:00"}"""
        )
        assertNull(bare.machineName)
        assertNull(bare.writtenInTour)
    }
}
