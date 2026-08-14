package xyz.vmflow.data

import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * Pure, non-Android warehouse intake logic: the quantity-expression
 * evaluator and expiration-severity classifier used by the "Lager" module's
 * intake form, plus the in-memory product/batch aggregation that feeds the
 * stock overview. Ported 1:1 from iOS:
 *   - [evaluateQuantityExpression] <- `ios/VMflow/Views/Warehouse/WarehouseView.swift`
 *     `evaluateExpression` (~L522-555)
 *   - [expirationStatus] <- `ios/VMflow/Models/Warehouse.swift`
 *     `WarehouseProductSummary.expirationStatus(for:)` (~L69-84)
 *   - [buildProductSummaries] <- `ios/VMflow/ViewModels/WarehouseViewModel.swift`
 *     `loadProductSummaries()`'s in-memory aggregation (~L141-216)
 *
 * Nothing here touches the network or Android framework, so it is trusted
 * to unit test cleanly and agree with iOS bit-for-bit — same split as
 * `MachineAnalysis.kt` / `DeviceHealth.kt`.
 */

/**
 * Expiration severity. Mirrors iOS `ExpirationStatus`. Declared top-level
 * (sibling of [WarehouseIntakeLogic], not nested inside it) so its fully
 * qualified name is `xyz.vmflow.data.ExpirationStatus` — the same shape as
 * [SlotTier] in `MachineAnalysis.kt`, which is also a derived enum meant to
 * be consumed outside the `data` layer that produces it. See the handoff
 * note on [WarehouseIntakeLogic.WarehouseProductSummary] for how Task 2
 * should import this.
 */
enum class ExpirationStatus { OK, WARNING, CRITICAL }

object WarehouseIntakeLogic {

    /**
     * Evaluates a user-typed quantity expression like "2*12" so operators can
     * key in "case size * count" instead of doing the multiplication by hand
     * before booking a stock intake.
     *
     * DOCUMENTED FINDING: the iOS source comments this function as doing
     * chained operations left-to-right, with no operator precedence beyond
     * multiply/divide/plus/minus. That comment is wrong. The actual iOS
     * implementation hands the cleaned string straight to
     * `NSExpression(format:)`, which parses and evaluates it as a real
     * arithmetic expression with standard operator precedence — multiply
     * and divide bind tighter than plus and minus. Concretely, "2+3*4"
     * evaluates to 14 (2 + (3*4)), not 20 (a left-to-right reading would
     * give ((2+3)*4)). This port deliberately follows NSExpression's actual
     * behavior via a small recursive-descent evaluator with the standard
     * precedence, not the misleading source comment, so Android agrees with
     * production iOS on every input.
     */
    fun evaluateQuantityExpression(text: String): Int? {
        val cleaned = text.replace("×", "*").replace("x", "*").replace(" ", "")
        if (cleaned.isEmpty()) return null

        // Pure integer (including "0" and "-5") short-circuits straight to
        // the >0 check below, same as Swift's `Int(cleaned)` fast path.
        cleaned.toIntOrNull()?.let { whole ->
            return if (whole > 0) whole else null
        }

        val allowedChars = "0123456789+-*/."
        if (cleaned.any { it !in allowedChars }) return null
        // Must not end on an operator or a bare '.' — mirrors iOS's
        // `guard let lastChar = cleaned.last, lastChar.isNumber`.
        if (!cleaned.last().isDigit()) return null

        val evaluated = evaluateWithPrecedence(cleaned) ?: return null
        val rounded = evaluated.roundToInt()
        return if (rounded > 0) rounded else null
    }

    /**
     * Recursive-descent evaluator implementing standard precedence:
     * `expression := term (('+'|'-') term)*`, `term := factor (('*'|'/') factor)*`,
     * `factor := digits with an optional single '.'`. This is what gives
     * `*`/`/` real precedence over `+`/`-`, matching NSExpression (see
     * [evaluateQuantityExpression] doc above).
     *
     * A unary minus immediately after an operator (e.g. "2*-3") is not
     * supported: factor parsing requires the next character to be a digit,
     * so a '-' there fails to parse and the whole expression evaluates to
     * null. This is a deliberate, conservative choice for an edge case the
     * task brief explicitly left to judgment — NSExpression's exact
     * behavior for a leading unary minus inside a sub-expression isn't
     * verifiable without a Mac, and "no result" is the safe fallback for a
     * quantity input field.
     */
    private fun evaluateWithPrecedence(cleaned: String): Double? {
        val cursor = ExpressionCursor(cleaned)
        val result = cursor.parseExpression() ?: return null
        return if (cursor.atEnd()) result else null
    }

    private class ExpressionCursor(private val s: String) {
        private var pos = 0
        fun atEnd() = pos >= s.length

        fun parseExpression(): Double? {
            var value = parseTerm() ?: return null
            while (!atEnd() && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos]
                pos++
                val rhs = parseTerm() ?: return null
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double? {
            var value = parseFactor() ?: return null
            while (!atEnd() && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos]
                pos++
                val rhs = parseFactor() ?: return null
                if (op == '/' && rhs == 0.0) return null
                value = if (op == '*') value * rhs else value / rhs
            }
            return value
        }

