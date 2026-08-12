package xyz.vmflow.data

import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

/**
 * Product-centric machine performance analysis — ported 1:1 from the "Pure
 * helpers" section of `ios/VMflow/ViewModels/MachineAnalysisViewModel.swift`
 * (lines 111-213), which itself mirrors the web's `useMachineAnalysis.ts`.
 * Performance is a property of the PRODUCT, not the slot: sales are
 * aggregated across every slot a product occupies, and its tenure survives
 * being moved between trays (`machine_product_offerings`).
 *
 * The slot grid layout math (row/column/width) mirrors
 * `ios/VMflow/Views/Refill/MachineLayoutGrid.swift` exactly:
 *   row    = max(0, floor(item_number / 10) - 1)
 *   column = item_number % 10
 *   width  = gap to the next occupied slot in the row.
 *
 * Nothing in this file touches Android, Compose, or the network — it is
 * pure data transformation so it can be unit-tested and trusted to agree
 * with iOS bit-for-bit on every boundary case.
 */

/** Worst-first severity ordering matches `SlotTier.severity` on iOS. */
enum class SlotTier {
    DEAD, WEAK, TESTING, OK, STRONG, EMPTY;

    val severity: Int
        get() = when (this) {
            DEAD -> 0
            WEAK -> 1
            TESTING -> 2
            OK -> 3
            STRONG -> 4
            EMPTY -> 5
        }
}

/** Thresholds for [MachineAnalysis.scoreProduct]. Matches iOS `ScoreOpts`. */
data class ScoreOpts(
    val gracePeriodDays: Int = 14,
    val weakSellThrough: Double = 15.0,
    val strongSellThrough: Double = 40.0,
)

/** A flat item_number's position in the layout grid. */
data class SlotPosition(val row: Int, val column: Int)

enum class SuggestionKind { BESTSELLER, NEWCOMER }

data class Suggestion(
    val productId: String,
    val name: String,
    val imagePath: String?,
    val kind: SuggestionKind,
    /** Fleet-wide avg daily units (0 for newcomers). */
    val velocity: Double,
)

data class SuggestionPool(
    val bestsellers: List<Suggestion>,
    val newcomers: List<Suggestion>,
)

/** A product's classification result, keyed by product id for [MachineAnalysis.buildGridSlots]. */
data class ProductTierInfo(val tier: SlotTier, val sellThroughPct: Double)

/** A single cell in the rendered machine layout grid, coloured by its product's tier. */
data class AnalysisGridSlot(
    val trayId: String,
    val itemNumber: Int,
    val row: Int,
    val column: Int,
    val width: Int,
    val productId: String?,
    val productName: String?,
    val imagePath: String?,
    val tier: SlotTier,
    val sellThroughPct: Double,
)

object MachineAnalysis {

    const val COLUMNS_PER_ROW = 10

    /**
     * Map a flat item_number to its (row, column) grid position — matches
     * `MachineLayoutGrid.swift`'s layout math exactly.
     */
    fun slotRowCol(itemNumber: Int): SlotPosition =
        SlotPosition(
            row = maxOf(0, itemNumber / COLUMNS_PER_ROW - 1),
            column = ((itemNumber % COLUMNS_PER_ROW) + COLUMNS_PER_ROW) % COLUMNS_PER_ROW,
        )

    /**
     * Physical width (in columns) of each slot: from its own column to the
     * next occupied slot in the same row; the last slot in a row stretches
     * to the row end. Gaps in the item_number sequence widen the preceding
     * slot — the numbering encodes the physical shelf width.
     */
    fun computeSlotWidths(items: List<Int>): Map<Int, Int> {
        val byRow = mutableMapOf<Int, MutableList<Int>>()
        for (item in items) {
            val (row, _) = slotRowCol(item)
            byRow.getOrPut(row) { mutableListOf() }.add(item)
        }
        val widths = mutableMapOf<Int, Int>()
        for (rowItems in byRow.values) {
            val sorted = rowItems.sorted()
            for (i in sorted.indices) {
                val item = sorted[i]
                val (_, column) = slotRowCol(item)
                val next = if (i + 1 < sorted.size) sorted[i + 1] else null
                val width = next?.let { it - item } ?: (COLUMNS_PER_ROW - column)
                widths[item] = maxOf(1, minOf(COLUMNS_PER_ROW - column, width))
            }
        }
        return widths
    }

