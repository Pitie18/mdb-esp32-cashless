package xyz.vmflow.data

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.vmflow.data.WarehouseIntakeLogic.BatchSummaryInput
import xyz.vmflow.data.ExpirationStatus
import xyz.vmflow.data.WarehouseIntakeLogic.ProductSummaryInput
import xyz.vmflow.data.WarehouseIntakeLogic.evaluateQuantityExpression
import xyz.vmflow.data.WarehouseIntakeLogic.expirationStatus

/**
 * Pure logic ported from:
 *   - `ios/VMflow/Views/Warehouse/WarehouseView.swift` `evaluateExpression` (~L522-555)
 *   - `ios/VMflow/Models/Warehouse.swift` `WarehouseProductSummary.expirationStatus(for:)` (~L69-84)
 *   - `ios/VMflow/ViewModels/WarehouseViewModel.swift` `loadProductSummaries()` (~L141-216)
 */
class WarehouseIntakeLogicTest {

    // ─── evaluateQuantityExpression ──────────────────────────────────────

    @Test
    fun `pure number returns itself`() {
        assertEquals(12, evaluateQuantityExpression("12"))
    }

    @Test
    fun `multiplication`() {
        assertEquals(24, evaluateQuantityExpression("2*12"))
    }

    @Test
    fun `addition`() {
        assertEquals(150, evaluateQuantityExpression("100+50"))
    }

    @Test
    fun `subtraction`() {
        assertEquals(8, evaluateQuantityExpression("10-2"))
    }

    @Test
    fun `division`() {
        assertEquals(8, evaluateQuantityExpression("48/6"))
    }

    @Test
    fun `multiplication sign is normalized to asterisk`() {
        assertEquals(6, evaluateQuantityExpression("2×3"))
    }

    @Test
    fun `lowercase x is normalized to asterisk`() {
        assertEquals(6, evaluateQuantityExpression("2x3"))
    }

    @Test
    fun `internal and surrounding spaces are stripped`() {
        assertEquals(6, evaluateQuantityExpression("  2 * 3  "))
    }

