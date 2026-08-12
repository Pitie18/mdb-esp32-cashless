package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SaleWithMachine
import xyz.vmflow.models.Tray
import xyz.vmflow.models.VendingMachineWithEmbedded

/**
 * Per-machine (not per-tray) stock health, mirrors `loadMachineStats()` in
 * `ios/VMflow/ViewModels/DashboardViewModel.swift`. A machine is counted at
 * most once, in the worse of its two buckets.
 */
data class MachineStockHealth(
    val total: Int,
    val online: Int,
    val criticalMachines: Int,
    val lowMachines: Int,
)

/**
 * Seam between [DashboardViewModel][xyz.vmflow.ui.dashboard.DashboardViewModel]
 * and the network so tests can substitute a fake — no Robolectric, no fake
 * Postgrest server, no new test dependency.
 */
interface DashboardDataSource {
    suspend fun fetchSalesSince(since: Instant): List<Sale>
    suspend fun fetchMachineStockHealth(): MachineStockHealth
    suspend fun fetchRecentSaleItems(windowStart: Instant): Pair<List<SaleWithMachine>, Int>
    suspend fun fetchActivityRows(windowStart: Instant): List<ActivityLogRow>
    suspend fun fetchIntakeRows(windowStart: Instant): List<IntakeTransactionRow>
    suspend fun resolveUserNames(ids: List<String>): Map<String, String>
    suspend fun fetchNewDealsCount(): Int
}

/** `users` row shape used only to resolve intake attribution display names. */
@Serializable
private data class UserRow(
    val id: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val email: String? = null,
)

/**
 * Dashboard queries in one place. Mirrors the fetches in
 * `ios/VMflow/ViewModels/DashboardViewModel.swift` (Z. 82-117, 277-445) —
 * both clients must query and degrade identically or the same backend shows
 * a different dashboard on each phone.
 */
object DashboardRepository : DashboardDataSource {
    private val postgrest get() = SupabaseService.client.postgrest

    private const val SALE_COLUMNS =
        "id, created_at, item_price, item_number, machine_id, embedded_id, channel"
    private const val SALE_WITH_PRODUCT_COLUMNS =
        "id, created_at, item_price, item_number, machine_id, embedded_id, channel, product_id, products(name, image_path)"
    private const val MACHINE_COLUMNS =
        "id, name, location_lat, location_lon, country_code, embeddeds(id, status, status_at, subdomain, mac_address, firmware_version)"
    private const val TRAY_COLUMNS =
        "id, machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, discontinued, sellprice)"