    /**
     * Classify a product's performance. A product offered for fewer than the
     * grace period that would otherwise score dead/weak is surfaced as
     * "testing" instead, so it isn't condemned before it has had a fair
     * chance. The grace period only ever rescues DEAD or WEAK — a
     * well-performing new product (e.g. OK/STRONG) is never relabeled as
     * "still testing", since that would hide a genuinely fine product behind
     * a "wait and see" badge.
     */
    fun scoreProduct(
        unitsSold: Int,
        sellThroughPct: Double,
        tenureDays: Int?,
        opts: ScoreOpts = ScoreOpts(),
    ): SlotTier {
        val base = when {
            unitsSold <= 0 -> SlotTier.DEAD
            sellThroughPct < opts.weakSellThrough -> SlotTier.WEAK
            sellThroughPct < opts.strongSellThrough -> SlotTier.OK
            else -> SlotTier.STRONG
        }

        if ((base == SlotTier.DEAD || base == SlotTier.WEAK) &&
            tenureDays != null && tenureDays < opts.gracePeriodDays
        ) {
            return SlotTier.TESTING
        }
        return base
    }

    /**
     * Build the shared pool of replacement candidates for a machine: proven
     * fleet-wide bestsellers not yet in this machine, and never-sold
     * newcomers (test candidates). Discontinued products are assumed
     * already excluded from [products] by the caller.
     */
    fun buildSuggestionPool(
        products: List<Product>,
        velocity: Map<String, Double>,
        productsInMachine: Set<String>,
        maxBestsellers: Int = 5,
        maxNewcomers: Int = 5,
    ): SuggestionPool {
        val eligible = products.filter { it.id !in productsInMachine }

        val bestsellers = eligible
            .mapNotNull { p ->
                val v = velocity[p.id]
                if (v != null && v > 0) p to v else null
            }
            .sortedByDescending { it.second }
            .take(maxBestsellers)
            .map { (p, v) ->
                Suggestion(
                    productId = p.id,
                    name = p.name ?: "Unknown",
                    imagePath = p.imagePath,
                    kind = SuggestionKind.BESTSELLER,
                    velocity = v,
                )
            }

        val newcomers = eligible
            .filter { (velocity[it.id] ?: 0.0) <= 0 }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            .take(maxNewcomers)
            .map { p ->
                Suggestion(
                    productId = p.id,
                    name = p.name ?: "Unknown",
                    imagePath = p.imagePath,
                    kind = SuggestionKind.NEWCOMER,
                    velocity = 0.0,
                )
            }

        return SuggestionPool(bestsellers = bestsellers, newcomers = newcomers)
    }

    /** Build the layout grid cells, colouring each slot by its product's tier. */
    fun buildGridSlots(
        trays: List<Tray>,
        tierByProduct: Map<String, ProductTierInfo>,
    ): List<AnalysisGridSlot> {
        val widths = computeSlotWidths(trays.map { it.itemNumber })
        return trays.map { tray ->
            val (row, column) = slotRowCol(tray.itemNumber)
            val info = tray.productId?.let { tierByProduct[it] }
            AnalysisGridSlot(
                trayId = tray.id,
                itemNumber = tray.itemNumber,
                row = row,
                column = column,
                width = widths[tray.itemNumber] ?: 1,
                productId = tray.productId,
                productName = tray.products?.name,
                imagePath = tray.products?.imagePath,
                tier = if (tray.productId != null) (info?.tier ?: SlotTier.EMPTY) else SlotTier.EMPTY,
                sellThroughPct = info?.sellThroughPct ?: 0.0,
            )
        }
    }
}
