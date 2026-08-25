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
import xyz.vmflow.models.ProductCategory
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

/** Decode target for the `select("id")` returning-representation of [RefillRepository.applyReplacement]. */
@Serializable
private data class UpdatedTrayIdRow(val id: String)

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
     * Fetches every `product_category` row, alphabetically — mirrors iOS
     * `loadData`'s category fetch (`RefillWizardViewModel.swift:1272-1288`,
     * itself mirroring `ProductsViewModel.loadCategories()`). Feeds the
     * replacement-product picker's grouping UI (a later task). Explicit
     * column list rather than `*` so the decoder stays safe against future
     * schema additions to `product_category`; see [ProductCategory]'s doc
     * comment for the migration those columns were verified against.
     */
    suspend fun fetchProductCategories(): Result<List<ProductCategory>> {
        return try {
            val categories = postgrest.from("product_category")
                .select(Columns.raw("id, name, company")) {
                    order("name", Order.ASCENDING)
                }
                .decodeList<ProductCategory>()
            Result.success(categories)
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
     *
     * Non-blocking is not the same as invisible, though: the returned list
     * names the deductions that did **not** go through (empty = every one was
     * charged), so the caller can tell somebody. `deduct_warehouse_stock_fifo`
     * *raises* on insufficient stock
     * (`Docker/supabase/migrations/20260305000000_warehouse_inventory.sql:249-250`)
     * and rolls that product's whole deduction back, so a stock level that
     * moved between packing and tour start silently produces goods that left
     * the warehouse physically without the ledger recording it — ledger drift
     * in the opposite direction from the over-charging bug this phase exists
     * to fix. Swallowing it was the old behaviour and it is exactly what made
     * that drift unmeasurable.
     */
    suspend fun deductForTour(
        warehouseId: String,
        tourId: String,
        deductions: List<PackedDeduction>
    ): Result<List<PackedDeduction>> {
        return try {
            if (deductions.isEmpty()) return Result.success(emptyList())

            // Auth resolved once, before the first write — an expired
            // session degrades to an unattributed deduction (matching
            // iOS's `try?`) rather than silently attributing a later
            // deduction to whichever session happened to still be valid.
            val user = auth.currentUserOrNull()
            val userId = user?.id
            val userEmail = user?.email

            val failed = mutableListOf<PackedDeduction>()
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
                    // Non-blocking: the tour continues even if one deduction
                    // fails, matching iOS — but it is *recorded* rather than
                    // dropped, so the caller can surface it (see the header).
                    //
                    // Still no log call in here: `android.util.Log` is
                    // unmocked on the JVM test path, so logging *inside* this
                    // catch would throw and escape to the outer catch —
                    // turning "one deduction may fail, the tour continues"
                    // into a whole-call failure under test. Collecting the
                    // failure into the return value is the seam that replaces
                    // the log.
                    failed.add(deduction)
                }
            }
            Result.success(failed)
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
     *
     * @param companyId pre-resolved `company_id`. Optional: without it this
     *   function resolves it itself through the `get-my-organization` edge
     *   function, which is one edge-function round trip **per row**. Over an
     *   N-machine tour that is N calls, each one *after* the tray write it
     *   documents has already committed — so an edge-runtime hiccup while
     *   PostgREST is healthy drops audit rows silently. Tour callers
     *   therefore resolve it once at tour start and pass it down
     *   (`RefillViewModel`); every other caller keeps today's behaviour by
     *   omitting it.
     */
    suspend fun writeTourActivity(
        action: String,
        machineId: String?,
        machineName: String?,
        tourId: String,
        warehouseId: String?,
        extra: Map<String, JsonElement>,
        companyId: String? = null
    ): Result<Unit> {
        return try {
            val user = auth.currentUserOrNull()
                ?: throw IllegalStateException("No authenticated user")
            val resolvedCompanyId = companyId
                ?: AuthRepository.fetchOrganization().getOrThrow().organization?.id
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
                    put("company_id", resolvedCompanyId)
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

    /**
     * Reassigns a review-step slot's product and resets its stock to 0 in
     * one update — mirrors iOS `applyReplacementsAndContinue`
     * (`RefillWizardViewModel.swift:1569-1602`) and the identical write in
     * [MachineAnalysisRepository.updateTrayProduct]. The zero is not
     * cosmetic: the old product's stock is meaningless for the new one, and
     * without it the slot shows no deficit in the pack step, so the driver
     * never refills it.
     *
     * Blocking, unlike [logReviewSwap]: a later task keeps the driver in the
     * review step when this fails, and can only do that if this [Result]
     * faithfully reports failure.
     *
     * Asks for the updated row back (`select("id")`, the same
     * returning-representation idiom as [WarehouseRepository.bookIntake]) and
     * fails on an empty result. Without that, a tray deleted between loading
     * the review and applying it — or one hidden by RLS — answers 204 and
     * would report success while changing nothing, which is the one failure
     * this [Result] exists to surface.
     */
    suspend fun applyReplacement(trayId: String, productId: String): Result<Unit> {
        return try {
            val updated = postgrest.from("machine_trays")
                .update({
                    set("product_id", productId)
                    set("current_stock", 0)
                }) {
                    filter { eq("id", trayId) }
                    select(Columns.raw("id"))
                }
                .decodeList<UpdatedTrayIdRow>()
            if (updated.isEmpty()) {
                Result.failure(IllegalStateException("Tray $trayId no longer exists"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes one `activity_log` row for a review-step product replacement,
     * action `product_swapped` with `source = "refill_review"` in the
     * metadata — NOT a bespoke action.
     *
     * The plan originally called for a new `refill_review_swap` action so a
     * review swap would stay distinguishable from the analysis screen's.
     * Looking one level further showed that a new action renders NOWHERE:
     * `MachineAnalysisRepository.logProductSwap` writes `product_swapped`
     * with `source = "analysis_swap"`, and the shared PWA renderer
     * (`management-frontend/app/lib/activityDescriptor.ts`) keys its cases
     * off the ACTION and merely labels the source. Reusing the action gets
     * these rows rendered on the web today, and keeps the origin
     * distinguishable **in the data** — `metadata->>'source'` separates a
     * review swap from an analysis swap.
     *
     * To be precise about what that does NOT buy, since an earlier version of
     * this comment overclaimed it: the PWA's `product_swapped` case never
     * calls its `pushSource()` helper, so neither source value is shown in the
     * visible feed today — `analysis_swap` was already invisible there too.
     * The two are queryable apart, not yet readable apart.
     *
     * iOS writes no audit row for this operation. Android's own analysis
     * screen writes one for the identical `machine_trays` update
     * ([MachineAnalysisRepository.logProductSwap]), and a product swap with
     * no audit trail is a gap in a multi-user operation — so this writes one
     * too. This app's own feed does not render `product_swapped` at all;
     * `ActivityFeedBuilder` drops actions it does not know (`mapNotNull`), so
     * an unrendered row here breaks nothing.
     *
     * `activity_log.metadata` is a typed cross-client contract — the keys
     * below reuse the names the existing writers already use for the same
     * facts rather than inventing new ones: `machine_id` / `item_number` /
     * `old_product_id` / `old_product_name` / `new_product_id` come from
     * [MachineAnalysisRepository.logProductSwap]
     * (`MachineAnalysisRepository.kt:198-224`); `machine_name` / `tour_id` /
     * `_user_email` / `_user_display` come from [writeTourActivity] above.
     * `new_product_name` has no existing writer, but it is already an
     * established contract key: the PWA's shared renderer pairs it with
     * `old_product_name` for `product_swapped`
     * (`management-frontend/app/lib/activityDescriptor.ts:357`, exercised by
     * `activityDescriptor.test.ts:240`).
     *
     * Auth is resolved before the insert — the only write in this function.
     * Non-critical like [writeTourActivity]: any failure, including an
     * expired session, is swallowed into [Result.success] — the
     * [applyReplacement] write this documents has already committed by the
     * time a caller invokes this, so a lost audit row must never surface as
     * a failure of the replacement itself.
     */
    suspend fun logReviewSwap(
        machineId: String,
        machineName: String,
        trayId: String,
        slotNumber: Int,
        oldProductId: String?,
        oldProductName: String?,
        newProductId: String,
        newProductName: String,
        tourId: String?,
        companyId: String?
    ): Result<Unit> {
        return try {
            val user = auth.currentUserOrNull()
                ?: throw IllegalStateException("No authenticated user")
            val resolvedCompanyId = companyId
                ?: AuthRepository.fetchOrganization().getOrThrow().organization?.id
                ?: throw IllegalStateException("Could not determine company")

            val firstName = user.userMetadata?.get("first_name").asNonNullString()
            val lastName = user.userMetadata?.get("last_name").asNonNullString()
            val fullName = listOfNotNull(firstName, lastName).joinToString(" ").trim()
            val userDisplay = fullName.ifEmpty { user.email }

            val metadata = buildJsonObject {
                put("machine_id", machineId)
                put("machine_name", machineName)
                put("item_number", slotNumber)
                oldProductId?.let { put("old_product_id", it) }
                oldProductName?.let { put("old_product_name", it) }
                put("new_product_id", newProductId)
                put("new_product_name", newProductName)
                tourId?.let { put("tour_id", it) }
                // Same action as the analysis screen's swap, distinguished by
                // `source` — see the action choice documented on this function.
                put("source", "refill_review")
                put("_user_email", user.email)
                put("_user_display", userDisplay)
            }

            postgrest.from("activity_log").insert(
                buildJsonObject {
                    put("company_id", resolvedCompanyId)
                    put("user_id", user.id)
                    put("entity_type", "stock")
                    put("entity_id", trayId)
                    put("action", "product_swapped")
                    put("metadata", metadata)
                }
            )
            Result.success(Unit)
        } catch (_: Exception) {
            // Non-critical, same rationale as `writeTourActivity` above: a
            // lost audit row must never fail a replacement that already
            // committed, and `android.util.Log` is unmocked on the JVM test
            // path so logging inside this catch is not an option.
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
