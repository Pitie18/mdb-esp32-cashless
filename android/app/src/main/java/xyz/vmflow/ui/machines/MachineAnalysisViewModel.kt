package xyz.vmflow.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.vmflow.data.AnalysisGridSlot
import xyz.vmflow.data.MachineAnalysis
import xyz.vmflow.data.MachineAnalysisDataSource
import xyz.vmflow.data.MachineAnalysisRepository
import xyz.vmflow.data.ProductKpiRow
import xyz.vmflow.data.ProductTierInfo
import xyz.vmflow.data.SlotTier
import xyz.vmflow.data.Suggestion
import xyz.vmflow.data.SwapLogContext
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

/**
 * Product-centric machine performance analysis — Android counterpart of
 * `ios/VMflow/ViewModels/MachineAnalysisViewModel.swift` (lines 215-523).
 * Performance is a property of the PRODUCT, not the slot: sales are
 * aggregated across every slot a product occupies (server-side, via
 * `get_machine_product_kpis`), and its tenure survives being moved between
 * trays (`machine_product_offerings`, read through the same RPC).
 *
 * Out of scope for this port: the `machine-insights` AI recommendations
 * panel (task briefs 24/25 only cover the grid, the KPI merge, and the
 * one-click swap — no AI panel is mentioned).
 */

/** Aggregated performance of one product within a single machine. Mirrors iOS `ProductAnalysis`. */
data class ProductAnalysis(
    val productId: String,
    val name: String,
    val imagePath: String?,
    /** item_numbers of the slots this product currently occupies. */
    val slots: List<Int>,
    /** tray ids of those slots (for applying swaps). */
    val trayIds: List<String>,
    val unitsSold: Int,
    val revenueEur: Double,
    val totalCapacity: Int,
    val totalStock: Int,
    val sellThroughPct: Double,
    val avgDailyUnits: Double,
    val daysUntilEmpty: Int?,
    /** Days since the product was first offered in this machine (survives slot moves). */
    val tenureDays: Int?,
    val tier: SlotTier,
    val suggestions: List<Suggestion>,
)

data class MachineAnalysisUiState(
    val products: List<ProductAnalysis> = emptyList(),
    val slots: List<AnalysisGridSlot> = emptyList(),
    val rowCount: Int = 0,
    val fillSuggestions: List<Suggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val days: Int = 30,
) {
    /** Underperforming products, worst first — the "products to review" list. Mirrors iOS `weakProducts`. */
    val weakProducts: List<ProductAnalysis>
        get() = products
            .filter { it.tier == SlotTier.DEAD || it.tier == SlotTier.WEAK }
            .sortedWith(compareBy<ProductAnalysis> { it.tier.severity }.thenBy { it.sellThroughPct })
}

/**
 * Merges KPI rows with tray/catalogue context into [ProductAnalysis]. Pure
 * (no I/O), so the tenure -> tier merge is unit-testable without a fake
 * network — only the surrounding [MachineAnalysisEngine.analyze] needs one.
 * Mirrors the row-mapping closure in iOS's `analyze()` (Z. 355-388).
 */
internal fun buildProductAnalyses(
    kpiRows: List<ProductKpiRow>,
    trays: List<Tray>,
    catalogue: List<Product>,
    days: Int,
    now: Instant,
    sharedSuggestions: List<Suggestion>,
): List<ProductAnalysis> {
    val trayIdsByProduct = mutableMapOf<String, MutableList<String>>()
    for (tray in trays) {
        val productId = tray.productId ?: continue
        trayIdsByProduct.getOrPut(productId) { mutableListOf() }.add(tray.id)
    }
    val imageByProduct = catalogue.associate { it.id to it.imagePath }

    return kpiRows.map { row ->
        val capacity = row.totalCapacity ?: 0
        val stock = row.totalStock ?: 0
        val sellThrough = if (capacity > 0 && days > 0) {
            minOf((row.unitsSold.toDouble() / (capacity.toDouble() * days / 7.0)) * 100.0, 100.0)
        } else {
            0.0
        }
        val avgDaily = if (days > 0) row.unitsSold.toDouble() / days else 0.0
        val daysUntilEmpty: Int? = when {
            row.unitsSold > 0 && stock > 0 -> round(stock.toDouble() / (row.unitsSold.toDouble() / days)).toInt()
            stock == 0 -> 0
            else -> null
        }
        val tenureDays = row.offeredSince?.let { (now - it).inWholeDays.toInt() }

        val tier = MachineAnalysis.scoreProduct(
            unitsSold = row.unitsSold,
            sellThroughPct = sellThrough,
            tenureDays = tenureDays,
        )

        ProductAnalysis(
            productId = row.productId,
            name = row.productName ?: "Unknown",
            imagePath = imageByProduct[row.productId],
            slots = row.slots,
            trayIds = trayIdsByProduct[row.productId] ?: emptyList(),
            unitsSold = row.unitsSold,
            revenueEur = row.revenueEur,
            totalCapacity = capacity,
            totalStock = stock,
            sellThroughPct = round(sellThrough * 10) / 10,
            avgDailyUnits = round(avgDaily * 100) / 100,
            daysUntilEmpty = daysUntilEmpty,
            tenureDays = tenureDays,
            tier = tier,
            suggestions = if (tier == SlotTier.DEAD || tier == SlotTier.WEAK) {
                sharedSuggestions.filter { it.productId != row.productId }
            } else {
                emptyList()
            },
        )
    }
}

