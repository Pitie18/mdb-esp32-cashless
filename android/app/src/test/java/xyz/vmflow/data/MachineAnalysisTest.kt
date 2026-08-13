package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Product
import xyz.vmflow.models.Tray

/**
 * Ported 1:1 from the "Pure helpers" section of
 * `ios/VMflow/ViewModels/MachineAnalysisViewModel.swift` (lines 111-213).
 * Boundary cases (exactly 15%, exactly 40%, tenure exactly 14) are the ones
 * that would silently make Android and iOS disagree about which products to
 * flag for replacement, so they're each pinned by a dedicated test.
 */
class MachineAnalysisTest {

    // ─── slotRowCol ──────────────────────────────────────────────────────

    @Test
    fun `item 0 is row 0 column 0`() {
        val pos = MachineAnalysis.slotRowCol(0)
        assertEquals(0, pos.row)
        assertEquals(0, pos.column)
    }

    @Test
    fun `item 5 is row 0 column 5`() {
        val pos = MachineAnalysis.slotRowCol(5)
        assertEquals(0, pos.row)
        assertEquals(5, pos.column)
    }

    @Test
    fun `item 11 is row 0 column 1`() {
        val pos = MachineAnalysis.slotRowCol(11)
        assertEquals(0, pos.row)
        assertEquals(1, pos.column)
    }

    @Test
    fun `item 15 is row 0 column 5`() {
        val pos = MachineAnalysis.slotRowCol(15)
        assertEquals(0, pos.row)
        assertEquals(5, pos.column)
    }

    @Test
    fun `item 20 is row 1 column 0`() {
        val pos = MachineAnalysis.slotRowCol(20)
        assertEquals(1, pos.row)
        assertEquals(0, pos.column)
    }

    @Test
    fun `item 25 is row 1 column 5`() {
        val pos = MachineAnalysis.slotRowCol(25)
        assertEquals(1, pos.row)
        assertEquals(5, pos.column)
    }

    @Test
    fun `item 99 is row 8 column 9`() {
        val pos = MachineAnalysis.slotRowCol(99)
        assertEquals(8, pos.row)
        assertEquals(9, pos.column)
    }

    @Test
    fun `an item below 10 never produces a negative row`() {
        for (item in 0..9) {
            assertTrue("item $item produced negative row", MachineAnalysis.slotRowCol(item).row >= 0)
        }
    }

    // ─── computeSlotWidths ───────────────────────────────────────────────

    @Test
    fun `a gapless row 10 through 19 gives every slot width 1`() {
        val widths = MachineAnalysis.computeSlotWidths((10..19).toList())
        for (item in 10..19) {
            assertEquals("item $item", 1, widths[item])
        }
    }

    @Test
    fun `a gap between items widens the preceding slot to the distance to the next item`() {
        val widths = MachineAnalysis.computeSlotWidths(listOf(10, 12, 15))
        assertEquals(2, widths[10])
        assertEquals(3, widths[12])
        assertEquals(5, widths[15])
    }

    @Test
    fun `a single item alone in its row stretches to the row end`() {
        val widths = MachineAnalysis.computeSlotWidths(listOf(15))
        assertEquals(5, widths[15])
    }

    @Test
    fun `the last column in a row alone gives width 1`() {
        val widths = MachineAnalysis.computeSlotWidths(listOf(19))
        assertEquals(1, widths[19])
    }

    @Test
    fun `items in different rows do not influence each other`() {
        val widths = MachineAnalysis.computeSlotWidths(listOf(10, 25))
        // Row 0 has only item 10 -> stretches to row end (width 10).
        assertEquals(10, widths[10])
        // Row 1 has only item 25 -> stretches to row end (col 5, width 5).
        assertEquals(5, widths[25])
    }

    @Test
    fun `empty input gives an empty map`() {
        val widths = MachineAnalysis.computeSlotWidths(emptyList())
        assertTrue(widths.isEmpty())
    }

    // ─── scoreProduct ────────────────────────────────────────────────────

    @Test
    fun `zero sold with no tenure is dead`() {
        assertEquals(SlotTier.DEAD, MachineAnalysis.scoreProduct(0, 0.0, null))
    }

    @Test
    fun `zero sold with tenure 5 is testing`() {
        assertEquals(SlotTier.TESTING, MachineAnalysis.scoreProduct(0, 0.0, 5))
    }

    @Test
    fun `zero sold with tenure 20 is dead`() {
        assertEquals(SlotTier.DEAD, MachineAnalysis.scoreProduct(0, 0.0, 20))
    }

    @Test
    fun `10 sold at 10 percent is weak`() {
        assertEquals(SlotTier.WEAK, MachineAnalysis.scoreProduct(10, 10.0, null))
    }

    @Test
    fun `10 sold at 10 percent with tenure 5 is testing`() {
        assertEquals(SlotTier.TESTING, MachineAnalysis.scoreProduct(10, 10.0, 5))
    }

    @Test
    fun `10 sold at 25 percent is ok`() {
        assertEquals(SlotTier.OK, MachineAnalysis.scoreProduct(10, 25.0, null))
    }

    @Test
    fun `10 sold at 25 percent with tenure 5 stays ok, the grace period only rescues dead and weak`() {
        assertEquals(SlotTier.OK, MachineAnalysis.scoreProduct(10, 25.0, 5))
    }

    @Test
    fun `10 sold at 60 percent is strong`() {
        assertEquals(SlotTier.STRONG, MachineAnalysis.scoreProduct(10, 60.0, null))
    }

