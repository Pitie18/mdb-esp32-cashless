package xyz.vmflow.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.vmflow.models.AdjustReason
import xyz.vmflow.models.IntakeEntry
import xyz.vmflow.models.Product
import xyz.vmflow.models.Supplier
import xyz.vmflow.models.Warehouse
import xyz.vmflow.models.WarehouseProductSummary
import xyz.vmflow.models.WarehouseStockBatch
import xyz.vmflow.models.WarehouseStockBatchInsert
import xyz.vmflow.models.WarehouseTransactionInsert

/**
 * Lightweight decode target for [WarehouseRepository.fetchProductSummaries] —
 * only the columns [WarehouseIntakeLogic.buildProductSummaries] needs, not
 * the full `WarehouseStockBatch` model (which requires `id`/`warehouse_id`
 * this query doesn't select). Mirrors iOS `loadProductSummaries()`'s private
 * `BatchRow` (`WarehouseViewModel.swift:153-163`).
 */
@Serializable
private data class ProductSummaryBatchRow(
    @SerialName("product_id") val productId: String,
    val quantity: Int = 0,
    @SerialName("expiration_date") val expirationDate: String? = null
)

/** Decode target for [WarehouseRepository.fetchAssignedProductIds]. */
@Serializable
private data class TrayProductIdRow(
    @SerialName("product_id") val productId: String? = null
)

/**
 * Decode target for [WarehouseRepository.fetchRecentIntakes]'s nested-embed
 * select. Mirrors iOS `loadRecentIntakes()`'s private `TransactionWithProduct`
 * (`WarehouseViewModel.swift:274-300`).
 */
@Serializable
private data class IntakeTransactionRow(
    val id: String,
    @SerialName("product_id") val productId: String,
    @SerialName("quantity_change") val quantityChange: Int = 0,
    @SerialName("created_at") val createdAt: String,
    val notes: String? = null,
    val products: IntakeTransactionProductRef? = null,
    val suppliers: IntakeTransactionSupplierRef? = null
)

@Serializable
private data class IntakeTransactionProductRef(
    val name: String? = null,
    @SerialName("image_path") val imagePath: String? = null
)

@Serializable
private data class IntakeTransactionSupplierRef(
    val name: String? = null
)

/** Encodable payload for inserting a `suppliers` row. Mirrors iOS `resolveOrCreateSupplier`'s local `NewSupplier`. */
@Serializable
private data class SupplierInsert(
    val name: String,
    @SerialName("company_id") val companyId: String
)

/** Decode target for [WarehouseRepository.lookupBarcode]. */
@Serializable
private data class BarcodeProductIdRow(
    @SerialName("product_id") val productId: String
)

/** Decode target for the `.select("id")` returning-representation of [WarehouseRepository.bookIntake]'s batch insert. */
@Serializable
private data class InsertedBatchIdRow(
    val id: String
)

/**
 * Decode target for [WarehouseRepository.adjustBatch]'s current-batch read.
 * Mirrors iOS `adjustBatch(...)`'s private `CurrentBatch` (`WarehouseViewModel.swift:604-618`).
 */
@Serializable
private data class CurrentBatchRow(
    @SerialName("product_id") val productId: String,
    val quantity: Int = 0,
    @SerialName("batch_number") val batchNumber: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null
)

object WarehouseRepository {
    private val postgrest get() = SupabaseService.client.postgrest
    private val auth get() = SupabaseService.client.auth

