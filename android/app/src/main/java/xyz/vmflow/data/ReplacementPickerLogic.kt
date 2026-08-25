package xyz.vmflow.data

import java.text.Collator
import xyz.vmflow.models.Product
import xyz.vmflow.models.ProductCategory
import xyz.vmflow.models.Tray

/**
 * Top-level grouping of the replacement picker: does the warehouse still
 * hold this product? Mirrors iOS `ReplacementProductPicker.StockStatus`
 * (`ReplacementProductPicker.swift` L62-65).
 */
enum class PickerStockBucketKind { IN_STOCK, OUT_OF_STOCK }

/**
 * One candidate product in the picker, with everything the row needs
 * *except* localized text — resolving that is the UI's job, so this stays a
 * pure value.
 *
 * @property name raw `products.name`, still nullable: a missing name has to
 *   become a localized label (`R.string.product_unnamed`), which this layer
 *   cannot produce.
 * @property warehouseStock units left in the selected warehouse, or `null`
 *   when there is no warehouse stock context at all (none selected, or the
 *   stock load failed). `null` means "show no stock pill", never "zero" —
 *   iOS makes the same distinction with an optional `remainingStock`.
 * @property existingSlots slot numbers in *this machine* that already hold
 *   this product, ascending, excluding the slot being replaced. Empty for
 *   the overwhelming majority of products (one product per machine is the
 *   norm in real data), which is why the badge is nearly invisible in
 *   practice — that is correct, not a bug.
 */
data class PickerProductRow(
    val productId: String,
    val name: String?,
    val imagePath: String?,
    val warehouseStock: Int?,
    val existingSlots: List<Int>
)

/**
 * One category's products inside a stock bucket.
 *
 * @property categoryId `null` for the uncategorized group, which also
 *   absorbs products whose `category` UUID is not in the catalogue.
 * @property categoryName raw category name, nullable for the same reason as
 *   [PickerProductRow.name] — `null` here means "render the localized
 *   uncategorized label".
 * @property isCurrent the category of the product currently in the slot.
 *   Rendered first inside its bucket and marked.
 * @property key stable identity for Compose keys and collapse state; unique
 *   within a bucket.
 */
data class PickerCategoryGroup(
    val key: String,
    val categoryId: String?,
    val categoryName: String?,
    val isCurrent: Boolean,
    val rows: List<PickerProductRow>
)

/** One stock bucket with its category groups, in render order. */
data class PickerStockBucket(
    val kind: PickerStockBucketKind,
    val groups: List<PickerCategoryGroup>
) {
    val totalCount: Int get() = groups.sumOf { it.rows.size }
}

/**
 * The slot badge split into its two renderable parts, so the UI can build
 * "Fach 3, 7, 9 +2" from string resources instead of receiving a
 * pre-formatted English sentence. Ported from iOS `slotBadgeLabel`
 * (`ReplacementProductPicker.swift` L14-23), which formats the label
 * itself — Android cannot, because "Slot" is "Fach" in German.
 */
data class SlotBadgeParts(val shown: List<Int>, val overflow: Int)

/**
 * Pure grouping, sorting and search for the refill wizard's replacement
 * product picker (`ui/refill/ReplacementPickerSheet.kt`). Ported from
 * `ios/VMflow/Views/Refill/ReplacementProductPicker.swift`: the stock-bucket
 * pipeline (L101-147), `groupByCategory` (L152-255), `sortKey` (L170-177)
 * and `slotBadgeLabel` (L14-23).
 *
 * Lives here rather than in the composable for the reason this module has
 * already been bitten by twice: a comparator inside a `@Composable` cannot
 * be unit-tested, and a non-total order silently reshuffles rows between
 * recompositions. Nothing here touches Android, coroutines or a clock.
 */
object ReplacementPickerLogic {

    /** Slot numbers spelled out in the badge before it switches to "+N". */
    const val SLOT_BADGE_MAX_SHOWN = 3

