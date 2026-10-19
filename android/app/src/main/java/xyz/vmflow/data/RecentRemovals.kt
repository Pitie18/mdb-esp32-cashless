package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A product taken out of a machine within the company's cool-down
 * (`companies.product_removal_cooldown_days`, default 30) and not back in
 * it since. The replacement picker marks it and sorts it last, so a slow
 * seller with warehouse stock left does not walk straight back into the
 * machine it was just taken out of. Server-side:
 * `get_recent_product_removals` (migration `20261019120000`); iOS reads the
 * same RPC (`RecentRemoval` in `SlotChange.swift`).
 *
 * @property offeredSince start of the offering that ended; `null` only for
 *   an offering that predates the offering history.
 * @property daysOffered whole days between [offeredSince] and [removedAt],
 *   at least 1.
 * @property unitsSold sales of the product in this machine during that
 *   offering.
 * @property estimated removed before removals were recorded: [removedAt] is
 *   the slot change that took it out, else its last sale.
 */
@Serializable
data class RecentRemoval(
    @SerialName("machine_id") val machineId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("removed_at") val removedAt: Instant,
    @SerialName("offered_since") val offeredSince: Instant? = null,
    @SerialName("days_offered") val daysOffered: Int? = null,
    @SerialName("units_sold") val unitsSold: Int = 0,
    val estimated: Boolean = false
)

@Serializable
private data class RecentRemovalsResponse(
    @SerialName("cooldown_days") val cooldownDays: Int = 0,
    val removals: List<RecentRemoval> = emptyList()
)

object RecentRemovalRepository {
    private val postgrest get() = SupabaseService.client.postgrest

    /**
     * `machineId -> productId -> removal` for [machineIds]. Empty when the
     * cool-down is switched off. Callers treat a failure as "nothing
     * removed": the hint is advice, never a reason to block the review.
     */
    suspend fun fetch(machineIds: List<String>): Result<Map<String, Map<String, RecentRemoval>>> {
        if (machineIds.isEmpty()) return Result.success(emptyMap())
        return try {
            val params = buildJsonObject {
                put("p_machine_ids", JsonArray(machineIds.map { JsonPrimitive(it) }))
            }
            val response = postgrest.rpc("get_recent_product_removals", params)
                .decodeAs<RecentRemovalsResponse>()
            Result.success(byMachine(response.removals))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Groups the RPC's rows; the latest removal wins should a product repeat. */
    fun byMachine(removals: List<RecentRemoval>): Map<String, Map<String, RecentRemoval>> =
        removals.groupBy { it.machineId }.mapValues { (_, rows) ->
            rows.groupBy { it.productId }.mapValues { (_, same) -> same.maxBy { it.removedAt } }
        }
}
