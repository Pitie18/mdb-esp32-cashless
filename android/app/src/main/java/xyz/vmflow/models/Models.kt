package xyz.vmflow.models

import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.vmflow.data.ExpirationStatus

@Serializable
data class Organization(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class OrganizationResponse(
    val organization: Organization? = null,
    val role: String? = null
)

@Serializable
data class Embedded(
    val id: String,
    val status: String? = null,
    @SerialName("status_at") val statusAt: String? = null,
    val subdomain: Int? = null,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("firmware_version") val firmwareVersion: String? = null,
    /** Live MDB status snapshot published by the firmware. Null until the device has reported at least once. */
    @SerialName("mdb_diagnostics") val mdbDiagnostics: MdbDiagnostics? = null,
    @SerialName("last_restart_reason") val lastRestartReason: String? = null,
    @SerialName("last_restart_at") val lastRestartAt: String? = null,
    /** Timestamp the device last transitioned to "online" — start of the current uptime run, distinct from [statusAt] (last status write of any kind). */
    @SerialName("online_since") val onlineSince: String? = null
) {
    val isOnline: Boolean
        get() {
            if (status == null || statusAt == null) return false
            if (status != "online") return false
            return try {
                val statusTime = kotlinx.datetime.Instant.parse(statusAt)
                val now = kotlinx.datetime.Clock.System.now()
                val diff = now - statusTime
                diff.inWholeMinutes < 5
            } catch (_: Exception) {
                false
            }
        }
}

/**
 * Live MDB status snapshot, published by the firmware into
 * `embeddeds.mdb_diagnostics` (jsonb). Keys are mostly camelCase because this
 * side is authored by the JS/TS mqtt-webhook ingest pipeline, not a Postgres
 * column — `updated_at` is the one snake_case exception.
 */
@Serializable
data class MdbDiagnostics(
    val state: String? = null,
    val addr: String? = null,
    val vmcLevel: Int? = null,
    val polls: Int? = null,
    val chkErr: Int? = null,
    val lastCmd: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/** One ESP32 reboot event. Maps to the `device_restarts` table. */
@Serializable
data class DeviceRestart(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val reason: String,
    @SerialName("uptime_sec") val uptimeSec: Int? = null,
    @SerialName("firmware_version") val firmwareVersion: String? = null,
    @SerialName("hw_reason") val hwReason: String? = null
)

/** One MDB state transition. Maps to the `mdb_log` table. */
@Serializable
data class MdbLogEntry(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val state: String,
    @SerialName("prev_state") val prevState: String? = null,
    val addr: String? = null,
    val polls: Int? = null,
    @SerialName("chk_err") val chkErr: Int? = null,
    @SerialName("last_cmd") val lastCmd: String? = null
)

@Serializable
data class VendingMachine(
    val id: String,
    val name: String? = null,
    @SerialName("location_lat") val locationLat: Double? = null,
    @SerialName("location_lon") val locationLon: Double? = null,
    val embedded: String? = null,
    @SerialName("country_code") val countryCode: String? = null
)

@Serializable
data class VendingMachineWithEmbedded(
    val id: String,
    val name: String? = null,
    @SerialName("location_lat") val locationLat: Double? = null,
    @SerialName("location_lon") val locationLon: Double? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("address_street") val addressStreet: String? = null,
    @SerialName("address_house_number") val addressHouseNumber: String? = null,
    @SerialName("address_postal_code") val addressPostalCode: String? = null,
    @SerialName("address_city") val addressCity: String? = null,
    @SerialName("formatted_address") val formattedAddress: String? = null,
    @SerialName("nayax_machine_id") val nayaxMachineId: String? = null,
    @SerialName("public_listing") val publicListing: Boolean? = null,
    val embeddeds: Embedded? = null
) {
    val displayName: String get() = name ?: "Machine ${id.take(8)}"
    val isOnline: Boolean get() = embeddeds?.isOnline == true
}

@Serializable
data class Product(
    val id: String,
    val name: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    val discontinued: Boolean = false,
    val sellprice: Double? = null
)

@Serializable
data class Tray(
    val id: String,
    @SerialName("machine_id") val machineId: String,
    @SerialName("item_number") val itemNumber: Int,
    @SerialName("product_id") val productId: String? = null,
    val capacity: Int = 10,
    @SerialName("current_stock") val currentStock: Int = 0,
    @SerialName("min_stock") val minStock: Int? = null,
    @SerialName("fill_when_below") val fillWhenBelow: Int? = null,
    val products: Product? = null
) {
    val stockPercentage: Float
        get() = if (capacity > 0) currentStock.toFloat() / capacity.toFloat() else 0f

    val deficit: Int
        get() = (capacity - currentStock).coerceAtLeast(0)

    val isLow: Boolean
        get() = fillWhenBelow?.let { currentStock < it } ?: (stockPercentage < 0.25f)

    val isCritical: Boolean
        get() = currentStock == 0
}

@Serializable
data class TrayUpsert(
    val id: String? = null,
    @SerialName("machine_id") val machineId: String,
    @SerialName("item_number") val itemNumber: Int,
    @SerialName("product_id") val productId: String? = null,
    val capacity: Int = 10,
    @SerialName("current_stock") val currentStock: Int = 0,
    @SerialName("min_stock") val minStock: Int? = null,
    @SerialName("fill_when_below") val fillWhenBelow: Int? = null
)

/** Snapshotted product from a sale's FK join (select `products(name, image_path)`). */
@Serializable
data class SaleProduct(
    val name: String? = null,
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
data class Sale(
    val id: String,
    @SerialName("machine_id") val machineId: String? = null,
    @SerialName("embedded_id") val embeddedId: String? = null,
    @SerialName("item_price") val itemPrice: Double = 0.0,
    @SerialName("item_number") val itemNumber: Int? = null,
    val channel: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("product_id") val productId: String? = null,
    /** Present only when the select includes the `products(...)` FK join (dashboard recent-activity fetch). */
    val products: SaleProduct? = null
)

/** Matched real sale's `created_at`, joined via `matched:sales!matched_sale_id(created_at)`. */
@Serializable
data class MatchedSaleRef(
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * An auto-dropped brownout duplicate sale. Maps to the `suppressed_sales` table.
 * Read-only: the app never inserts/updates/deletes this table directly.
 */
@Serializable
data class SuppressedSale(
    val id: String,
    @SerialName("embedded_id") val embeddedId: String,
    @SerialName("item_number") val itemNumber: Int? = null,
    @SerialName("item_price") val itemPrice: Double? = null,
    val channel: String? = null,
    @SerialName("sale_seq") val saleSeq: Long? = null,
    @SerialName("device_created_at") val deviceCreatedAt: String? = null,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("matched_sale_id") val matchedSaleId: String? = null,
    val reason: String,
    @SerialName("product_id") val productId: String? = null,
    /** Snapshotted product from the FK join (`products(name, image_path)`). */
    val products: SaleProduct? = null,
    /** Matched real sale's `created_at` — used for the gap fragment in the row's reason text. */
    val matched: MatchedSaleRef? = null
)

@Serializable
data class Warehouse(
    val id: String,
    val name: String? = null,
    val address: String? = null,
    val notes: String? = null,
    @SerialName("company_id") val companyId: String
)

@Serializable
data class WarehouseStockBatch(
    val id: String,
    @SerialName("warehouse_id") val warehouseId: String,
    @SerialName("product_id") val productId: String,
    val quantity: Int = 0,
    @SerialName("batch_number") val batchNumber: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    val products: Product? = null
)

@Serializable
data class Supplier(
    val id: String,
    val name: String
)

/** Encodable payload for inserting a `warehouse_stock_batches` row. Mirrors iOS `InsertStockBatch`. */
@Serializable
data class WarehouseStockBatchInsert(
    @SerialName("warehouse_id") val warehouseId: String,
    @SerialName("product_id") val productId: String,
    val quantity: Int,
    @SerialName("batch_number") val batchNumber: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("company_id") val companyId: String,
    @SerialName("supplier_id") val supplierId: String? = null
)

/**
 * Encodable payload for inserting a `warehouse_transactions` row. Mirrors iOS
 * `InsertWarehouseTransaction` (`Models/Warehouse.swift` L122-153).
 *
 * DEVIATION FROM BRIEF: the brief's prose says "all nullable except the
 * first five required fields" (warehouseId, productId, transactionType,
 * quantityChange, userId), which would make [companyId] nullable. The cited
 * iOS source it's ported from declares `companyId` as non-optional
 * (required), matching the DB constraint (`company_id` is `NOT NULL` on
 * `warehouse_transactions` — see this task's own rationale for adding
 * `companyId` to [Warehouse]). Following the DB constraint and iOS source
 * over the brief's field count: [companyId] is required here.
 */
@Serializable
data class WarehouseTransactionInsert(
    @SerialName("warehouse_id") val warehouseId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("transaction_type") val transactionType: String,
    @SerialName("quantity_change") val quantityChange: Int,
    @SerialName("user_id") val userId: String,
    @SerialName("batch_id") val batchId: String? = null,
    val notes: String? = null,
    @SerialName("company_id") val companyId: String,
    @SerialName("quantity_before") val quantityBefore: Int? = null,
    @SerialName("quantity_after") val quantityAfter: Int? = null,
    @SerialName("batch_number") val batchNumber: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null
)

/**
 * Reasons a manual stock adjustment can be booked for. `raw` is the literal
 * value stored in `warehouse_transactions.transaction_type`. Mirrors iOS
 * `WarehouseViewModel.AdjustReason`.
 */
enum class AdjustReason(val raw: String) {
    REFILL_RETURN("adjustment_refill_return"),
    CORRECTION("adjustment_correction"),
    DAMAGE("adjustment_damage"),
    EXPIRED("adjustment_expired")
}

@Serializable
data class Paxcounter(
    val id: String? = null,
    @SerialName("embedded_id") val embeddedId: String? = null,
    val count: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
)

// UI state models (not serialized to/from Supabase)

/**
 * Severity of a single product's stock deficit. Declaration order matches
 * `ios/VMflow/Models/VendingMachine.swift`'s `StockSeverity` so the
 * synthesized `Comparable`/`ordinal` ordering agrees: [CRITICAL] is worst
 * and sorts first.
 */
enum class StockSeverity { CRITICAL, LOW, FILL_BELOW }

/** Warehouse stock availability for a deficient product. */
enum class WarehouseAvailability { IN_STOCK, NO_STOCK, NEEDS_SWAP, UNKNOWN }

/** Aggregated product deficit info for display on machine cards (machine list). */
data class TrayDeficit(
    val productName: String,
    val imagePath: String?,
    val deficit: Int,
    val severity: StockSeverity,
    val isDiscontinued: Boolean,
    val warehouseAvailability: WarehouseAvailability
)

data class MachineWithStats(
    val machine: VendingMachineWithEmbedded,
    val todayRevenue: Double = 0.0,
    val todaySalesCount: Int = 0,
    val yesterdayRevenue: Double = 0.0,
    val lastSaleAt: String? = null,
    val paxCount: Int = 0,
    val trays: List<Tray> = emptyList(),
    val trayDeficits: List<TrayDeficit> = emptyList(),
    val swapNeededCount: Int = 0,
    val noStockCount: Int = 0
) {
    enum class StockHealth { OK, LOW, CRITICAL }

    val stockHealth: StockHealth
        get() = when {
            trays.isEmpty() -> StockHealth.OK
            trays.any { it.isCritical } -> StockHealth.CRITICAL
            trays.any { it.isLow } -> StockHealth.LOW
            else -> StockHealth.OK
        }

    val lowTrayCount: Int
        get() = trays.count { it.isLow || it.isCritical }
}

/**
 * Pre-"Phase 5" refill rump models. `RefillItem`/`RefillMachine`/
 * `RefillSummary` are the names the Phase 5 plan (`.superpowers/sdd/`)
 * hands to the new tour models below — `ui/refill/RefillViewModel.kt` and
 * its Compose steps plus `data/RefillRepository.kt` are the only remaining
 * consumers of this shape, and they are rewritten wholesale in Task 6-9 of
 * that plan. Renamed (not deleted) here purely so the still-wired old
 * wizard screens keep compiling in the meantime — delete this block
 * alongside that rewrite.
 */
data class LegacyRefillItem(
    val tray: Tray,
    val targetStock: Int,
    val fillAmount: Int = 0
) {
    val currentStock: Int get() = tray.currentStock
    val maxFillAmount: Int get() = tray.capacity - tray.currentStock
}

data class LegacyRefillMachine(
    val machine: VendingMachineWithEmbedded,
    val items: List<LegacyRefillItem>,
    val isCompleted: Boolean = false
)

data class LegacyRefillSummary(
    val machinesVisited: Int,
    val traysRefilled: Int,
    val totalItemsAdded: Int
)

// ─────────────────────────────────────────────────────────────────────────
// Refill tour models (Phase 5). Ported from the model structs at the top of
// `ios/VMflow/ViewModels/RefillWizardViewModel.swift` (L7-166), plus
// `WarehousePositionGroup`/`WarehouseProductPosition` from
// `ios/VMflow/Models/Warehouse.swift` (L150-186). `@Serializable` only
// where tour persistence or a Supabase wire format needs it — pure
// UI/derived-value models stay plain data classes, matching the
// `WarehouseProductSummary`/`TrayDeficit` precedent above.
// ─────────────────────────────────────────────────────────────────────────

/**
 * One tray in the refill tour: how much to add, and whether it's in scope
 * for the currently active tour. Mirrors iOS `RefillTray`
 * (`RefillWizardViewModel.swift` L73-119).
 */
@Serializable
data class RefillTray(
    val tray: Tray,
    val fillAmount: Int,
    val isInTour: Boolean = true
) {
    val deficit: Int get() = tray.deficit
    val maxFill: Int get() = tray.capacity - tray.currentStock
    val targetStock: Int get() = tray.currentStock + fillAmount
}

/**
 * A machine in the refill tour with its trays and pack/refill/skip state.
 * Mirrors iOS `RefillMachine` (`RefillWizardViewModel.swift` L7-70).
 */
@Serializable
data class RefillMachine(
    val machine: VendingMachineWithEmbedded,
    val trays: List<RefillTray>,
    val isPacked: Boolean = false,
    val isRefilled: Boolean = false,
    val isSkipped: Boolean = false
) {
    val totalDeficit: Int get() = trays.sumOf { it.deficit }
    val traysNeedingRefill: Int get() = trays.count { it.deficit > 0 }
    val totalCurrentStock: Int get() = trays.sumOf { it.tray.currentStock }
    val totalCapacity: Int get() = trays.sumOf { it.tray.capacity }
    val stockPercent: Int
        get() = if (totalCapacity > 0) {
            (totalCurrentStock.toDouble() / totalCapacity.toDouble() * 100).roundToInt()
        } else {
            0
        }
}

/** One machine's need for a specific product. Mirrors iOS `MachineNeed`. */
data class MachineNeed(
    val machineId: String,
    val machineName: String,
    val quantity: Int,
    val capacity: Int
)

/**
 * A product grouped across all machines that need it — one row of the
 * combined packing list. Mirrors iOS `CombinedPackingItem`.
 */
data class CombinedPackingItem(
    val productId: String,
    /**
     * Null when the tray's `products` relation is missing (unassigned slot,
     * or the join wasn't populated) — passed through as-is rather than
     * synthesized here to keep [xyz.vmflow.data.RefillTourLogic] pure. The
     * UI resolves a missing name to the localized
     * `R.string.machine_card_unassigned_slot`, same fallback as the machine
     * card list; that string needs a slot number, which this item doesn't
     * carry (it's grouped by product across every tray/machine that needs
     * it), so the UI reads the slot number from the item's `machineNeeds`/
     * tray context instead.
     */
    val productName: String?,
    val imagePath: String?,
    val sellprice: Double?,
    val totalQuantity: Int,
    val machineNeeds: List<MachineNeed>
)

/** Per-machine result entry for the tour log; source of the tour summary. Mirrors iOS `TourLogEntry`. */
@Serializable
data class TourLogEntry(
    val machineId: String,
    val machineName: String,
    val traysRefilled: Int,
    val totalAdded: Int,
    val skipped: Boolean
)

/**
 * Folder-like group of warehouse positions; groups can nest via [parentId].
 * Maps to `warehouse_position_groups` (`Docker/supabase/migrations/20260318200000_warehouse_position_groups.sql`).
 */
@Serializable
data class WarehousePositionGroup(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int
)

/**
 * Physical warehouse slot for a product. Maps to `warehouse_product_positions`
 * (`Docker/supabase/migrations/20260318100000_warehouse_product_positions.sql`).
 */
@Serializable
data class WarehouseProductPosition(
    @SerialName("product_id") val productId: String,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("group_id") val groupId: String? = null
)

/**
 * RPC input row for `refill_machine_trays`
 * (`Docker/supabase/migrations/20260511120000_refill_machine_trays_rpc.sql`,
 * `p_trays` param: `[{"tray_id": "<uuid>", "fill_amount": <int>}, ...]`).
 */
@Serializable
data class RefillTrayPayload(
    @SerialName("tray_id") val trayId: String,
    @SerialName("fill_amount") val fillAmount: Int
)

/**
 * RPC return row from `refill_machine_trays`
 * (`Docker/supabase/migrations/20260511120000_refill_machine_trays_rpc.sql`
 * `RETURNS TABLE` block).
 */
@Serializable
data class TrayApplicationResult(
    @SerialName("tray_id") val trayId: String,
    @SerialName("old_stock") val oldStock: Int,
    @SerialName("new_stock") val newStock: Int,
    @SerialName("fill_amount") val fillAmount: Int,
    @SerialName("was_already_applied") val wasAlreadyApplied: Boolean
)

/**
 * Step of the refill wizard's flow (pack → refill → summary). Lives here
 * rather than in `ui/refill/` so the data layer (`TourStore`) can share it
 * with the UI layer without a `data` → `ui` dependency.
 */
@Serializable
enum class RefillStep {
    PACKING, REFILL, SUMMARY
}

/**
 * Resume-state snapshot for an in-progress tour, persisted across app
 * restarts. [savedAt] is deliberately an ISO-8601 string (not a date type)
 * so it round-trips through JSON without a custom serializer module — a
 * later task writes and reads it.
 */
@Serializable
data class PersistedTourState(
    val step: RefillStep,
    val machines: List<RefillMachine>,
    val currentMachineIndex: Int,
    val selectedWarehouseId: String?,
    val tourId: String,
    val tourLog: List<TourLogEntry>,
    val savedAt: String
)

/**
 * One stock intake entry for the warehouse "recent intakes" list. UI-only,
 * built from a decode-intermediate form in the repository layer.
 * [productName] is nullable (the joined `products` row may be missing, or
 * its `name` may be null) — the repository passes it through as-is and the
 * UI layer resolves a missing name to the localized `R.string.product_unnamed`
 * fallback, the same pattern the intake product picker already uses.
 */
data class IntakeEntry(
    val id: String,
    val productId: String,
    val productName: String?,
    val imagePath: String?,
    val quantity: Int,
    val supplierName: String?,
    val createdAt: String
)

/**
 * Per-product stock summary for the warehouse overview: total quantity and
 * batch count across all of a product's batches, plus its earliest-expiring
 * batch's severity. UI-only — result of [xyz.vmflow.data.WarehouseIntakeLogic.buildProductSummaries],
 * never decoded/encoded directly. Mirrors iOS `WarehouseProductSummary`.
 */
data class WarehouseProductSummary(
    val productId: String,
    val productName: String,
    val imagePath: String?,
    val totalQuantity: Int,
    val batchCount: Int,
    val earliestExpiration: String?,
    val discontinued: Boolean,
    val expirationStatus: ExpirationStatus
) {
    val isLow: Boolean get() = totalQuantity > 0 && totalQuantity < 10
    val isOutOfStock: Boolean get() = totalQuantity == 0
}