    /** Group key for the uncategorized bucket — never a real category UUID. */
    const val UNCATEGORIZED_KEY = "uncategorized"

    /**
     * Subsequence match with a gap penalty: every character of [query] must
     * appear in [target] in order, and the score is the number of characters
     * skipped along the way (lower = tighter match). `null` = no match.
     *
     * Ported from iOS `fuzzyMatch` (`ReplacementProductPicker.swift`
     * L85-99). iOS adds `distance` under two mutually exclusive `if`s, which
     * is one addition either way — this is that single addition, not a
     * simplification of the behaviour.
     *
     * Both arguments must already be case-folded by the caller.
     *
     * Operates on UTF-16 code units where iOS operates on grapheme
     * clusters; the two can only disagree for names containing astral
     * characters (emoji, some scripts), where this may match where iOS
     * would not. It never affects *which* products can be found by ASCII
     * queries, only the exotic edge.
     */
    fun fuzzyScore(query: String, target: String): Int? {
        var score = 0
        var cursor = 0
        for (ch in query) {
            val found = target.indexOf(ch, startIndex = cursor)
            if (found < 0) return null
            score += found - cursor
            cursor = found + 1
        }
        return score
    }

    /**
     * The slot badge's contents: the first [SLOT_BADGE_MAX_SHOWN] slot
     * numbers ascending, plus how many were left out. `null` for an empty
     * input — the caller renders no badge at all, matching iOS's
     * "empty string means no pill".
     *
     * Sorts its input, so callers don't have to.
     */
    fun slotBadgeParts(slots: List<Int>): SlotBadgeParts? {
        if (slots.isEmpty()) return null
        val sorted = slots.sorted()
        if (sorted.size <= SLOT_BADGE_MAX_SHOWN) return SlotBadgeParts(sorted, 0)
        return SlotBadgeParts(
            shown = sorted.take(SLOT_BADGE_MAX_SHOWN),
            overflow = sorted.size - SLOT_BADGE_MAX_SHOWN
        )
    }

    /**
     * Slot numbers per product for one machine, excluding the slot being
     * replaced — iOS `ReviewStepView.existingSlots(forTrayId:)` (L316-331).
     * Ascending per product, so [slotBadgeParts] and the row's
     * accessibility label read the same order.
     */
    fun existingSlotsByProduct(
        trays: List<Tray>,
        excludeTrayId: String
    ): Map<String, List<Int>> {
        val result = LinkedHashMap<String, MutableList<Int>>()
        for (tray in trays) {
            if (tray.id == excludeTrayId) continue
            val productId = tray.productId ?: continue
            result.getOrPut(productId) { mutableListOf() }.add(tray.itemNumber)
        }
        return result.mapValues { (_, slots) -> slots.sorted() }
    }