    override suspend fun fetchSalesSince(since: Instant): List<Sale> =
        postgrest.from("sales")
            .select(Columns.raw(SALE_COLUMNS)) {
                filter { gte("created_at", since.toString()) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Sale>()

    /**
     * Stock-semantics reminder (spec, not obvious from the column names):
     * a machine counts as CRITICAL if it has at least one empty tray, else
     * LOW if it has at least one tray at/under `min_stock` — these are
     * MACHINE counts, not tray counts, and a machine is never counted in
     * both buckets.
     */
    override suspend fun fetchMachineStockHealth(): MachineStockHealth {
        val machines = postgrest.from("vendingMachine")
            .select(Columns.raw(MACHINE_COLUMNS))
            .decodeList<VendingMachineWithEmbedded>()

        val trays = postgrest.from("machine_trays")
            .select(Columns.raw(TRAY_COLUMNS))
            .decodeList<Tray>()

        val traysByMachine = trays.groupBy { it.machineId }

        var critical = 0
        var low = 0
        for (machine in machines) {
            val machineTrays = traysByMachine[machine.id].orEmpty()
            val hasEmpty = machineTrays.any { it.currentStock == 0 }
            val hasBelowMinStock = machineTrays.any { tray ->
                val minStock = tray.minStock ?: 0
                minStock > 0 && tray.currentStock <= minStock
            }
            when {
                hasEmpty -> critical++
                hasBelowMinStock -> low++
            }
        }

        return MachineStockHealth(
            total = machines.size,
            online = machines.count { it.isOnline },
            criticalMachines = critical,
            lowMachines = low,
        )
    }

    /**
     * Sales + machine names + product fallback via trays, unchanged pipeline
     * from iOS. Returns the display items plus the RAW row count — the
     * infinite-scroll exhaustion check in the ViewModel compares raw rows,
     * not merged items, because new transactions folding into an existing
     * intake group would otherwise leave the merged count unchanged and
     * falsely signal "no more history".
     */
    override suspend fun fetchRecentSaleItems(windowStart: Instant): Pair<List<SaleWithMachine>, Int> {
        val sales = postgrest.from("sales")
            .select(Columns.raw(SALE_WITH_PRODUCT_COLUMNS)) {
                filter { gte("created_at", windowStart.toString()) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Sale>()

        val machineIds = sales.mapNotNull { it.machineId }.toSet()
        val machineNames = mutableMapOf<String, String>()
        if (machineIds.isNotEmpty()) {
            val machines = postgrest.from("vendingMachine")
                .select(Columns.raw("id, name, location_lat, location_lon, country_code")) {
                    filter { isIn("id", machineIds.toList()) }
                }
                .decodeList<VendingMachineWithEmbedded>()
            for (machine in machines) machineNames[machine.id] = machine.displayName
        }

        // Fallback: tray -> product lookup only for old sales without a
        // snapshotted product_id.
        val salesWithoutProduct = sales.filter { it.productId == null && it.machineId != null }
        val trayProductLookup = mutableMapOf<String, Pair<String?, String?>>()
        if (salesWithoutProduct.isNotEmpty()) {
            val fallbackMachineIds = salesWithoutProduct.mapNotNull { it.machineId }.toSet()
            val trays = postgrest.from("machine_trays")
                .select(Columns.raw(TRAY_COLUMNS)) {
                    filter { isIn("machine_id", fallbackMachineIds.toList()) }
                }
                .decodeList<Tray>()
            for (tray in trays) {
                val key = "${tray.machineId}_${tray.itemNumber}"
                trayProductLookup[key] = tray.products?.name to tray.products?.imagePath
            }
        }

        val items = sales.map { sale ->
            val machineName = sale.machineId?.let { machineNames[it] }

            // Prefer the snapshotted product from the FK join, fall back to
            // the tray lookup for pre-product_id sales.
            var productName = sale.products?.name
            var productImagePath = sale.products?.imagePath
            if (productName == null && sale.machineId != null && sale.itemNumber != null) {
                val fallback = trayProductLookup["${sale.machineId}_${sale.itemNumber}"]
                productName = fallback?.first
                productImagePath = fallback?.second
            }

            SaleWithMachine(
                sale = sale,
                machineName = machineName,
                productName = productName,
                productImagePath = productImagePath,
            )
        }
        return items to sales.size
    }

    /** Refill + tour-start + cash-book rows. RLS scopes to the user's company. */
    override suspend fun fetchActivityRows(windowStart: Instant): List<ActivityLogRow> =
        postgrest.from("activity_log")
            .select(Columns.raw("id, created_at, action, metadata")) {
                filter {
                    isIn("action", listOf("stock_refill_tour", "tour_started", "cash_book_entry_created"))
                    gte("created_at", windowStart.toString())
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ActivityLogRow>()

    /**
     * Incoming warehouse transactions with product/warehouse names joined.
     * Both type strings are read: the PWA books intakes as 'incoming', the
     * iOS/Android apps as 'intake' — a pre-existing cross-client divergence.
     * Querying only one silently drops half the feed.
     */
    override suspend fun fetchIntakeRows(windowStart: Instant): List<IntakeTransactionRow> =
        postgrest.from("warehouse_transactions")
            .select(Columns.raw("id, created_at, warehouse_id, user_id, quantity_change, products(name), warehouses(name)")) {
                filter {
                    isIn("transaction_type", listOf("incoming", "intake"))
                    gte("created_at", windowStart.toString())
                }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<IntakeTransactionRow>()

    /**
     * Resolve display names for intake attribution. The `users` FK points to
     * `auth.users`, so PostgREST can't embed it — same lookup pattern as the
     * iOS ProductDetailSheet. Degrades to an empty map on any failure
     * (including a missing table under restrictive RLS): a missing name must
     * never take down the feed.
     */
    override suspend fun resolveUserNames(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            val rows = postgrest.from("users")
                .select(Columns.raw("id, first_name, last_name, email")) {
                    filter { isIn("id", ids) }
                }
                .decodeList<UserRow>()
            rows.associate { user ->
                val full = listOfNotNull(user.firstName, user.lastName)
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                val display = full.ifEmpty { user.email ?: user.id.take(8) }
                user.id to display
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * New/unhandled deals count for the dashboard banner. Swallows failure
     * (including a backend without the `get_new_deals_count` RPC) so a
     * missing extension point never breaks the dashboard.
     */
    override suspend fun fetchNewDealsCount(): Int =
        try {
            postgrest.rpc("get_new_deals_count").decodeAs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
}
