package xyz.vmflow.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("firmware_version") val firmwareVersion: String? = null
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
    val notes: String? = null
)

@Serializable
data class WarehouseStockBatch(
    val id: String,
    @SerialName("warehouse_id") val warehouseId: String,
    @SerialName("product_id") val productId: String,
    val quantity: Int = 0,
    @SerialName("batch_number") val batchNumber: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    val products: Product? = null
)

@Serializable
data class Paxcounter(
    val id: String? = null,
    @SerialName("embedded_id") val embeddedId: String? = null,
    val count: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
)

// UI state models (not serialized to/from Supabase)
data class MachineWithStats(
    val machine: VendingMachineWithEmbedded,
    val todayRevenue: Double = 0.0,
    val todaySalesCount: Int = 0,
    val yesterdayRevenue: Double = 0.0,
    val lastSaleAt: String? = null,
    val paxCount: Int = 0,
    val trays: List<Tray> = emptyList()
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

data class RefillItem(
    val tray: Tray,
    val targetStock: Int,
    val fillAmount: Int = 0
) {
    val currentStock: Int get() = tray.currentStock
    val maxFillAmount: Int get() = tray.capacity - tray.currentStock
}

data class RefillMachine(
    val machine: VendingMachineWithEmbedded,
    val items: List<RefillItem>,
    val isCompleted: Boolean = false
)

data class RefillSummary(
    val machinesVisited: Int,
    val traysRefilled: Int,
    val totalItemsAdded: Int
)