/**
 * Analysis state machine, deliberately independent of
 * [androidx.lifecycle.ViewModel]/`viewModelScope` — same rationale as
 * `DashboardEngine`: plain JUnit + `runBlocking` against a fake
 * [MachineAnalysisDataSource], no Robolectric, no `kotlinx-coroutines-test`.
 */
internal class MachineAnalysisEngine(private val repository: MachineAnalysisDataSource) {
    private val _uiState = MutableStateFlow(MachineAnalysisUiState())
    val uiState: StateFlow<MachineAnalysisUiState> = _uiState.asStateFlow()

    private var lastMachineId: String? = null
    private var lastSlots: List<AnalysisGridSlot> = emptyList()

    /**
     * [trays] and [catalogue] are the machine's already-loaded trays and the
     * (non-discontinued) product catalogue — both already live in
     * `MachineDetailViewModel`, so this avoids re-fetching them.
     */
    suspend fun analyze(
        machineId: String,
        trays: List<Tray>,
        catalogue: List<Product>,
        windowDays: Int? = null,
        now: Instant = Clock.System.now(),
    ) {
        lastMachineId = machineId
        val days = windowDays ?: _uiState.value.days
        _uiState.update { it.copy(isLoading = true, error = null, days = days) }

        try {
            val companyId = repository.fetchCompanyId()

            coroutineScope {
                val kpiDeferred = async { repository.fetchProductKpis(machineId, companyId, days) }
                val velocityDeferred = async { repository.fetchVelocity(companyId, days) }
                val kpiRows = kpiDeferred.await()
                val velocity = velocityDeferred.await()

                val productsInMachine = trays.mapNotNull { it.productId }.toSet()
                val pool = MachineAnalysis.buildSuggestionPool(
                    products = catalogue,
                    velocity = velocity,
                    productsInMachine = productsInMachine,
                )
                val sharedSuggestions = pool.bestsellers.take(3) + pool.newcomers.take(2)

                val analyses = buildProductAnalyses(kpiRows, trays, catalogue, days, now, sharedSuggestions)

                val tierByProduct = analyses.associate { it.productId to ProductTierInfo(it.tier, it.sellThroughPct) }
                val slots = MachineAnalysis.buildGridSlots(trays, tierByProduct)
                val rowCount = slots.maxOfOrNull { it.row + 1 } ?: 0
                lastSlots = slots

                _uiState.update {
                    it.copy(
                        products = analyses,
                        slots = slots,
                        rowCount = rowCount,
                        fillSuggestions = sharedSuggestions,
                        isLoading = false,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    /**
     * Swap the product assigned to a slot. Resets stock to 0 (the old
     * product is physically removed); the caller is responsible for
     * reloading trays and re-running [analyze] afterwards — this only
     * performs the write, same contract as iOS's `applySwap`.
     */
    suspend fun applySwap(trayId: String, productId: String): Boolean {
        return try {
            repository.updateTrayProduct(trayId, productId)
            logSwapBestEffort(trayId, productId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message) }
            false
        }
    }

    /** Best-effort audit log entry — a logging failure must never undo an already-successful swap. */
    private suspend fun logSwapBestEffort(trayId: String, productId: String) {
        val machineId = lastMachineId ?: return
        val oldSlot = lastSlots.firstOrNull { it.trayId == trayId }
        try {
            repository.logProductSwap(
                SwapLogContext(
                    machineId = machineId,
                    trayId = trayId,
                    itemNumber = oldSlot?.itemNumber,
                    newProductId = productId,
                    oldProductId = oldSlot?.productId,
                    oldProductName = oldSlot?.productName,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-fatal — the swap itself already succeeded.
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

class MachineAnalysisViewModel @JvmOverloads constructor(
    repository: MachineAnalysisDataSource = MachineAnalysisRepository,
) : ViewModel() {
    private val engine = MachineAnalysisEngine(repository)
    val uiState: StateFlow<MachineAnalysisUiState> = engine.uiState

    fun analyze(machineId: String, trays: List<Tray>, catalogue: List<Product>, windowDays: Int? = null) {
        viewModelScope.launch { engine.analyze(machineId, trays, catalogue, windowDays) }
    }

    fun applySwap(trayId: String, productId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(engine.applySwap(trayId, productId)) }
    }

    fun clearError() = engine.clearError()
}