        private fun parseFactor(): Double? {
            val start = pos
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) return null
            return s.substring(start, pos).toDoubleOrNull()
        }
    }

    /**
     * Classifies a `yyyy-MM-dd` expiration date against [today]. Boundaries
     * match iOS `WarehouseProductSummary.expirationStatus(for:)` literally:
     * fewer than 7 days away — including already-expired, i.e. a negative
     * day count — is CRITICAL; up to and including 30 days is WARNING;
     * beyond that is OK. A null or unparsable date is treated as OK, same
     * as iOS: a batch without a usable date shouldn't visually alarm the
     * operator.
     */
    fun expirationStatus(dateIso: String?, today: LocalDate): ExpirationStatus {
        if (dateIso == null) return ExpirationStatus.OK
        val expiration = try {
            LocalDate.parse(dateIso)
        } catch (_: Exception) {
            return ExpirationStatus.OK
        }
        val daysUntil = expiration.toEpochDays() - today.toEpochDays()
        return when {
            daysUntil < 7 -> ExpirationStatus.CRITICAL
            daysUntil <= 30 -> ExpirationStatus.WARNING
            else -> ExpirationStatus.OK
        }
    }

    /** One `products` row as input to [buildProductSummaries]. */
    data class ProductSummaryInput(
        val productId: String,
        val name: String?,
        val imagePath: String?,
        val discontinued: Boolean,
    )

    /** One `warehouse_stock_batches` row as input to [buildProductSummaries]. */
    data class BatchSummaryInput(
        val productId: String,
        val quantity: Int,
        val expirationDate: String?,
    )

    /**
     * Placeholder for Task 2's `xyz.vmflow.models.WarehouseProductSummary`.
     * Task 2 was not yet written when this task landed, so [buildProductSummaries]
     * returns this local type instead. Field names and types match the real
     * model 1:1 (see `ios/VMflow/Models/Warehouse.swift` `WarehouseProductSummary`),
     * so wiring it up in Task 2 is a pure rename/move — delete this data
     * class, add the equivalent one to `models/Models.kt`, and change this
     * file's import; no logic in [buildProductSummaries] needs to change.
     *
     * The `expirationStatus` field's type, [xyz.vmflow.data.ExpirationStatus],
     * is ALREADY top-level in this file (not nested inside
     * [WarehouseIntakeLogic]) specifically so it's importable from other
     * layers. When Task 2 adds the real `WarehouseProductSummary` to
     * `models/Models.kt`, its `expirationStatus` field should import and use
     * `xyz.vmflow.data.ExpirationStatus` directly
     * (`import xyz.vmflow.data.ExpirationStatus`) — do NOT re-nest it or
     * duplicate it inside `models`. This matches the established precedent
     * for derived enums produced by the `data` layer and consumed elsewhere:
     * `SlotTier` in `MachineAnalysis.kt` is declared top-level in that file
     * for the exact same reason and is imported the same way by its
     * consumers.
     */
    data class WarehouseProductSummary(
        val productId: String,
        val name: String,
        val imagePath: String?,
        val totalQuantity: Int,
        val batchCount: Int,
        val earliestExpiration: String?,
        val discontinued: Boolean,
        val expirationStatus: ExpirationStatus,
    )

    /**
     * Builds one summary per product — including zero-stock and discontinued
     * products, no filtering here; that happens later in the UI/ViewModel
     * layer (Task 9), same as iOS `loadProductSummaries()`. [batches] are
     * grouped by `productId`: total quantity is their sum, batch count is
     * how many there are, and earliest expiration is the smallest non-null
     * `expirationDate` (plain string comparison is safe because `yyyy-MM-dd`
     * sorts lexicographically). [expirationStatus] is derived from that
     * earliest date. A null or empty product name falls back to the
     * (deliberately non-localized) literal "Unknown", matching iOS's own
     * `p.name ?? "Unknown"` verbatim (`WarehouseViewModel.swift:202`).
     * Output order follows [products]' input order — no sorting here either.
     */
    fun buildProductSummaries(
        products: List<ProductSummaryInput>,
        batches: List<BatchSummaryInput>,
        today: LocalDate,
    ): List<WarehouseProductSummary> {
        data class BatchAggregate(var totalQuantity: Int = 0, var batchCount: Int = 0, var earliestExpiration: String? = null)

        val aggregateByProduct = mutableMapOf<String, BatchAggregate>()
        for (batch in batches) {
            val aggregate = aggregateByProduct.getOrPut(batch.productId) { BatchAggregate() }
            aggregate.totalQuantity += batch.quantity
            aggregate.batchCount += 1
            val expiration = batch.expirationDate
            if (expiration != null) {
                val currentEarliest = aggregate.earliestExpiration
                if (currentEarliest == null || expiration < currentEarliest) {
                    aggregate.earliestExpiration = expiration
                }
            }
        }

        return products.map { product ->
            val aggregate = aggregateByProduct[product.productId]
            val earliestExpiration = aggregate?.earliestExpiration
            WarehouseProductSummary(
                productId = product.productId,
                name = product.name?.takeIf { it.isNotEmpty() } ?: "Unknown",
                imagePath = product.imagePath,
                totalQuantity = aggregate?.totalQuantity ?: 0,
                batchCount = aggregate?.batchCount ?: 0,
                earliestExpiration = earliestExpiration,
                discontinued = product.discontinued,
                expirationStatus = expirationStatus(earliestExpiration, today),
            )
        }
    }
}
