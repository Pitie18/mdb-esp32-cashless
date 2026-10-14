package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Notes for the next refill tour ("Notiz für nächste Tour"). Reads go to the
 * `machine_tour_notes` table (RLS: company members), writes through the
 * SECURITY DEFINER RPCs of
 * `Docker/supabase/migrations/20261013200000_machine_tour_notes.sql`, which
 * web and iOS call the same way.
 */
object TourNoteRepository {
    private val postgrest get() = SupabaseService.client.postgrest

    private const val SELECT =
        "id, machine_id, note, written_in_tour, created_at, done_at, vendingMachine(name)"

    /** Every open note of the company, oldest first. */
    suspend fun fetchOpenNotes(): Result<List<MachineTourNote>> {
        return try {
            val rows = postgrest.from("machine_tour_notes")
                .select(Columns.raw(SELECT)) {
                    filter { filter("done_at", FilterOperator.IS, "null") }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<MachineTourNote>()
            Result.success(rows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** This tour's open note for [machineId], or `null` when it has none. */
    suspend fun fetchTourNote(machineId: String, tourId: String): Result<MachineTourNote?> {
        return try {
            val row = postgrest.from("machine_tour_notes")
                .select(Columns.raw(SELECT)) {
                    filter {
                        eq("machine_id", machineId)
                        eq("written_in_tour", tourId)
                        filter("done_at", FilterOperator.IS, "null")
                    }
                    limit(1)
                }
                .decodeList<MachineTourNote>()
                .firstOrNull()
            Result.success(row)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes (or overwrites) the machine's note of [tourId]; a blank [note]
     * deletes it. Returns the note id, `null` when deleted.
     */
    suspend fun saveNote(machineId: String, tourId: String, note: String): Result<String?> {
        return try {
            val params = buildJsonObject {
                put("p_machine_id", machineId)
                put("p_tour_id", tourId)
                put("p_note", note)
            }
            val body = postgrest.rpc("save_machine_tour_note", params).data.trim()
            val id = if (body.isEmpty() || body == "null") null else Json.decodeFromString<String>(body)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Ticks notes off ([tourId] empty outside a tour). Idempotent on the server. */
    suspend fun completeNotes(noteIds: List<String>, tourId: String): Result<Unit> {
        return try {
            if (noteIds.isEmpty()) return Result.success(Unit)
            val params = buildJsonObject {
                put("p_note_ids", JsonArray(noteIds.map { JsonPrimitive(it) }))
                put("p_tour_id", tourId)
            }
            postgrest.rpc("complete_machine_tour_notes", params)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