    suspend fun fetchWarehouses(): Result<List<Warehouse>> {
        return try {
            val warehouses = postgrest.from("warehouses")
                .select(Columns.raw("id, name, address, notes, company_id"))
                .decodeList<Warehouse>()
            Result.success(warehouses)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches every product, including discontinued ones. Deliberately
     * separate from [TrayRepository.fetchProducts] — that function filters
     * `discontinued = false` server-side for the tray-editing product
     * picker (where discontinued products should never be assignable), but
     * the warehouse stock overview needs the full catalogue so the
     * `includeArchived` toggle (matching iOS `WarehouseViewModel`'s
     * client-side `filteredSummaries`; wired up in `WarehouseStockTab.kt`)
     * has discontinued products to reveal.
     * Do not collapse this back into a call to `fetchProducts()`.
     */
    suspend fun fetchAllProductsIncludingDiscontinued(): Result<List<Product>> {
        return try {
            val products = postgrest.from("products")
                .select(Columns.raw("id, name, image_path, discontinued")) {
                    order("name", Order.ASCENDING)
                }
                .decodeList<Product>()
            Result.success(products)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds one [WarehouseProductSummary] per product (including zero-stock
     * and discontinued products — no filtering here, see
     * [WarehouseIntakeLogic.buildProductSummaries]), merged with the given
     * warehouse's batches. Mirrors iOS `loadProductSummaries()`.
     */
    suspend fun fetchProductSummaries(warehouseId: String): Result<List<WarehouseProductSummary>> {
        return try {
            val products = fetchAllProductsIncludingDiscontinued().getOrThrow()
            val batches = postgrest.from("warehouse_stock_batches")
                .select(Columns.raw("product_id, quantity, expiration_date")) {
                    filter {
                        eq("warehouse_id", warehouseId)
                        gt("quantity", 0)
                    }
                }
                .decodeList<ProductSummaryBatchRow>()

            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val summaries = WarehouseIntakeLogic.buildProductSummaries(
                products = products.map { product ->
                    WarehouseIntakeLogic.ProductSummaryInput(
                        productId = product.id,
                        name = product.name,
                        imagePath = product.imagePath,
                        discontinued = product.discontinued
                    )
                },
                batches = batches.map { batch ->
                    WarehouseIntakeLogic.BatchSummaryInput(
                        productId = batch.productId,
                        quantity = batch.quantity,
                        expirationDate = batch.expirationDate
                    )
                },
                today = today
            )
            Result.success(summaries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Products currently occupying at least one machine tray slot, across all machines. */
    suspend fun fetchAssignedProductIds(): Result<Set<String>> {
        return try {
            // Company-wide read of a growing table, so paged for the same
            // reason as [RefillRepository.fetchRefillMachines]: PostgREST caps
            // a response at `db.max_rows` silently, and a truncated answer here
            // under-reports which products are assigned to a slot — which the
            // warehouse list then treats as "safe to run out of". Ordered by
            // `id` only to give paging a total order — PostgREST orders by any
            // column, selected or not, so the decode target stays as narrow as
            // it was. The result is a Set, so row order is irrelevant.
            val rows = fetchAllPages { from, to ->
                postgrest.from("machine_trays")
                    .select(Columns.raw("product_id")) {
                        filter {
                            filterNot("product_id", FilterOperator.IS, "null")
                        }
                        order("id", Order.ASCENDING)
                        range(from, to)
                    }
                    .decodeList<TrayProductIdRow>()
            }
            Result.success(rows.mapNotNull { it.productId }.toSet())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The 10 most recent intake transactions for a warehouse, newest first. */
    suspend fun fetchRecentIntakes(warehouseId: String): Result<List<IntakeEntry>> {
        return try {
            val transactions = postgrest.from("warehouse_transactions")
                .select(Columns.raw("id, product_id, quantity_change, created_at, notes, products(name, image_path), suppliers(name)")) {
                    filter {
                        eq("warehouse_id", warehouseId)
                        eq("transaction_type", "intake")
                    }
                    order("created_at", Order.DESCENDING)
                    limit(10)
                }
                .decodeList<IntakeTransactionRow>()

            val entries = transactions.map { tx ->
                IntakeEntry(
                    id = tx.id,
                    productId = tx.productId,
                    productName = tx.products?.name,
                    imagePath = tx.products?.imagePath,
                    quantity = tx.quantityChange,
                    supplierName = tx.suppliers?.name,
                    createdAt = tx.createdAt
                )
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchWarehouseStock(warehouseId: String): Result<List<WarehouseStockBatch>> {
        return try {
            val batches = postgrest.from("warehouse_stock_batches")
                .select(Columns.raw("id, warehouse_id, product_id, quantity, batch_number, expiration_date, products(id, name, image_path, discontinued, sellprice)")) {
                    filter {
                        eq("warehouse_id", warehouseId)
                        gt("quantity", 0)
                    }
                    order("expiration_date", Order.ASCENDING)
                }
                .decodeList<WarehouseStockBatch>()
            Result.success(batches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllWarehouseStock(): Result<List<WarehouseStockBatch>> {
        return try {
            val batches = postgrest.from("warehouse_stock_batches")
                .select(Columns.raw("id, warehouse_id, product_id, quantity, batch_number, expiration_date, products(id, name, image_path, discontinued, sellprice)")) {
                    filter {
                        gt("quantity", 0)
                    }
                }
                .decodeList<WarehouseStockBatch>()
            Result.success(batches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Suppliers for the intake-form picker. Mirrors iOS `loadSuppliersForIntake()` (`WarehouseViewModel.swift:334-339`). */
    suspend fun fetchSuppliers(): Result<List<Supplier>> {
        return try {
            val suppliers = postgrest.from("suppliers")
                .select(Columns.raw("id, name")) {
                    order("name", Order.ASCENDING)
                }
                .decodeList<Supplier>()
            Result.success(suppliers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Finds an existing supplier by case-insensitive name match against
     * [existing] (no network round-trip on a hit), or creates one. On a
     * unique-constraint failure (a race with another client creating the
     * same name concurrently) re-fetches suppliers once and matches again
     * instead of failing outright. Mirrors iOS `resolveOrCreateSupplier(named:)`
     * (`WarehouseViewModel.swift:375-402`).
     */
    suspend fun resolveOrCreateSupplier(
        name: String,
        companyId: String,
        existing: List<Supplier>
    ): Result<Supplier> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Supplier name must not be blank"))
        }
        existing.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let {
            return Result.success(it)
        }
        return try {
            val inserted = postgrest.from("suppliers")
                .insert(SupplierInsert(name = trimmed, companyId = companyId)) {
                    select(Columns.raw("id, name"))
                }
                .decodeList<Supplier>()
            val created = inserted.firstOrNull()
                ?: return Result.failure(IllegalStateException("Supplier insert returned no row"))
            Result.success(created)
        } catch (e: Exception) {
            val refreshed = fetchSuppliers().getOrElse { return Result.failure(e) }
            val match = refreshed.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            if (match != null) Result.success(match) else Result.failure(e)
        }
    }

    /**
     * Looks up a product by barcode. Returns `null` (not `Result.failure`) when
     * no row matches — that's an expected outcome, not an error. Mirrors iOS
     * `lookupBarcode(_:)` (`WarehouseViewModel.swift:436-455`).
     */
    suspend fun lookupBarcode(barcode: String): Result<String?> {
        return try {
            val rows = postgrest.from("product_barcodes")
                .select(Columns.raw("product_id")) {
                    filter { eq("barcode", barcode) }
                    limit(1)
                }
                .decodeList<BarcodeProductIdRow>()
            Result.success(rows.firstOrNull()?.productId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Books a warehouse intake: inserts a brand-new `warehouse_stock_batches`
     * row (no merge-into-existing-batch logic — see the plan's global
     * constraints), then an `intake`-typed `warehouse_transactions` row
     * referencing the new batch's id. Mirrors iOS `bookIntake(...)`
     * (`WarehouseViewModel.swift:459-530`).
     */
    suspend fun bookIntake(
        warehouseId: String,
        companyId: String,
        productId: String,
        quantity: Int,
        batchNumber: String?,
        expirationDate: String?,
        supplierId: String?
    ): Result<Unit> {
        return try {
            val userId = auth.currentUserOrNull()?.id
                ?: throw IllegalStateException("No authenticated user")

            val newBatch = WarehouseStockBatchInsert(
                warehouseId = warehouseId,
                productId = productId,
                quantity = quantity,
                batchNumber = batchNumber,
                expirationDate = expirationDate,
                companyId = companyId,
                supplierId = supplierId
            )
            val insertedBatches = postgrest.from("warehouse_stock_batches")
                .insert(newBatch) {
                    select(Columns.raw("id"))
                }
                .decodeList<InsertedBatchIdRow>()
            val batchId = insertedBatches.firstOrNull()?.id

            val transaction = WarehouseTransactionInsert(
                warehouseId = warehouseId,
                productId = productId,
                transactionType = "intake",
                quantityChange = quantity,
                userId = userId,
                batchId = batchId,
                notes = batchNumber?.let { if (it.isBlank()) null else "Batch: $it" },
                companyId = companyId,
                quantityBefore = null,
                quantityAfter = null,
                batchNumber = batchNumber,
                expirationDate = expirationDate,
                supplierId = supplierId
            )
            postgrest.from("warehouse_transactions").insert(transaction)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Non-empty batches for a specific product in a warehouse, oldest expiration
     * first. Mirrors iOS `loadBatchesForProduct(_:)` (`WarehouseViewModel.swift:547-576`).
     */
    suspend fun fetchBatchesForProduct(warehouseId: String, productId: String): Result<List<WarehouseStockBatch>> {
        return try {
            val batches = postgrest.from("warehouse_stock_batches")
                .select(Columns.raw("id, warehouse_id, product_id, quantity, batch_number, expiration_date, supplier_id")) {
                    filter {
                        eq("warehouse_id", warehouseId)
                        eq("product_id", productId)
                        gt("quantity", 0)
                    }
                    order("expiration_date", Order.ASCENDING)
                }
                .decodeList<WarehouseStockBatch>()
            Result.success(batches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Adjusts a batch's quantity by a signed delta, clamped at zero, and logs a
     * `warehouse_transactions` row typed with one of [AdjustReason]'s four
     * `adjustment_*` values — never `"incoming"`/`"intake"` (those belong to
     * [bookIntake]). Unlike [bookIntake], `quantity_before`/`quantity_after` are
     * populated here. Mirrors iOS `adjustBatch(...)` (`WarehouseViewModel.swift:587-671`).
     */
    suspend fun adjustBatch(
        warehouseId: String,
        companyId: String,
        batchId: String,
        quantityChange: Int,
        reason: AdjustReason,
        notes: String?
    ): Result<Unit> {
        return try {
            val userId = auth.currentUserOrNull()?.id
                ?: throw IllegalStateException("No authenticated user")

            val current = postgrest.from("warehouse_stock_batches")
                .select(Columns.raw("product_id, quantity, batch_number, expiration_date, supplier_id")) {
                    filter { eq("id", batchId) }
                    limit(1)
                }
                .decodeSingle<CurrentBatchRow>()

            val quantityBefore = current.quantity
            val quantityAfter = maxOf(0, quantityBefore + quantityChange)

            postgrest.from("warehouse_stock_batches")
                .update({
                    set("quantity", quantityAfter)
                }) {
                    filter { eq("id", batchId) }
                }

            val transaction = WarehouseTransactionInsert(
                warehouseId = warehouseId,
                productId = current.productId,
                transactionType = reason.raw,
                quantityChange = quantityChange,
                userId = userId,
                batchId = batchId,
                notes = notes?.let { if (it.isBlank()) null else it },
                companyId = companyId,
                quantityBefore = quantityBefore,
                quantityAfter = quantityAfter,
                batchNumber = current.batchNumber,
                expirationDate = current.expirationDate,
                supplierId = current.supplierId
            )
            postgrest.from("warehouse_transactions").insert(transaction)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Calls the `deduct_warehouse_stock_fifo` RPC with the 7 named parameters,
     * exactly matching iOS `RefillWizardViewModel.deductWarehouseStock(warehouseId:)`
     * (`RefillWizardViewModel.swift:1740-1798`). Not called by anything in this
     * phase — a future `RefillViewModel` wires this up. Parameter names are
     * binding (do not rename): `p_warehouse_id`, `p_product_id`, `p_quantity`,
     * `p_user_id`, `p_reference_id`, `p_notes`, `p_metadata`. `p_metadata`'s
     * values may be `null` — that's preserved as JSON `null`, not stripped.
     */
    suspend fun deductWarehouseStockFifo(
        warehouseId: String,
        productId: String,
        quantity: Int,
        userId: String?,
        referenceId: String?,
        notes: String,
        metadata: Map<String, String?> = emptyMap()
    ): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("p_warehouse_id", warehouseId)
                put("p_product_id", productId)
                put("p_quantity", quantity)
                put("p_user_id", userId)
                put("p_reference_id", referenceId)
                put("p_notes", notes)
                putJsonObject("p_metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            postgrest.rpc("deduct_warehouse_stock_fifo", params)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