    /**
     * The picker's whole visible structure: stock buckets, category groups
     * inside them, sorted rows inside those.
     *
     * Pipeline (iOS `stockBuckets`, L114-147):
     *  1. Filter by [query] — a fuzzy subsequence match keeping the score.
     *     A blank query keeps everything with score 0. A product with no
     *     name cannot match a non-blank query and drops out, as on iOS.
     *  2. Partition by stock: `0` → [PickerStockBucketKind.OUT_OF_STOCK],
     *     anything else (including unknown) → in stock.
     *  3. Group by category inside each bucket: the current category first,
     *     other named categories by name, uncategorized last.
     *  4. Sort rows inside each group — see below.
     *
     * **Row order is a total order**, which matters more here than
     * anywhere: `Map` iteration order is not a contract, and a picker whose
     * rows swap places between recompositions makes the driver tap the
     * wrong product. Keys, in order: products not already in this machine
     * first, then the fuzzy score (all-zero when the query is blank), then
     * the product name via [nameComparator], then `productId` — the last
     * key is what makes it total, because a [Collator] returns 0 for
     * strings it considers equal and two products may genuinely share a
     * name.
     *
     * Empty buckets are dropped, so the caller can suppress the bucket
     * headers when only one bucket survives.
     *
     * @param stockByProduct `productId -> units left in the warehouse`.
     *   Only read when [stockKnown]; a product missing from a known map
     *   counts as zero.
     * @param stockKnown whether warehouse stock loaded at all. `false`
     *   suppresses every stock pill and leaves a single in-stock bucket,
     *   rather than declaring the entire catalogue sold out.
     * @param currentCategoryId category of the product currently in the
     *   slot (`RefillViewModel.categoryIdOfCurrentProduct`). Rendered first
     *   in each bucket. A category id with no products in a bucket simply
     *   does not appear there.
     * @param nameComparator the name tiebreaker; defaults to a
     *   [Collator]-backed, case-insensitive, accent-aware comparator
     *   resolved fresh per call (never cached in a top-level `val`, so it
     *   follows the JVM's current default locale). Tests pin a locale by
     *   passing their own.
     */
    fun buildBuckets(
        products: List<Product>,
        categories: List<ProductCategory>,
        stockByProduct: Map<String, Int>,
        stockKnown: Boolean,
        existingSlotsByProduct: Map<String, List<Int>>,
        currentCategoryId: String?,
        query: String,
        nameComparator: Comparator<String?> = defaultProductNameComparator()
    ): List<PickerStockBucket> {
        // ── 1. search ────────────────────────────────────────────────────
        val trimmed = query.trim()
        val scored: List<ScoredProduct> = if (trimmed.isEmpty()) {
            products.map { ScoredProduct(it, 0) }
        } else {
            val needle = trimmed.lowercase()
            products.mapNotNull { product ->
                val name = product.name?.lowercase() ?: return@mapNotNull null
                val score = fuzzyScore(needle, name) ?: return@mapNotNull null
                ScoredProduct(product, score)
            }
        }

        // ── 2. stock buckets ─────────────────────────────────────────────
        val inStock = mutableListOf<ScoredProduct>()
        val outOfStock = mutableListOf<ScoredProduct>()
        for (entry in scored) {
            val stock = if (stockKnown) stockByProduct[entry.product.id] ?: 0 else null
            if (stock == 0) outOfStock.add(entry) else inStock.add(entry)
        }

        // ── 3./4. category groups per bucket ─────────────────────────────
        return listOf(
            PickerStockBucket(
                kind = PickerStockBucketKind.IN_STOCK,
                groups = groupByCategory(
                    scored = inStock,
                    categories = categories,
                    stockByProduct = stockByProduct,
                    stockKnown = stockKnown,
                    existingSlotsByProduct = existingSlotsByProduct,
                    currentCategoryId = currentCategoryId,
                    nameComparator = nameComparator
                )
            ),
            PickerStockBucket(
                kind = PickerStockBucketKind.OUT_OF_STOCK,
                groups = groupByCategory(
                    scored = outOfStock,
                    categories = categories,
                    stockByProduct = stockByProduct,
                    stockKnown = stockKnown,
                    existingSlotsByProduct = existingSlotsByProduct,
                    currentCategoryId = currentCategoryId,
                    nameComparator = nameComparator
                )
            )
        ).filter { it.groups.isNotEmpty() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // internals
    // ─────────────────────────────────────────────────────────────────────

    private data class ScoredProduct(val product: Product, val score: Int)

    /**
     * One bucket's products grouped into ordered categories. Ported from
     * iOS `groupByCategory` (L152-255), including its deliberate handling
     * of a `category` UUID that is not in the catalogue: those products fold
     * into the uncategorized group rather than disappearing (a failed
     * category fetch leaves `categories` empty, and dropping the whole
     * catalogue would leave the driver with an empty picker).
     */
    private fun groupByCategory(
        scored: List<ScoredProduct>,
        categories: List<ProductCategory>,
        stockByProduct: Map<String, Int>,
        stockKnown: Boolean,
        existingSlotsByProduct: Map<String, List<Int>>,
        currentCategoryId: String?,
        nameComparator: Comparator<String?>
    ): List<PickerCategoryGroup> {
        if (scored.isEmpty()) return emptyList()

        // Later duplicate wins, like iOS's `reduce(into:)` — a duplicated
        // id must not decide whether the picker renders at all.
        val categoryById = LinkedHashMap<String, ProductCategory>()
        for (category in categories) categoryById[category.id] = category

        // Only ids that actually resolve stay their own group; everything
        // else (null, or an unknown uuid) collapses onto the null key.
        val byCategory = LinkedHashMap<String?, MutableList<ScoredProduct>>()
        for (entry in scored) {
            val raw = entry.product.category
            val key = if (raw != null && categoryById.containsKey(raw)) raw else null
            byCategory.getOrPut(key) { mutableListOf() }.add(entry)
        }

        val rowComparator = compareBy<ScoredProduct> { entry ->
            existingSlotsByProduct[entry.product.id]?.isNotEmpty() == true
        }
            .thenBy { it.score }
            .thenBy(nameComparator) { it.product.name }
            // The key that makes this a total order — see [buildBuckets].
            .thenBy { it.product.id }

        fun rowsOf(entries: List<ScoredProduct>): List<PickerProductRow> =
            entries.sortedWith(rowComparator).map { entry ->
                PickerProductRow(
                    productId = entry.product.id,
                    name = entry.product.name,
                    imagePath = entry.product.imagePath,
                    warehouseStock = if (stockKnown) {
                        stockByProduct[entry.product.id] ?: 0
                    } else {
                        null
                    },
                    existingSlots = existingSlotsByProduct[entry.product.id]?.sorted()
                        ?: emptyList()
                )
            }

        val groups = mutableListOf<PickerCategoryGroup>()

        // Current category first.
        val currentEntries = currentCategoryId
            ?.takeIf { categoryById.containsKey(it) }
            ?.let { byCategory[it] }
        if (currentEntries != null && currentEntries.isNotEmpty()) {
            groups += PickerCategoryGroup(
                key = currentCategoryId,
                categoryId = currentCategoryId,
                categoryName = categoryById[currentCategoryId]?.name,
                isCurrent = true,
                rows = rowsOf(currentEntries)
            )
        }

        // Other named categories, by name then id — id again for totality,
        // since a Collator can call two different names equal.
        val otherIds = byCategory.keys
            .filterNotNull()
            .filter { it != currentCategoryId }
            .sortedWith(
                compareBy<String, String?>(nameComparator) { categoryById[it]?.name }
                    .thenBy { it }
            )
        for (id in otherIds) {
            val entries = byCategory[id] ?: continue
            if (entries.isEmpty()) continue
            groups += PickerCategoryGroup(
                key = id,
                categoryId = id,
                categoryName = categoryById[id]?.name,
                isCurrent = false,
                rows = rowsOf(entries)
            )
        }

        // Uncategorized last.
        val uncategorized = byCategory[null]
        if (uncategorized != null && uncategorized.isNotEmpty()) {
            groups += PickerCategoryGroup(
                key = UNCATEGORIZED_KEY,
                categoryId = null,
                categoryName = null,
                isCurrent = false,
                rows = rowsOf(uncategorized)
            )
        }

        return groups
    }

    /**
     * Locale-aware, case-insensitive name comparator, null last. Same
     * construction and the same reasoning as
     * `RefillTourLogic.defaultProductNameComparator` (which is private to
     * that object, hence the three duplicated lines rather than a touched
     * file): [Collator.SECONDARY] ignores case but keeps accents
     * significant, so "Öl" sorts next to "Ol" instead of behind "Zucker",
     * matching iOS's `localizedCaseInsensitiveCompare`.
     *
     * A [Collator] can return 0 for two genuinely different strings, so
     * every caller must keep an id tiebreaker after it.
     */
    private fun defaultProductNameComparator(): Comparator<String?> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return nullsLast(Comparator(collator::compare))
    }
}
