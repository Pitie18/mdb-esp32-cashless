package xyz.vmflow.data

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.Sale

class DashboardKpisTest {

    // Fixed "now": Wednesday 2024-01-10, 15:00 UTC.
    // ISO week containing it: Mon 2024-01-08 .. Sun 2024-01-14.
    // Previous ISO week: Mon 2024-01-01 .. Sun 2024-01-07.
    // Calendar month: Jan 2024. Previous calendar month: Dec 2023.
    private val zone = TimeZone.UTC
    private val now = LocalDateTime(2024, 1, 10, 15, 0, 0)

    private fun sale(createdAt: String, price: Double = 1.0) =
        Sale(id = createdAt, itemPrice = price, createdAt = createdAt)

    @Test
    fun `a sale from today counts in today, week and month, but not yesterday`() {
        val kpis = DashboardKpis.bucketSales(listOf(sale("2024-01-10T10:00:00Z")), now, zone)
        assertEquals(1, kpis.todayCount)
        assertEquals(1, kpis.weekCount)
        assertEquals(1, kpis.monthCount)
        assertEquals(0, kpis.yesterdayCount)
    }

    @Test
    fun `a sale from yesterday counts in yesterday, not today`() {
        val kpis = DashboardKpis.bucketSales(listOf(sale("2024-01-09T10:00:00Z")), now, zone)
        assertEquals(1, kpis.yesterdayCount)
        assertEquals(0, kpis.todayCount)
    }

    @Test
    fun `a sale from last week counts in lastWeek, not week`() {
        // Friday 2024-01-05, inside the previous ISO week (Jan 1 - Jan 7).
        val kpis = DashboardKpis.bucketSales(listOf(sale("2024-01-05T10:00:00Z")), now, zone)
        assertEquals(1, kpis.lastWeekCount)
        assertEquals(0, kpis.weekCount)
    }

    @Test
    fun `a sale from last month counts in lastMonth, not month`() {
        val kpis = DashboardKpis.bucketSales(listOf(sale("2023-12-15T10:00:00Z")), now, zone)
        assertEquals(1, kpis.lastMonthCount)
        assertEquals(0, kpis.monthCount)
    }

    @Test
    fun `week boundary is the ISO week (Monday), not rolling seven days`() {
        // 2024-01-04 is within a rolling 7-day lookback from "now" (2024-01-10),
        // but it falls in the PREVIOUS ISO week (Mon Jan 1 - Sun Jan 7), not the
        // current one (Mon Jan 8 - Sun Jan 14). A rolling window would wrongly
        // put it in `week`; the ISO-week boundary puts it in `lastWeek`.
        val kpis = DashboardKpis.bucketSales(listOf(sale("2024-01-04T10:00:00Z")), now, zone)
        assertEquals(0, kpis.weekCount)
        assertEquals(1, kpis.lastWeekCount)
    }

    @Test
    fun `month boundary is the calendar month, not rolling 30 days`() {
        // 2023-12-20 is only 21 days before "now" (2024-01-10) - within a rolling
        // 30-day lookback - but it is in the PREVIOUS calendar month (December),
        // not the current one (January). A rolling window would wrongly put it
        // in `month`; the calendar-month boundary puts it in `lastMonth`.
        val kpis = DashboardKpis.bucketSales(listOf(sale("2023-12-20T10:00:00Z")), now, zone)
        assertEquals(0, kpis.monthCount)
        assertEquals(1, kpis.lastMonthCount)
    }

    @Test
    fun `item_price is summed as EUR, not divided by 100`() {
        val kpis = DashboardKpis.bucketSales(listOf(sale("2024-01-10T10:00:00Z", price = 2.50)), now, zone)
        assertEquals(2.50, kpis.todayRevenue, 0.0001)
    }

    @Test
    fun `buildDailyChart returns exactly 31 entries, ascending by date, even with zero sales`() {
        val chart = DashboardKpis.buildDailyChart(emptyList(), now, zone)
        assertEquals(31, chart.size)
        for (i in 1 until chart.size) {
            assertTrue(chart[i - 1].date < chart[i].date)
        }
        // Window is [today - 30, today] inclusive.
        assertEquals(now.date.minus(DatePeriod(days = 30)), chart.first().date)
        assertEquals(now.date, chart.last().date)
    }

    @Test
    fun `days without sales have revenue 0 and count 0, but are not missing`() {
        val chart = DashboardKpis.buildDailyChart(emptyList(), now, zone)
        val today = chart.last()
        assertEquals(now.date, today.date)
        assertEquals(0.0, today.revenue, 0.0001)
        assertEquals(0, today.count)
    }

    @Test
    fun `a sale outside the 30-day window lands in no bucket`() {
        // 35 days before "now" - older than the 31-bucket window (today - 30 .. today).
        val chart = DashboardKpis.buildDailyChart(listOf(sale("2023-12-06T10:00:00Z")), now, zone)
        assertEquals(31, chart.size)
        assertEquals(0, chart.sumOf { it.count })
        assertEquals(0.0, chart.sumOf { it.revenue }, 0.0001)
    }

    @Test
    fun `averageOf divides by the bucket count (31), not the count of days with revenue`() {
        val chart = DashboardKpis.buildDailyChart(listOf(sale("2024-01-10T10:00:00Z", price = 31.0)), now, zone)
        assertEquals(31, chart.size)
        assertEquals(1.0, DashboardKpis.averageOf(chart), 0.0001)
    }

    @Test
    fun `queryLowerBound is the minimum of the three period boundaries`() {
        // startOfLastMonth = 2023-12-01T00:00Z (earliest of the three)
        // startOfLastWeek  = 2024-01-01T00:00Z
        // startOfYesterday = 2024-01-09T00:00Z
        val expected = LocalDateTime(2023, 12, 1, 0, 0, 0).toInstant(zone)
        assertEquals(expected, DashboardKpis.queryLowerBound(now, zone))
    }
}
