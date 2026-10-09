package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Network + auth seam for [xyz.vmflow.ui.machines.MachineAnalysisEngine] —
 * mirrors the fetches in `ios/VMflow/ViewModels/MachineAnalysisViewModel.swift`
 * (lines 249-467). Kept as an interface (same shape as [DashboardDataSource])
 * so the merge logic (KPI row + tenure -> tier per product) is unit-testable
 * with a fake, no network, no Robolectric.
 */
interface MachineAnalysisDataSource {
    /** Current user's `company_id`, via the `get-my-organization` edge function (same lookup `DashboardViewModel` already uses). */
    suspend fun fetchCompanyId(): String

    /** `get_machine_product_kpis(p_machine_id, p_company_id, p_days)` — per-product KPIs aggregated across every slot the product occupies. */
    suspend fun fetchProductKpis(machineId: String, companyId: String, days: Int): List<ProductKpiRow>

    /** `get_product_sales_velocity(p_company_id, p_days)` — fleet-wide avg daily units, keyed by product id. Only entries with velocity > 0 are kept. */
    suspend fun fetchVelocity(companyId: String, days: Int): Map<String, Double>

    /** Trays with a pending item on the machine's open slot change request. */
    suspend fun fetchPendingTrayIds(machineId: String): Set<String>

    /**
     * Queues [productId] for [trayId] on the machine's slot change request
     * (merging with its pending items, keeping the slot's capacity) and
     * returns the trays now pending. Never writes `machine_trays`: the slot is
     * rebuilt on the next refill tour. Port of web `useMachineAnalysis.applySwap`.
     */
    suspend fun queueReplacement(machineId: String, trayId: String, productId: String): Set<String>
}

/**
 * One row of `get_machine_product_kpis` — a product's aggregated performance
 * within a single machine over the requested window. `offeredSince` comes
 * straight from `machine_product_offerings` via the RPC (survives slot
 * moves; only re-added after being removed from every slot starts a fresh
 * trial) — Android never queries that table directly, matching iOS.
 */
data class ProductKpiRow(
    val productId: String,
    val productName: String?,
    val unitsSold: Int,
    val totalCapacity: Int?,
    val totalStock: Int?,
    val slots: List<Int>,
    val offeredSince: Instant?,
    val revenueEur: Double,
)

/**
 * Postgres `numeric` values coming back from an RPC may be serialized as
 * either a JSON number or a JSON string depending on the PostgREST version —
 * mirrors the defensive decode in `MachineAnalysisViewModel.swift` (lines
 * 288-297 and 312-318) for `revenue_eur` / `avg_daily_units`.
 */
internal object FlexibleDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)

    override fun deserialize(decoder: Decoder): Double {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return 0.0
        return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: 0.0
    }
}

@Serializable
private data class KpiResponse(val products: List<KpiRow> = emptyList())

@Serializable
private data class KpiRow(
    @SerialName("product_id") val productId: String,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("units_sold") val unitsSold: Int = 0,
    @SerialName("total_capacity") val totalCapacity: Int? = null,
    @SerialName("total_stock") val totalStock: Int? = null,
    val slots: List<Int>? = null,
    @SerialName("offered_since") val offeredSince: Instant? = null,
    @SerialName("revenue_eur") @Serializable(with = FlexibleDoubleSerializer::class) val revenueEur: Double = 0.0,
)

@Serializable
private data class VelocityRow(
    @SerialName("product_id") val productId: String,
    @SerialName("avg_daily_units") @Serializable(with = FlexibleDoubleSerializer::class) val avgDailyUnits: Double = 0.0,
)

@Serializable
private data class ProductKpiParams(
    @SerialName("p_machine_id") val machineId: String,
    @SerialName("p_company_id") val companyId: String,
    @SerialName("p_days") val days: Int,
)

@Serializable
private data class VelocityParams(
    @SerialName("p_company_id") val companyId: String,
    @SerialName("p_days") val days: Int,
)

object MachineAnalysisRepository : MachineAnalysisDataSource {
    private val postgrest get() = SupabaseService.client.postgrest

    override suspend fun fetchCompanyId(): String {
        val response = AuthRepository.fetchOrganization().getOrThrow()
        return response.organization?.id
            ?: throw IllegalStateException("Could not determine company")
    }

    override suspend fun fetchProductKpis(machineId: String, companyId: String, days: Int): List<ProductKpiRow> {
        val params = Json.encodeToJsonElement(
            ProductKpiParams(machineId = machineId, companyId = companyId, days = days),
        ) as JsonObject
        val response = postgrest.rpc("get_machine_product_kpis", params).decodeAs<KpiResponse>()
        return response.products.map { row ->
            ProductKpiRow(
                productId = row.productId,
                productName = row.productName,
                unitsSold = row.unitsSold,
                totalCapacity = row.totalCapacity,
                totalStock = row.totalStock,
                slots = row.slots ?: emptyList(),
                offeredSince = row.offeredSince,
                revenueEur = row.revenueEur,
            )
        }
    }

    override suspend fun fetchVelocity(companyId: String, days: Int): Map<String, Double> {
        val params = Json.encodeToJsonElement(
            VelocityParams(companyId = companyId, days = days),
        ) as JsonObject
        val rows = postgrest.rpc("get_product_sales_velocity", params).decodeList<VelocityRow>()
        return rows.filter { it.avgDailyUnits > 0 }.associate { it.productId to it.avgDailyUnits }
    }

    override suspend fun fetchPendingTrayIds(machineId: String): Set<String> =
        SlotChangeRepository.fetchOpenRequests(listOf(machineId)).getOrThrow()[machineId]
            ?.items?.map { it.trayId }?.toSet()
            .orEmpty()

    override suspend fun queueReplacement(machineId: String, trayId: String, productId: String): Set<String> =
        SlotChangeRepository.queueReplacement(machineId, trayId, productId).getOrThrow()
}
