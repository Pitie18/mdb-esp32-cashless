package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.max

/**
 * Nullable twin of [FlexibleDoubleSerializer]: Postgres `numeric` arrives as
 * a JSON number or string depending on the PostgREST version; null stays null.
 */
internal object FlexibleNullableDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableDouble", PrimitiveKind.DOUBLE).nullable

    override fun serialize(encoder: Encoder, value: Double?) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(if (value == null) JsonNull else JsonPrimitive(value))
    }

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
    }
}

/** An order marked as placed with a supplier (`purchase_orders`), with its lines. */
@Serializable
data class PurchaseOrder(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("warehouse_id") val warehouseId: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String = "ordered",
    val note: String? = null,
    @SerialName("ordered_by_name") val orderedByName: String? = null,
    @SerialName("purchase_order_lines") val rawLines: List<PurchaseOrderLine> = emptyList(),
) {
    val lines: List<PurchaseOrderLine> get() = rawLines.sortedBy { it.sortOrder }
    val totalUnits: Int get() = rawLines.sumOf { it.quantity }
}

@Serializable
data class PurchaseOrderLine(
    val id: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("product_name") val productName: String? = null,
    /** Units, not cartons. */
    val quantity: Int = 0,
    @SerialName("unit_size") val unitSize: Int = 1,
    @SerialName("unit_price_net") @Serializable(with = FlexibleNullableDoubleSerializer::class) val unitPriceNet: Double? = null,
    @SerialName("received_quantity") val receivedQuantity: Int? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** What the refiller counted for one delivered line. */
data class ReceiveLineInput(
    val lineId: String,
    val quantity: Int,
    /** `yyyy-MM-dd` or null. */
    val expirationDate: String?,
    val batchNumber: String?,
)

@Serializable
data class ReceiveResult(
    val status: String? = null,
    val booked: Int = 0,
    val units: Int = 0,
)

/** Main supplier, carton size and latest purchase price of a product. */
data class ProductOrderInfo(
    val preferredSupplierId: String? = null,
    val unitSize: Int = 1,
    val latestSupplierId: String? = null,
    val latestPriceNet: Double? = null,
) {
    /** Preferred supplier, else the supplier of the latest purchase price. */
    val supplierId: String? get() = preferredSupplierId ?: latestSupplierId
}

@Serializable
private data class OrderSettingsRow(
    @SerialName("order_buffer_days") val bufferDays: Int? = null,
    @SerialName("order_count_machine_stock") val countMachineStock: Boolean? = null,
    @SerialName("order_default_lead_days") val defaultLeadDays: Int? = null,
    @SerialName("order_default_interval_days") val defaultIntervalDays: Int? = null,
)

@Serializable
private data class OrderSupplierRow(
    val id: String,
    val name: String,
    val email: String? = null,
    @SerialName("customer_number") val customerNumber: String? = null,
    @SerialName("lead_time_days") val leadTimeDays: Int? = null,
    @SerialName("order_interval_days") val orderIntervalDays: Int? = null,
    @SerialName("min_order_value") @Serializable(with = FlexibleNullableDoubleSerializer::class) val minOrderValue: Double? = null,
)

@Serializable
private data class ProductOrderingRow(
    val id: String,
    @SerialName("preferred_supplier_id") val preferredSupplierId: String? = null,
    @SerialName("order_unit_size") val orderUnitSize: Int? = null,
)

@Serializable
private data class PurchaseSummaryRow(
    @SerialName("product_id") val productId: String,
    @SerialName("newest_supplier") val newestSupplier: String? = null,
    @SerialName("newest_net") @Serializable(with = FlexibleNullableDoubleSerializer::class) val newestNet: Double? = null,
)

@Serializable
private data class OrderTrayStockRow(
    @SerialName("product_id") val productId: String? = null,
    @SerialName("current_stock") val currentStock: Int? = null,
)

/**
 * Data and actions behind the warehouse "Order" tab and the expected
 * deliveries under Incoming: suppliers with their ordering rhythm, the
 * company's calculation settings, manual order-list entries and open
 * purchase orders. The suggestion itself is [OrderSuggestion]. Same queries
 * and RPCs (the purchase ordering migration in `Docker/supabase/migrations`) as the
 * web's `usePurchaseOrders` and iOS `PurchaseOrderService`.
 */
object PurchaseOrderRepository {
    private val postgrest get() = SupabaseService.client.postgrest
    private val json = Json { ignoreUnknownKeys = true }

    private const val ORDER_SELECT =
        "id, created_at, warehouse_id, supplier_id, supplier_name, status, note, ordered_by_name, " +
            "purchase_order_lines(id, product_id, product_name, quantity, unit_size, unit_price_net, received_quantity, sort_order)"

    // ── Settings & suppliers ────────────────────────────────────────────────

    suspend fun fetchSettings(companyId: String): Result<OrderSettings> = runCatching {
        val row = postgrest.from("companies")
            .select(Columns.raw("order_buffer_days, order_count_machine_stock, order_default_lead_days, order_default_interval_days")) {
                filter { eq("id", companyId) }
                limit(1)
            }
            .decodeList<OrderSettingsRow>()
            .firstOrNull()
        val d = OrderSettings()
        if (row == null) d else OrderSettings(
            bufferDays = row.bufferDays ?: d.bufferDays,
            countMachineStock = row.countMachineStock ?: d.countMachineStock,
            defaultLeadDays = row.defaultLeadDays ?: d.defaultLeadDays,
            defaultIntervalDays = row.defaultIntervalDays ?: d.defaultIntervalDays,
        )
    }

    suspend fun fetchSuppliers(): Result<List<OrderSupplier>> = runCatching {
        postgrest.from("suppliers")
            .select(Columns.raw("id, name, email, customer_number, lead_time_days, order_interval_days, min_order_value")) {
                order("name", Order.ASCENDING)
            }
            .decodeList<OrderSupplierRow>()
            .map {
                OrderSupplier(
                    id = it.id, name = it.name, customerNumber = it.customerNumber, email = it.email,
                    leadTimeDays = it.leadTimeDays, orderIntervalDays = it.orderIntervalDays,
                    minOrderValue = it.minOrderValue,
                )
            }
    }

    // ── Products ────────────────────────────────────────────────────────────

    /**
     * Preferred supplier, carton size and latest purchase price per product.
     * The purchase summary names the newest supplier; names are unique per
     * company, so it maps back to an id.
     */
    suspend fun fetchProductInfo(suppliers: List<OrderSupplier>): Result<Map<String, ProductOrderInfo>> = runCatching {
        val products = fetchAllPages { from, to ->
            postgrest.from("products")
                .select(Columns.raw("id, preferred_supplier_id, order_unit_size")) {
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<ProductOrderingRow>()
        }
        // Best-effort like on the web: without prices the list still works.
        val summaries = if (products.isEmpty()) emptyMap() else runCatching {
            val params = buildJsonObject {
                putJsonArray("p_product_ids") { products.forEach { add(it.id) } }
            }
            val body = postgrest.rpc("get_product_purchase_summary", params).data
            json.decodeFromString<List<PurchaseSummaryRow>>(body).associateBy { it.productId }
        }.getOrDefault(emptyMap())

        val byName = suppliers.associate { it.name.trim().lowercase() to it.id }
        products.associate { p ->
            val s = summaries[p.id]
            p.id to ProductOrderInfo(
                preferredSupplierId = p.preferredSupplierId,
                unitSize = max(1, p.orderUnitSize ?: 1),
                latestSupplierId = s?.newestSupplier?.let { byName[it.trim().lowercase()] },
                latestPriceNet = s?.newestNet,
            )
        }
    }

    /** Admins only (RLS): the product's main supplier (null clears it). */
    suspend fun setPreferredSupplier(productId: String, supplierId: String?): Result<Unit> = runCatching {
        postgrest.from("products").update({
            if (supplierId == null) setToNull("preferred_supplier_id") else set("preferred_supplier_id", supplierId)
        }) {
            filter { eq("id", productId) }
        }
        Unit
    }

    /** Admins only (RLS): units per carton. */
    suspend fun setUnitSize(productId: String, size: Int): Result<Unit> = runCatching {
        postgrest.from("products").update({ set("order_unit_size", max(1, size)) }) {
            filter { eq("id", productId) }
        }
        Unit
    }

    /**
     * Units sitting in machine slots per product (company-wide, RLS-scoped).
     * Paged like [WarehouseRepository.fetchAssignedProductIds]; its keys are
     * also the products assigned to a slot.
     */
    suspend fun fetchMachineStock(): Result<Map<String, Int>> = runCatching {
        val rows = fetchAllPages { from, to ->
            postgrest.from("machine_trays")
                .select(Columns.raw("product_id, current_stock")) {
                    filter { filterNot("product_id", FilterOperator.IS, "null") }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<OrderTrayStockRow>()
        }
        val stock = mutableMapOf<String, Int>()
        for (r in rows) {
            val id = r.productId ?: continue
            stock[id] = (stock[id] ?: 0) + max(0, r.currentStock ?: 0)
        }
        stock
    }

    // ── Order list (manual entries) ─────────────────────────────────────────

    suspend fun fetchManualEntries(warehouseId: String): Result<List<ManualOrderEntry>> = runCatching {
        postgrest.from("order_list_items")
            .select(Columns.raw("product_id, added_by_name, added_via, created_at")) {
                filter { eq("warehouse_id", warehouseId) }
            }
            .decodeList<ManualOrderEntry>()
    }

    suspend fun addToOrderList(warehouseId: String, productId: String): Result<Unit> = runCatching {
        postgrest.rpc("add_to_order_list", buildJsonObject {
            put("p_warehouse_id", warehouseId)
            put("p_product_id", productId)
            put("p_via", "android")
        })
        Unit
    }

    suspend fun removeFromOrderList(warehouseId: String, productId: String): Result<Unit> = runCatching {
        postgrest.rpc("remove_from_order_list", buildJsonObject {
            put("p_warehouse_id", warehouseId)
            put("p_product_id", productId)
        })
        Unit
    }

    // ── Purchase orders ─────────────────────────────────────────────────────

    suspend fun fetchOpenOrders(warehouseId: String): Result<List<PurchaseOrder>> = runCatching {
        postgrest.from("purchase_orders")
            .select(Columns.raw(ORDER_SELECT)) {
                filter {
                    eq("warehouse_id", warehouseId)
                    eq("status", "ordered")
                }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<PurchaseOrder>()
    }

    /** Admins only. Clears the manual entries of the ordered products. */
    suspend fun placeOrder(warehouseId: String, supplierId: String, lines: List<OrderLine>): Result<Unit> = runCatching {
        postgrest.rpc("place_purchase_order", buildJsonObject {
            put("p_warehouse_id", warehouseId)
            put("p_supplier_id", supplierId)
            putJsonArray("p_lines") {
                lines.filter { it.cartons > 0 }.forEach { l ->
                    addJsonObject {
                        put("product_id", l.product.productId)
                        put("quantity", l.units)
                        put("unit_size", max(1, l.product.unitSize))
                        val price = l.product.unitPriceNet
                        if (price != null) put("unit_price_net", price) else put("unit_price_net", JsonNull)
                    }
                }
            }
            put("p_note", JsonNull)
        })
        Unit
    }

    /** Admins only. */
    suspend fun cancelOrder(id: String): Result<Unit> = runCatching {
        postgrest.rpc("cancel_purchase_order", buildJsonObject { put("p_order_id", id) })
        Unit
    }

    /** Marks an order delivered without booking anything (goods booked by hand). */
    suspend fun closeOrder(id: String): Result<Unit> = runCatching {
        postgrest.rpc("close_purchase_order", buildJsonObject { put("p_order_id", id) })
        Unit
    }

    /** Books the delivery into the warehouse; a retry books nothing. */
    suspend fun receiveOrder(id: String, lines: List<ReceiveLineInput>): Result<ReceiveResult> = runCatching {
        val body = postgrest.rpc("receive_purchase_order", buildJsonObject {
            put("p_order_id", id)
            putJsonArray("p_lines") {
                lines.forEach { l ->
                    addJsonObject {
                        put("line_id", l.lineId)
                        put("quantity", max(0, l.quantity))
                        if (l.expirationDate != null) put("expiration_date", l.expirationDate) else put("expiration_date", JsonNull)
                        val batch = l.batchNumber?.trim().orEmpty()
                        if (batch.isNotEmpty()) put("batch_number", batch) else put("batch_number", JsonNull)
                    }
                }
            }
        }).data.trim()
        if (body.isEmpty() || body == "null") ReceiveResult() else json.decodeFromString<ReceiveResult>(body)
    }
}