    @Test
    fun `multiplication binds tighter than addition, not left-to-right`() {
        // 2+3*4 must be 2+(3*4)=14, NOT ((2+3)*4)=20. This is the documented
        // finding: the iOS source comment claims "left-to-right" evaluation,
        // but the implementation actually delegates to NSExpression, which
        // applies real operator precedence. Android must match the real
        // (NSExpression) behavior, not the misleading comment.
        assertEquals(14, evaluateQuantityExpression("2+3*4"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(evaluateQuantityExpression(""))
    }

    @Test
    fun `non-numeric garbage returns null`() {
        assertNull(evaluateQuantityExpression("abc"))
    }

    @Test
    fun `trailing operator returns null`() {
        assertNull(evaluateQuantityExpression("2+"))
    }

    @Test
    fun `double operator returns null`() {
        assertNull(evaluateQuantityExpression("2++2"))
    }

    @Test
    fun `zero result returns null because result must be greater than zero`() {
        assertNull(evaluateQuantityExpression("0"))
    }

    @Test
    fun `negative pure number returns null`() {
        assertNull(evaluateQuantityExpression("-5"))
    }

    @Test
    fun `unary minus right after an operator is treated conservatively as invalid`() {
        // "2*-3" is an ambiguous edge case the brief explicitly leaves to
        // judgment (real NSExpression behavior isn't verifiable without a
        // Mac). This port's recursive-descent factor parser requires a
        // digit immediately after an operator, so "-" there fails to parse
        // as a factor and the whole expression evaluates to null — the safe
        // choice for a quantity input field.
        assertNull(evaluateQuantityExpression("2*-3"))
    }

    // ─── expirationStatus ────────────────────────────────────────────────

    private val today = LocalDate(2026, 8, 14)

    @Test
    fun `null date is OK`() {
        assertEquals(ExpirationStatus.OK, expirationStatus(null, today))
    }

    @Test
    fun `40 days in the future is OK`() {
        assertEquals(ExpirationStatus.OK, expirationStatus("2026-09-23", today))
    }

    @Test
    fun `exactly 30 days is WARNING`() {
        assertEquals(ExpirationStatus.WARNING, expirationStatus("2026-09-13", today))
    }

    @Test
    fun `31 days is OK because the boundary is less-or-equal 30`() {
        assertEquals(ExpirationStatus.OK, expirationStatus("2026-09-14", today))
    }

    @Test
    fun `exactly 7 days is still WARNING because the critical boundary is strictly less than 7`() {
        assertEquals(ExpirationStatus.WARNING, expirationStatus("2026-08-21", today))
    }

    @Test
    fun `6 days is CRITICAL`() {
        assertEquals(ExpirationStatus.CRITICAL, expirationStatus("2026-08-20", today))
    }

    @Test
    fun `0 days (expires today) is CRITICAL`() {
        assertEquals(ExpirationStatus.CRITICAL, expirationStatus("2026-08-14", today))
    }

    @Test
    fun `already expired 5 days ago is CRITICAL`() {
        assertEquals(ExpirationStatus.CRITICAL, expirationStatus("2026-08-09", today))
    }

    @Test
    fun `unparsable date string is OK`() {
        assertEquals(ExpirationStatus.OK, expirationStatus("not-a-date", today))
    }

    // ─── buildProductSummaries ───────────────────────────────────────────

    @Test
    fun `product without batches gets a zero-stock OK summary`() {
        val products = listOf(ProductSummaryInput("p1", "Cola", "img/p1.png", discontinued = false))
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, emptyList(), today)

        assertEquals(1, summaries.size)
        val s = summaries[0]
        assertEquals("p1", s.productId)
        assertEquals(0, s.totalQuantity)
        assertEquals(0, s.batchCount)
        assertNull(s.earliestExpiration)
        assertEquals(ExpirationStatus.OK, s.expirationStatus)
    }

    @Test
    fun `two batches of the same product are summed into one entry`() {
        val products = listOf(ProductSummaryInput("p1", "Cola", null, discontinued = false))
        val batches = listOf(
            BatchSummaryInput("p1", quantity = 10, expirationDate = "2026-12-01"),
            BatchSummaryInput("p1", quantity = 5, expirationDate = "2026-11-01"),
        )
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, batches, today)

        assertEquals(1, summaries.size)
        assertEquals(15, summaries[0].totalQuantity)
        assertEquals(2, summaries[0].batchCount)
    }

    @Test
    fun `earliest expiration is picked correctly even when batches arrive out of chronological order`() {
        val products = listOf(ProductSummaryInput("p1", "Cola", null, discontinued = false))
        val batches = listOf(
            BatchSummaryInput("p1", quantity = 3, expirationDate = "2026-12-01"),
            BatchSummaryInput("p1", quantity = 3, expirationDate = "2026-09-01"),
            BatchSummaryInput("p1", quantity = 3, expirationDate = "2026-10-15"),
        )
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, batches, today)

        assertEquals("2026-09-01", summaries[0].earliestExpiration)
    }

    @Test
    fun `null product name falls back to Unknown`() {
        val products = listOf(ProductSummaryInput("p1", null, null, discontinued = false))
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, emptyList(), today)

        assertEquals("Unknown", summaries[0].productName)
    }

    @Test
    fun `discontinued flag is passed through unchanged`() {
        val products = listOf(
            ProductSummaryInput("p1", "Cola", null, discontinued = true),
            ProductSummaryInput("p2", "Fanta", null, discontinued = false),
        )
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, emptyList(), today)

        assertEquals(true, summaries[0].discontinued)
        assertEquals(false, summaries[1].discontinued)
    }

    @Test
    fun `output order follows the input products order, no sorting`() {
        val products = listOf(
            ProductSummaryInput("p3", "Zzz", null, discontinued = false),
            ProductSummaryInput("p1", "Aaa", null, discontinued = false),
            ProductSummaryInput("p2", "Mmm", null, discontinued = false),
        )
        val summaries = WarehouseIntakeLogic.buildProductSummaries(products, emptyList(), today)

        assertEquals(listOf("p3", "p1", "p2"), summaries.map { it.productId })
    }
}
