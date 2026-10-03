package xyz.vmflow.data

import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.MatchedSaleRef
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SuppressedSale

/**
 * Ported from the merge/group logic in
 * `ios/VMflow/Views/Machines/MachineDetailView.swift` (~L451-492):
 * `groupFeedByDay`/`SalesFeedItem`/`FeedDayGroup`/`salesFeedItems`. The UTC
 * zone is used throughout so day boundaries are deterministic regardless of
 * the machine running the tests.
 */
class SalesFeedTest {

    private val utc = TimeZone.UTC

    private fun sale(id: String, createdAt: String?) = Sale(
        id = id,
        machineId = "m1",
        itemPrice = 2.5,
        createdAt = createdAt
    )

    private fun suppressed(
        id: String,
        receivedAt: String,
        matchedCreatedAt: String? = null,
        deviceCreatedAt: String? = "2026-08-10T09:00:00Z"
    ) = SuppressedSale(
        id = id,
        embeddedId = "e1",
        itemPrice = 2.5,
        receivedAt = receivedAt,
        deviceCreatedAt = deviceCreatedAt,
        reason = "brownout_duplicate",
        matched = matchedCreatedAt?.let { MatchedSaleRef(createdAt = it) }
    )

    // ─── buildItems ──────────────────────────────────────────────────────

    @Test
    fun `sales without suppressed rows pass through unchanged`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", "2026-08-12T10:00:00Z")),
            suppressed = emptyList()
        )
        assertEquals(1, items.size)
        assertTrue(items[0] is SalesFeedItem.SaleRow)
    }

    @Test
    fun `a suppressed row at or after the oldest sale is included`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", "2026-08-12T10:00:00Z")),
            suppressed = listOf(suppressed("sup1", "2026-08-12T10:00:00Z"))
        )
        assertEquals(2, items.size)
        assertTrue(items.any { it is SalesFeedItem.SuppressedRow })
    }

    @Test
    fun `a suppressed row older than the oldest loaded sale is dropped`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", "2026-08-12T10:00:00Z")),
            suppressed = listOf(suppressed("sup1", "2026-08-11T23:59:59Z"))
        )
        assertEquals(1, items.size)
        assertTrue(items[0] is SalesFeedItem.SaleRow)
    }

    @Test
    fun `the cutoff is the oldest of several loaded sales, not the newest`() {
        val items = SalesFeed.buildItems(
            sales = listOf(
                sale("s1", "2026-08-12T10:00:00Z"),
                sale("s2", "2026-08-10T08:00:00Z")
            ),
            suppressed = listOf(suppressed("sup1", "2026-08-11T00:00:00Z"))
        )
        assertEquals(3, items.size)
    }

    @Test
    fun `no sales at all means no suppressed rows are attached`() {
        val items = SalesFeed.buildItems(
            sales = emptyList(),
            suppressed = listOf(suppressed("sup1", "2026-08-12T10:00:00Z"))
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `a sale with no parseable timestamp is dropped`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", null), sale("s2", "2026-08-12T10:00:00Z")),
            suppressed = emptyList()
        )
        assertEquals(1, items.size)
        assertEquals("s2", (items[0] as SalesFeedItem.SaleRow).sale.id)
    }

    // ─── groupByDay ──────────────────────────────────────────────────────

    @Test
    fun `items land in the same day bucket`() {
        val items = SalesFeed.buildItems(
            sales = listOf(
                sale("s1", "2026-08-12T08:00:00Z"),
                sale("s2", "2026-08-12T20:00:00Z")
            ),
            suppressed = emptyList()
        )
        val groups = SalesFeed.groupByDay(items, utc)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
    }

    @Test
    fun `days are sorted newest first`() {
        val items = SalesFeed.buildItems(
            sales = listOf(
                sale("s1", "2026-08-10T08:00:00Z"),
                sale("s2", "2026-08-12T08:00:00Z")
            ),
            suppressed = emptyList()
        )
        val groups = SalesFeed.groupByDay(items, utc)
        assertEquals(2, groups.size)
        assertTrue(groups[0].date > groups[1].date)
    }

    @Test
    fun `items within a day are sorted newest first`() {
        val items = SalesFeed.buildItems(
            sales = listOf(
                sale("s1", "2026-08-12T08:00:00Z"),
                sale("s2", "2026-08-12T20:00:00Z")
            ),
            suppressed = emptyList()
        )
        val groups = SalesFeed.groupByDay(items, utc)
        assertEquals("s2", (groups[0].items[0] as SalesFeedItem.SaleRow).sale.id)
    }

    @Test
    fun `saleCount excludes suppressed rows`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", "2026-08-12T10:00:00Z")),
            suppressed = listOf(suppressed("sup1", "2026-08-12T11:00:00Z"))
        )
        val groups = SalesFeed.groupByDay(items, utc)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
        assertEquals(1, groups[0].saleCount)
    }

    @Test
    fun `a suppressed-only day anchored above the cutoff still forms its own group`() {
        val items = SalesFeed.buildItems(
            sales = listOf(sale("s1", "2026-08-10T10:00:00Z")),
            suppressed = listOf(suppressed("sup1", "2026-08-12T10:00:00Z"))
        )
        val groups = SalesFeed.groupByDay(items, utc)
        assertEquals(2, groups.size)
        assertEquals(0, groups[0].saleCount)
        assertEquals(1, groups[1].saleCount)
    }

    // ─── suppressedGapSeconds ────────────────────────────────────────────

    @Test
    fun `gap seconds is null when there is no matched sale`() {
        assertNull(SalesFeed.suppressedGapSeconds(suppressed("sup1", "2026-08-12T10:00:00Z")))
    }

    @Test
    fun `gap seconds is the absolute rounded difference to the matched sale`() {
        val s = suppressed(
            id = "sup1",
            receivedAt = "2026-08-12T10:00:42Z",
            matchedCreatedAt = "2026-08-12T10:00:00Z"
        )
        assertEquals(42L, SalesFeed.suppressedGapSeconds(s))
    }

    @Test
    fun `gap seconds is symmetric regardless of which timestamp is earlier`() {
        val s = suppressed(
            id = "sup1",
            receivedAt = "2026-08-12T10:00:00Z",
            matchedCreatedAt = "2026-08-12T10:00:42Z"
        )
        assertEquals(42L, SalesFeed.suppressedGapSeconds(s))
    }

    @Test
    fun `gap seconds is null when the matched timestamp is unparseable`() {
        val s = suppressed(
            id = "sup1",
            receivedAt = "2026-08-12T10:00:00Z",
            matchedCreatedAt = "not-a-date"
        )
        assertNull(SalesFeed.suppressedGapSeconds(s))
    }
}
