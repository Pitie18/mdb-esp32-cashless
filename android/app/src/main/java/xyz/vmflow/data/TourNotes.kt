package xyz.vmflow.data

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `vendingMachine(name)` join of a [MachineTourNote]. */
@Serializable
data class TourNoteMachine(val name: String? = null)

/**
 * One "note for the next tour" (`machine_tour_notes`,
 * `Docker/supabase/migrations/20261013200000_machine_tour_notes.sql`): free
 * text a refiller wrote at a machine, shown at the top of every tour's
 * packing step until it is ticked off. Open while [doneAt] is `null`.
 */
@Serializable
data class MachineTourNote(
    val id: String,
    @SerialName("machine_id") val machineId: String,
    val note: String,
    @SerialName("written_in_tour") val writtenInTour: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("vendingMachine") val machine: TourNoteMachine? = null
) {
    val machineName: String? get() = machine?.name
}

/** Pure helpers for the tour notes — unit-tested in `TourNotesTest`. */
object TourNotes {
    /** Server-side limit of `machine_tour_notes.note`. */
    const val MAX_LENGTH = 2000

    /**
     * The notes the packing step shows: open ones only, oldest first, never
     * the ones written in [currentTourId] (when one is set) — a note belongs
     * to the *next* tour, not the one it was written in.
     */
    fun forPacking(notes: List<MachineTourNote>, currentTourId: String?): List<MachineTourNote> {
        val tour = currentTourId?.trim().orEmpty()
        return notes
            .filter { it.doneAt == null }
            .filter { tour.isEmpty() || it.writtenInTour != tour }
            .sortedWith(compareBy<MachineTourNote> { sortKey(it.createdAt) }.thenBy { it.id })
    }

    /** Text to send to `save_machine_tour_note`: capped at [MAX_LENGTH]; blank deletes the note server side. */
    fun normalizedForSave(text: String): String = text.trim().take(MAX_LENGTH)

    /** Epoch millis of a timestamptz string; unparseable dates sort first. */
    private fun sortKey(createdAt: String): Long =
        runCatching { Instant.parse(createdAt).toEpochMilliseconds() }.getOrDefault(Long.MIN_VALUE)
}