    @Test
    fun `exactly 15 percent is ok, the weak boundary is strictly less than`() {
        assertEquals(SlotTier.OK, MachineAnalysis.scoreProduct(10, 15.0, null))
    }

    @Test
    fun `exactly 40 percent is strong, the strong boundary is strictly less than`() {
        assertEquals(SlotTier.STRONG, MachineAnalysis.scoreProduct(10, 40.0, null))
    }

    @Test
    fun `tenure exactly 14 no longer qualifies for the grace period`() {
        assertEquals(SlotTier.DEAD, MachineAnalysis.scoreProduct(0, 0.0, 14))
    }

    // ─── buildSuggestionPool ─────────────────────────────────────────────

    private fun product(id: String, name: String?, imagePath: String? = null) =
        Product(id = id, name = name, imagePath = imagePath)

    @Test
    fun `products already in the machine appear in neither list`() {
        val products = listOf(product("p1", "InMachine"), product("p2", "NotInMachine"))
        val pool = MachineAnalysis.buildSuggestionPool(
            products = products,
            velocity = mapOf("p1" to 5.0, "p2" to 3.0),
            productsInMachine = setOf("p1")
        )
        assertTrue(pool.bestsellers.none { it.productId == "p1" })
        assertTrue(pool.newcomers.none { it.productId == "p1" })
        assertEquals(listOf("p2"), pool.bestsellers.map { it.productId })
    }

    @Test
    fun `bestsellers are sorted descending by velocity`() {
        val products = listOf(product("p1", "Low"), product("p2", "High"), product("p3", "Mid"))
        val pool = MachineAnalysis.buildSuggestionPool(
            products = products,
            velocity = mapOf("p1" to 1.0, "p2" to 9.0, "p3" to 5.0),
            productsInMachine = emptySet()
        )
        assertEquals(listOf("p2", "p3", "p1"), pool.bestsellers.map { it.productId })
    }

    @Test
    fun `at most 5 bestsellers and 5 newcomers are returned`() {
        val bestsellerProducts = (1..8).map { product("bs$it", "Bestseller$it") }
        val newcomerProducts = (1..8).map { product("nc$it", "Newcomer$it") }
        val velocity = bestsellerProducts.associate { it.id to 1.0 } // all > 0
        val pool = MachineAnalysis.buildSuggestionPool(
            products = bestsellerProducts + newcomerProducts,
            velocity = velocity,
            productsInMachine = emptySet()
        )
        assertEquals(5, pool.bestsellers.size)
        assertEquals(5, pool.newcomers.size)
    }

    @Test
    fun `products with velocity 0 are newcomers, not bestsellers`() {
        val products = listOf(product("p1", "Zero"))
        val pool = MachineAnalysis.buildSuggestionPool(
            products = products,
            velocity = mapOf("p1" to 0.0),
            productsInMachine = emptySet()
        )
        assertTrue(pool.bestsellers.isEmpty())
        assertEquals(listOf("p1"), pool.newcomers.map { it.productId })
    }

    @Test
    fun `newcomers are sorted alphabetically case insensitive`() {
        val products = listOf(product("p1", "banana"), product("p2", "Apple"), product("p3", "cherry"))
        val pool = MachineAnalysis.buildSuggestionPool(
            products = products,
            velocity = emptyMap(),
            productsInMachine = emptySet()
        )
        assertEquals(listOf("p2", "p1", "p3"), pool.newcomers.map { it.productId })
    }

    @Test
    fun `a product without a name falls back to Unknown`() {
        val products = listOf(product("p1", null))
        val pool = MachineAnalysis.buildSuggestionPool(
            products = products,
            velocity = emptyMap(),
            productsInMachine = emptySet()
        )
        assertEquals("Unknown", pool.newcomers.first().name)
    }

    // ─── buildGridSlots ──────────────────────────────────────────────────

    private fun tray(id: String, itemNumber: Int, productId: String? = null, product: Product? = null) =
        Tray(id = id, machineId = "m1", itemNumber = itemNumber, productId = productId, products = product)

    @Test
    fun `a tray without a productId gets tier empty`() {
        val slots = MachineAnalysis.buildGridSlots(
            trays = listOf(tray("t1", 10, productId = null)),
            tierByProduct = emptyMap()
        )
        assertEquals(SlotTier.EMPTY, slots.single().tier)
    }

    @Test
    fun `a tray whose product has no tierByProduct entry also gets tier empty`() {
        val slots = MachineAnalysis.buildGridSlots(
            trays = listOf(tray("t1", 10, productId = "p1")),
            tierByProduct = emptyMap()
        )
        assertEquals(SlotTier.EMPTY, slots.single().tier)
    }

    @Test
    fun `grid slot widths come from computeSlotWidths`() {
        val slots = MachineAnalysis.buildGridSlots(
            trays = listOf(tray("t1", 10), tray("t2", 12), tray("t3", 15)),
            tierByProduct = emptyMap()
        )
        assertEquals(2, slots.first { it.itemNumber == 10 }.width)
        assertEquals(3, slots.first { it.itemNumber == 12 }.width)
        assertEquals(5, slots.first { it.itemNumber == 15 }.width)
    }

    @Test
    fun `grid slot row and column come from slotRowCol`() {
        val slots = MachineAnalysis.buildGridSlots(
            trays = listOf(tray("t1", 25)),
            tierByProduct = emptyMap()
        )
        val slot = slots.single()
        assertEquals(1, slot.row)
        assertEquals(5, slot.column)
    }
}
