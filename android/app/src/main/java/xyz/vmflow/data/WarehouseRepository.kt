package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.vmflow.models.IntakeEntry
import xyz.vmflow.models.Product
import xyz.vmflow.models.Warehouse
import xyz.vmflow.models.WarehouseProductSummary
import xyz.vmflow.models.WarehouseStockBatch

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

object WarehouseRepository {
    private val postgrest get() = SupabaseService.client.postgrest

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
     * the warehouse stock overview needs the full catalogue so a future
     * `includeArchived` toggle (matching iOS `WarehouseViewModel`'s
     * client-side `filteredSummaries`) has discontinued products to reveal.
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
            val rows = postgrest.from("machine_trays")
                .select(Columns.raw("product_id")) {
                    filter {
                        filterNot("product_id", FilterOperator.IS, "null")
                    }
                }
                .decodeList<TrayProductIdRow>()
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
                    productName = tx.products?.name ?: "Unknown",
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
}
