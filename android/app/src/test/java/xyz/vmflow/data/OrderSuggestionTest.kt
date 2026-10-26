package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warehouse order suggestion. Same cases as the web's
 * `orderSuggestion.test.ts` and iOS `HostTests/OrderSuggestion`.
 */
class OrderSuggestionTest {

    private val becker = OrderSupplier(
        id = "s1", name = "Becker", customerNumber = "40817",
        leadTimeDays = 2, orderIntervalDays = 14, minOrderValue = 50.0
    )
    private val hahn = OrderSupplier(id = "s2", name = "Hahn")
    private val settings = OrderSettings()

    private fun product(
        id: String = "p1", name: String = "Cola", discontinued: Boolean = false,
        warehouse: Int = 12, machines: Int = 18, avg: Double = 3.1, onOrder: Int = 0,
        unitSize: Int = 24, supplier: String? = "s1", price: Double? = 0.52
    ) = OrderProductInput(
        productId = id, productName = name, discontinued = discontinued,
        warehouseQty = warehouse, machineQty = machines, avgDailySales = avg, onOrder = onOrder,
        unitSize = unitSize, supplierId = supplier, unitPriceNet = price
    )

    @Test
    fun targetDays() {
        assertEquals(14 + 2 + 3, OrderSuggestion.targetDays(becker, settings))
        assertEquals(14 + 3 + 3, OrderSuggestion.targetDays(hahn, settings))
        assertEquals(20, OrderSuggestion.targetDays(null, settings))
    }

    @Test
    fun suggestedCartons() {
        // 3.1 × 19 = 58.9 needed, 30 available → 28.9 missing → 2 cartons of 24
        assertEquals(2, OrderSuggestion.suggestedCartons(product(), 19, settings))
        assertEquals(1, OrderSuggestion.suggestedCartons(product(onOrder = 24), 19, settings))
        assertEquals(0, OrderSuggestion.suggestedCartons(product(onOrder = 48), 19, settings))
        val noMachines = settings.copy(countMachineStock = false)
        assertEquals(2, OrderSuggestion.suggestedCartons(product(machines = 100), 19, noMachines))
        assertEquals(0, OrderSuggestion.suggestedCartons(product(machines = 100), 19, settings))
        assertEquals(0, OrderSuggestion.suggestedCartons(product(discontinued = true, warehouse = 0), 19, settings))
        assertEquals(0, OrderSuggestion.suggestedCartons(product(warehouse = 0, avg = 0.0), 19, settings))
        // 3.1 × 10 = 31.000000000000004; 7 available → exactly 24 missing → 1 carton
        assertEquals(1, OrderSuggestion.suggestedCartons(product(warehouse = 7, machines = 0), 10, settings))
    }

    @Test
    fun reachDays() {
        assertEquals(10, OrderSuggestion.reachDays(product(), settings))
        assertNull(OrderSuggestion.reachDays(product(avg = 0.0), settings))
    }

    @Test
    fun buildGroups() {
        val manual = ManualOrderEntry(productId = "p3", addedByName = "Lucien", addedVia = "android")
        val groups = OrderSuggestion.buildGroups(
            products = listOf(
                product(),
                product(id = "p2", name = "Fanta", warehouse = 100),
                product(id = "p3", name = "Snickers", warehouse = 80, avg = 1.0, supplier = "s2"),
                product(id = "p4", name = "Nuts", warehouse = 0, machines = 0, unitSize = 1, supplier = null),
            ),
            suppliers = listOf(hahn, becker),
            manual = listOf(manual),
            settings = settings
        )
        assertEquals(listOf("Becker", "Hahn", null), groups.map { it.supplier?.name })
        val (b, h, none) = groups

        assertEquals(listOf("p1"), b.lines.map { it.product.productId })
        assertEquals(listOf("p2"), b.others.map { it.productId })
        assertTrue(b.lines.first().auto)
        assertEquals(48, b.lines.first().units)
        assertEquals(48 * 0.52, b.value, 1e-9)
        assertTrue(b.belowMinOrder)

        assertFalse(h.lines.first().auto)
        assertEquals("Lucien", h.lines.first().manual?.addedByName)
        assertEquals(1, h.lines.first().cartons)

        assertNull(none.supplier)
        assertTrue(none.hasEmpty)
        assertEquals(62, none.lines.first().cartons) // 3.1 × 20 days, cartons of 1

        assertEquals(3, OrderSuggestion.lineCount(groups))
    }

    @Test
    fun overrides() {
        val overridden = OrderSuggestion.buildGroups(
            listOf(product()), listOf(becker), emptyList(), mapOf("p1" to 5), settings
        ).first()
        assertEquals(5, overridden.lines.first().cartons)
        assertEquals(120, overridden.lines.first().units)
        assertTrue(overridden.lines.first().overridden)
        assertFalse(overridden.belowMinOrder)

        val zeroed = OrderSuggestion.buildGroups(
            listOf(product()), listOf(becker), emptyList(), mapOf("p1" to 0), settings
        ).first()
        assertEquals(0, zeroed.lines.first().cartons)
        assertFalse(zeroed.belowMinOrder)
    }

    @Test
    fun emptyGroupsDropped() {
        val plenty = OrderSuggestion.buildGroups(
            listOf(product(warehouse = 500)), listOf(becker, hahn), emptyList(), settings = settings
        )
        assertEquals(listOf("s1"), plenty.map { it.supplier?.id })
        assertTrue(plenty.first().lines.isEmpty())
    }

    @Test
    fun orderText() {
        val g = OrderSuggestion.buildGroups(listOf(product()), listOf(becker), emptyList(), settings = settings).first()
        assertEquals(
            "Becker\nKd.-Nr.: 40817\n\n48 × Cola (2 × 24)",
            OrderSuggestion.orderText(g, "Kd.-Nr.") { n, s -> "$n × $s" }
        )
    }
}
