package xyz.vmflow.data

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.ActivityFeedItem
import xyz.vmflow.models.ActivityLogMetadata
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.NameOnly
import xyz.vmflow.models.RefillActivity

class ActivityFeedBuilderTest {

    private val base = Instant.parse("2026-08-12T10:00:00Z")
    private fun t(minutes: Long) = base.plus(kotlin.time.Duration.parse("${minutes}m"))

    private fun intake(
        id: String,
        minutes: Long,
        user: String? = "u1",
        warehouse: String? = "w1",
        qty: Int = 1,
        product: String? = "Cola",
    ) = IntakeTransactionRow(
        id = id,
        createdAt = t(minutes),
        warehouseId = warehouse,
        userId = user,
        quantityChange = qty,
        products = product?.let { NameOnly(it) },
        warehouses = NameOnly("Hauptlager"),
    )

    @Test
    fun `bookings within the session gap form one group`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0), intake("b", 10), intake("c", 14))
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].totalUnits)
    }

    @Test
    fun `exactly fifteen minutes still counts as the same session`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0), intake("b", 15)))
        assertEquals(1, groups.size)
    }

    @Test
    fun `a gap beyond fifteen minutes starts a new group`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0), intake("b", 16)))
        assertEquals(2, groups.size)
    }

    @Test
    fun `a different user starts a new group even within the gap`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0, user = "u1"), intake("b", 5, user = "u2"))
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `a different warehouse starts a new group even within the gap`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0, warehouse = "w1"), intake("b", 5, warehouse = "w2"))
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `the group id is the oldest transaction and the date is the newest`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("newest", 10), intake("oldest", 0), intake("middle", 5))
        )
        assertEquals(1, groups.size)
        assertEquals("oldest", groups[0].id)
        assertEquals(t(10), groups[0].date)
    }

    @Test
    fun `rows arriving out of order are sorted before grouping`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("c", 40), intake("a", 0), intake("b", 5))
        )
        assertEquals(2, groups.size)
        assertEquals("a", groups[0].id)
        assertEquals("c", groups[1].id)
    }

    @Test
    fun `quantities are summed per product in first-seen order`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(
                intake("a", 0, product = "Cola", qty = 2),
                intake("b", 1, product = "Fanta", qty = 5),
                intake("c", 2, product = "Cola", qty = 3),
            )
        )
        assertEquals(listOf("Cola" to 5, "Fanta" to 5), groups[0].products.map { it.name to it.quantity })
        assertEquals(10, groups[0].totalUnits)
    }

    @Test
    fun `a missing product name falls back to a dash`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0, product = null)))
        assertEquals("—", groups[0].products.first().name)
    }

    @Test
    fun `an empty input yields no groups`() {
        assertTrue(ActivityFeedBuilder.groupIntakes(emptyList()).isEmpty())
    }

    private fun logRow(id: String, action: String, minutes: Long, meta: ActivityLogMetadata? = null) =
        ActivityLogRow(id = id, createdAt = t(minutes), action = action, metadata = meta)

    @Test
    fun `a refill row becomes a machineRefilled item`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("r", "stock_refill_tour", 0, ActivityLogMetadata(machineName = "Automat 1", traysRefilled = 3, totalAdded = 12)))
        )
        assertEquals(1, items.size)
        val item = items.first() as ActivityFeedItem.MachineRefilled
        assertEquals("Automat 1", item.activity.machineName)
        assertEquals(3, item.activity.traysRefilled)
    }

    @Test
    fun `a tour row falls back to the machine name count when machineCount is absent`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("t", "tour_started", 0, ActivityLogMetadata(machineNames = listOf("A", "B"))))
        )
        val item = items.first() as ActivityFeedItem.TourStarted
        assertEquals(2, item.activity.machineCount)
    }

    @Test
    fun `a cash book row keeps its amount and type`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("c", "cash_book_entry_created", 0, ActivityLogMetadata(cashType = "deposit", amount = 42.5)))
        )
        val item = items.first() as ActivityFeedItem.CashBookEntry
        assertEquals(42.5, item.activity.amount, 0.001)
    }

    @Test
    fun `unknown actions are skipped rather than crashing the feed`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("x", "something_new_from_the_pwa", 0), logRow("r", "stock_refill_tour", 1))
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `missing metadata degrades to defaults instead of throwing`() {
        val items = ActivityFeedBuilder.makeActivityItems(listOf(logRow("r", "stock_refill_tour", 0, null)))
        val item = items.first() as ActivityFeedItem.MachineRefilled
        assertEquals("—", item.activity.machineName)
        assertEquals(0, item.activity.traysRefilled)
    }

    @Test
    fun `the merged feed is ordered newest first across all sources`() {
        val merged = ActivityFeedBuilder.mergeFeed(
            sales = emptyList(),
            activityRows = listOf(logRow("old", "stock_refill_tour", 0), logRow("new", "stock_refill_tour", 30)),
            intakeGroups = ActivityFeedBuilder.groupIntakes(listOf(intake("mid", 15))),
        )
        assertEquals(listOf(t(30), t(15), t(0)), merged.map { it.date })
    }

    @Test
    fun `feed item ids are unique and prefixed per type`() {
        val merged = ActivityFeedBuilder.mergeFeed(
            sales = emptyList(),
            activityRows = listOf(logRow("shared-id", "stock_refill_tour", 0)),
            intakeGroups = ActivityFeedBuilder.groupIntakes(listOf(intake("shared-id", 5))),
        )
        assertEquals(2, merged.map { it.id }.toSet().size)
        assertTrue(merged.any { it.id.startsWith("refill-") })
        assertTrue(merged.any { it.id.startsWith("intake-") })
    }

    // MARK: - groupByDay (dashboard feed day headers)

    @Test
    fun `items on the same calendar day land in one group`() {
        val items = listOf(
            ActivityFeedItem.MachineRefilled(
                RefillActivity(
                    id = "a", createdAt = t(0), machineName = "M1", traysRefilled = 1,
                    totalAdded = 1, userDisplay = null, tourId = null, products = emptyList(),
                )
            ),
            ActivityFeedItem.MachineRefilled(
                RefillActivity(
                    id = "b", createdAt = t(60), machineName = "M1", traysRefilled = 1,
                    totalAdded = 1, userDisplay = null, tourId = null, products = emptyList(),
                )
            ),
        )
        val groups = ActivityFeedBuilder.groupByDay(items, TimeZone.UTC)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
    }

    @Test
    fun `items are bucketed newest day first, each bucket newest item first`() {
        val today = ActivityFeedItem.MachineRefilled(
            RefillActivity(
                id = "today", createdAt = t(0), machineName = "M1", traysRefilled = 1,
                totalAdded = 1, userDisplay = null, tourId = null, products = emptyList(),
            )
        )
        // base is 2026-08-12T10:00:00Z; 25h/20h back crosses into 2026-08-11.
        val yesterdayEarlier = ActivityFeedItem.MachineRefilled(
            RefillActivity(
                id = "yesterday-early",
                createdAt = base.minus(kotlin.time.Duration.parse("25h")),
                machineName = "M1", traysRefilled = 1,
                totalAdded = 1, userDisplay = null, tourId = null, products = emptyList(),
            )
        )
        val yesterdayLater = ActivityFeedItem.MachineRefilled(
            RefillActivity(
                id = "yesterday-late",
                createdAt = base.minus(kotlin.time.Duration.parse("20h")),
                machineName = "M1", traysRefilled = 1,
                totalAdded = 1, userDisplay = null, tourId = null, products = emptyList(),
            )
        )
        val groups = ActivityFeedBuilder.groupByDay(
            listOf(yesterdayEarlier, today, yesterdayLater),
            TimeZone.UTC,
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("today"), groups[0].items.map { it.id.removePrefix("refill-") })
        assertEquals(
            listOf("yesterday-late", "yesterday-early"),
            groups[1].items.map { it.id.removePrefix("refill-") },
        )
    }
}
