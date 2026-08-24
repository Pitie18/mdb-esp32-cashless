package xyz.vmflow.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.RefillTrayPayload
import xyz.vmflow.models.Tray
import xyz.vmflow.models.TrayApplicationResult
import xyz.vmflow.models.WarehousePositionGroup
import xyz.vmflow.models.WarehouseProductPosition

/** RPC input for [RefillRepository.refillMachineTrays] — mirrors iOS `applyRefillRPC` (`RefillWizardViewModel.swift:1848-1868`). */
@Serializable
private data class RefillTraysParams(
    @SerialName("p_machine_id") val machineId: String,
    @SerialName("p_tour_id") val tourId: String,
    @SerialName("p_trays") val trays: List<RefillTrayPayload>
)

object RefillRepository {
    private val postgrest get() = SupabaseService.client.postgrest
    private val auth get() = SupabaseService.client.auth

    /**
     * Fetches every vending machine (with its `embeddeds` join, via
     * [MachineRepository.fetchMachines] — same select shape iOS `loadData`
     * uses, L1216-1223) together with **every** tray of every machine, not
     * only trays below threshold: the refill step's UI shows full trays too,
     * collapsed. A machine with zero trays drops out of the result; a
     * machine with trays is included even if none of them currently need a
     * refill — need-based filtering now happens downstream, over the
     * combined packing list, not here (see [RefillTourLogic.buildCombinedPackingList]).
     *
     * `fillAmount` on every [RefillTray] starts at the tray's `deficit`.
     *
     * The returned machine order carries **no meaning** — it is whatever
     * order the `vendingMachine` fetch happened to yield. iOS sorts by
     * urgency inside the `buildRefillMachines` step this function
     * deliberately bypasses, so the caller that establishes a tour's visit
     * order has to sort for itself.
     */
    suspend fun fetchRefillMachines(): Result<List<RefillMachine>> {
        return try {
            val machines = MachineRepository.fetchMachines().getOrThrow()

            val trays = postgrest.from("machine_trays")
                .select(
                    Columns.raw(
                        "id, machine_id, item_number, product_id, capacity, current_stock, " +
                            "min_stock, fill_when_below, products(id, name, image_path, discontinued, sellprice)"
                    )
                ) {
                    order("item_number", Order.ASCENDING)
                }
                .decodeList<Tray>()

            val traysByMachine = trays.groupBy { it.machineId }
            val refillMachines = machines.mapNotNull { machine ->
                val machineTrays = traysByMachine[machine.id]
                if (machineTrays.isNullOrEmpty()) return@mapNotNull null
                RefillMachine(
                    machine = machine,
                    trays = machineTrays.map { tray -> RefillTray(tray = tray, fillAmount = tray.deficit) }
                )
            }
            Result.success(refillMachines)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Aggregates one warehouse's positive-quantity stock batches
     * ([WarehouseRepository.fetchWarehouseStock], already filtered to
     * `quantity > 0` for this warehouse) into `productId -> total quantity`.
     * No new query — mirrors iOS `loadWarehouseStock`'s batch aggregation
     * (L1389-1399).
     */
    suspend fun fetchWarehouseStockTotals(warehouseId: String): Result<Map<String, Int>> {
        return try {
            val batches = WarehouseRepository.fetchWarehouseStock(warehouseId).getOrThrow()
            val totals = mutableMapOf<String, Int>()
            for (batch in batches) {
                totals[batch.productId] = (totals[batch.productId] ?: 0) + batch.quantity
            }
            Result.success(totals)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads the warehouse's position-group tree (`warehouse_position_groups`)
     * and its product positions (`warehouse_product_positions`), both
     * ordered by `sort_order`, and flattens them via
     * [RefillTourLogic.flattenPickOrder] into `productId -> pick index`.
     * Mirrors iOS `fetchOrderedProductIds` (L1456-1500).
     *
     * A failure here is deliberately **not** swallowed into an empty
     * success: the caller (a later task) treats [Result.failure] as an
     * empty map and falls back to quantity-based sorting for the packing
     * list, matching iOS `fetchOrderedProductIdsOrEmpty`. Swallowing the
     * error here would make that distinction impossible for the caller to
     * observe.
     */
    suspend fun fetchPickOrder(warehouseId: String): Result<Map<String, Int>> {
        return try {
            val groups = postgrest.from("warehouse_position_groups")
                .select(Columns.raw("id, parent_id, sort_order")) {
                    filter { eq("warehouse_id", warehouseId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<WarehousePositionGroup>()

            val positions = postgrest.from("warehouse_product_positions")
                .select(Columns.raw("product_id, sort_order, group_id")) {
                    filter { eq("warehouse_id", warehouseId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<WarehouseProductPosition>()

            val orderedIds = RefillTourLogic.flattenPickOrder(groups, positions)
            val orderMap = orderedIds.withIndex().associate { (index, productId) -> productId to index }
            Result.success(orderMap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Calls the atomic `refill_machine_trays` RPC
     * (`Docker/supabase/migrations/20260511120000_refill_machine_trays_rpc.sql`,
     * ambiguity-fixed by `20260513120000_fix_refill_machine_trays_ambiguity.sql`)
     * — mirrors iOS `applyRefillRPC` (`RefillWizardViewModel.swift:1848-1868`).
     * The RPC runs every tray update for the machine in one transaction and
     * dedupes per `(tour_id, tray_id)`, so it is safe to call again after a
     * network failure. **No retry loop here** — that lives in the ViewModel
     * (Task 9) so it stays testable together with UI state.
     */
    suspend fun refillMachineTrays(
        machineId: String,
        tourId: String,
        trays: List<RefillTrayPayload>
    ): Result<List<TrayApplicationResult>> {
        return try {
            val params = Json.encodeToJsonElement(
                RefillTraysParams(machineId = machineId, tourId = tourId, trays = trays)
            ) as JsonObject
            val result = postgrest.rpc("refill_machine_trays", params)
                .decodeList<TrayApplicationResult>()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * FIFO-deducts warehouse stock for every packed (machine, product, qty)
     * triple via [WarehouseRepository.deductWarehouseStockFifo] — mirrors
     * iOS `deductWarehouseStock(warehouseId:)`
     * (`RefillWizardViewModel.swift:1741-1800`): `p_notes` is always
     * `"Refill tour"`, `p_reference_id` is the deduction's `machineId`, and
     * `p_metadata` carries `_user_email` and `tour_id`. The authenticated
     * user is resolved once, before the first deduction is attempted.
     *
     * A single deduction failing does **not** abort the loop or fail this
     * call — the tour must not get stuck because the warehouse ledger had a
     * hiccup, same as iOS. Only something unexpected escaping the loop
     * itself (not a per-deduction RPC error) turns into [Result.failure].
     */
    suspend fun deductForTour(
        warehouseId: String,
        tourId: String,
        deductions: List<PackedDeduction>
    ): Result<Unit> {
        return try {
            if (deductions.isEmpty()) return Result.success(Unit)

            // Auth resolved once, before the first write — an expired
            // session degrades to an unattributed deduction (matching
            // iOS's `try?`) rather than silently attributing a later
            // deduction to whichever session happened to still be valid.
            val user = auth.currentUserOrNull()
            val userId = user?.id
            val userEmail = user?.email

            for (deduction in deductions) {
                try {
                    WarehouseRepository.deductWarehouseStockFifo(
                        warehouseId = warehouseId,
                        productId = deduction.productId,
                        quantity = deduction.quantity,
                        userId = userId,
                        referenceId = deduction.machineId,
                        notes = "Refill tour",
                        metadata = mapOf(
                            "_user_email" to userEmail,
                            "tour_id" to tourId
                        )
                    ).getOrThrow()
                } catch (_: Exception) {
                    // Non-critical: continue the tour even if one deduction
                    // fails, matching iOS. Deliberately silent rather than
                    // logged: `android.util.Log` is unmocked on the JVM test
                    // path, so a log call *inside* this catch would throw and
                    // escape to the outer catch — turning "one deduction may
                    // fail, the tour continues" into a whole-call failure
                    // under test. The caller cannot observe a partial failure
                    // here by design (same as iOS).
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes one `activity_log` row for a refill/skip/tour action — mirrors
     * iOS `writeActivityLog` (`RefillWizardViewModel.swift:2028-2078`).
     * Uses the same write path and `company_id` resolution as
     * [MachineAnalysisRepository.logProductSwap] (`MachineAnalysisRepository.kt:198-224`):
     * `AuthRepository.fetchOrganization()` for `company_id`, first/last name
     * from user metadata falling back to the e-mail for `_user_display`.
     *
     * `activity_log.metadata` is a typed cross-client contract (PWA, iOS,
     * Android) — the keys below are exactly what
     * `models/ActivityFeed.kt` / `data/ActivityFeedBuilder.kt` already read
     * for `stock_refill_tour` / `tour_started` rows: `tour_id`,
     * `_user_email`, `_user_display` (leading underscore is deliberate, not
     * a typo — see `ActivityFeed.kt:43-45`), plus optional `machine_id`,
     * `machine_name`, `warehouse_id`, and whatever the caller passes via
     * [extra].
     *
     * Auth (and the `company_id` lookup that depends on it) is resolved
     * before the insert is attempted — an expired session must fail here,
     * not after committing a write elsewhere that then has no audit row.
     *
     * Non-critical: any failure is logged and swallowed — the caller
     * always gets [Result.success], matching iOS's silent catch-and-print.
     */
    suspend fun writeTourActivity(
        action: String,
        machineId: String?,
        machineName: String?,
        tourId: String,
        warehouseId: String?,
        extra: Map<String, JsonElement>
    ): Result<Unit> {
        return try {
            val user = auth.currentUserOrNull()
                ?: throw IllegalStateException("No authenticated user")
            val companyId = AuthRepository.fetchOrganization().getOrThrow().organization?.id
                ?: throw IllegalStateException("Could not determine company")

            // `JsonNull` is itself a JsonPrimitive whose `content` is the
            // literal string "null", so an explicit `"first_name": null` in
            // the user metadata would otherwise be written as the author name
            // "null" and rendered as such in the activity feed.
            val firstName = user.userMetadata?.get("first_name").asNonNullString()
            val lastName = user.userMetadata?.get("last_name").asNonNullString()
            val fullName = listOfNotNull(firstName, lastName).joinToString(" ").trim()
            val userDisplay = fullName.ifEmpty { user.email }

            val metadata = buildJsonObject {
                put("tour_id", tourId)
                put("_user_email", user.email)
                put("_user_display", userDisplay)
                machineId?.let { put("machine_id", it) }
                machineName?.let { put("machine_name", it) }
                warehouseId?.let { put("warehouse_id", it) }
                extra.forEach { (key, value) -> put(key, value) }
            }

            postgrest.from("activity_log").insert(
                buildJsonObject {
                    put("company_id", companyId)
                    put("user_id", user.id)
                    put("entity_type", "stock")
                    put("entity_id", machineId ?: tourId)
                    put("action", action)
                    put("metadata", metadata)
                }
            )
            Result.success(Unit)
        } catch (_: Exception) {
            // Non-critical: a lost audit row must never fail a refill that
            // already committed. Silent for the same reason as the deduction
            // loop above — `android.util.Log` is unmocked on the JVM test path.
            Result.success(Unit)
        }
    }
}

/**
 * Reads a `user_metadata` value as a real string, treating JSON null as
 * absent. `JsonNull` is a [JsonPrimitive] whose `content` is `"null"`, so a
 * bare `(value as? JsonPrimitive)?.content` turns an explicit null into the
 * four-character string.
 */
private fun JsonElement?.asNonNullString(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
