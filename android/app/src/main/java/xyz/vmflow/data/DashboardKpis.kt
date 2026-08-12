package xyz.vmflow.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.models.Sale

/**
 * Multi-period sales totals for the dashboard header. Mirrors the six
 * buckets computed by `DashboardViewModel.loadSalesKPIs()` on iOS.
 */
data class SalesKpis(
    val todayRevenue: Double = 0.0,
    val todayCount: Int = 0,
    val yesterdayRevenue: Double = 0.0,
    val yesterdayCount: Int = 0,
    val weekRevenue: Double = 0.0,
    val weekCount: Int = 0,
    val lastWeekRevenue: Double = 0.0,
    val lastWeekCount: Int = 0,
    val monthRevenue: Double = 0.0,
    val monthCount: Int = 0,
    val lastMonthRevenue: Double = 0.0,
    val lastMonthCount: Int = 0,
)

/** One day's aggregated sales for the 30-day dashboard chart. */
data class DailySales(
    val date: LocalDate,
    val revenue: Double,
    val count: Int,
)

/**
 * Pure KPI bucketing and 30-day chart binning for the dashboard — no I/O,
 * so it is unit testable. Mirrors `loadSalesKPIs()` / `loadDailyChart()` in
 * `ios/VMflow/ViewModels/DashboardViewModel.swift`; both clients must bucket
 * sales identically or the same data shows a different number on each phone.
 *
 * `now` is a parameter rather than read from the system clock so these stay
 * deterministically testable — do not add a convenience overload that reads
 * `Clock.System.now()` internally, it will end up in a test and flake.
 */
object DashboardKpis {

    private data class Boundaries(
        val startOfToday: Instant,
        val startOfYesterday: Instant,
        val startOfWeek: Instant,
        val startOfLastWeek: Instant,
        val startOfMonth: Instant,
        val startOfLastMonth: Instant,
    )

    /**
     * Start of the ISO week (Monday) containing [date]. `DayOfWeek.ordinal` is
     * 0 for MONDAY .. 6 for SUNDAY in kotlinx.datetime, so it doubles as the
     * "days since Monday" offset directly.
     */
    private fun startOfIsoWeek(date: LocalDate): LocalDate =
        date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)

    /** Start of the calendar month containing [date]. */
    private fun startOfMonth(date: LocalDate): LocalDate =
        LocalDate(date.year, date.monthNumber, 1)

    private fun boundaries(now: LocalDateTime, zone: TimeZone): Boundaries {
        val today = now.date

        val startOfWeekDate = startOfIsoWeek(today)
        val startOfMonthDate = startOfMonth(today)

        return Boundaries(
            startOfToday = today.atStartOfDayIn(zone),
            startOfYesterday = today.minus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
            startOfWeek = startOfWeekDate.atStartOfDayIn(zone),
            startOfLastWeek = startOfWeekDate.minus(7, DateTimeUnit.DAY).atStartOfDayIn(zone),
            startOfMonth = startOfMonthDate.atStartOfDayIn(zone),
            startOfLastMonth = startOfMonthDate.minus(1, DateTimeUnit.MONTH).atStartOfDayIn(zone),
        )
    }

    /**
     * Earliest of the three "previous period" starts (last month, last week,
     * yesterday). A single fetch from this instant onward covers all six KPI
     * buckets, which are then filled client-side by [bucketSales].
     */
    fun queryLowerBound(now: LocalDateTime, zone: TimeZone): Instant {
        val b = boundaries(now, zone)
        return minOf(b.startOfLastMonth, b.startOfLastWeek, b.startOfYesterday)
    }

    /** Buckets [sales] into the six today/yesterday/week/lastWeek/month/lastMonth totals. */
    fun bucketSales(sales: List<Sale>, now: LocalDateTime, zone: TimeZone): SalesKpis {
        val b = boundaries(now, zone)

        var todayRev = 0.0; var todayCount = 0
        var yesterdayRev = 0.0; var yesterdayCount = 0
        var weekRev = 0.0; var weekCount = 0
        var lastWeekRev = 0.0; var lastWeekCount = 0
        var monthRev = 0.0; var monthCount = 0
        var lastMonthRev = 0.0; var lastMonthCount = 0

        for (sale in sales) {
            val createdAt = sale.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: continue
            val price = sale.itemPrice

            // Month buckets — current vs previous full calendar month
            if (createdAt >= b.startOfMonth) {
                monthRev += price; monthCount++
            } else if (createdAt >= b.startOfLastMonth && createdAt < b.startOfMonth) {
                lastMonthRev += price; lastMonthCount++
            }

            // Week buckets — current vs previous full ISO week
            if (createdAt >= b.startOfWeek) {
                weekRev += price; weekCount++
            } else if (createdAt >= b.startOfLastWeek && createdAt < b.startOfWeek) {
                lastWeekRev += price; lastWeekCount++
            }

            // Day buckets — today vs yesterday
            if (createdAt >= b.startOfToday) {
                todayRev += price; todayCount++
            } else if (createdAt >= b.startOfYesterday && createdAt < b.startOfToday) {
                yesterdayRev += price; yesterdayCount++
            }
        }

        return SalesKpis(
            todayRevenue = todayRev, todayCount = todayCount,
            yesterdayRevenue = yesterdayRev, yesterdayCount = yesterdayCount,
            weekRevenue = weekRev, weekCount = weekCount,
            lastWeekRevenue = lastWeekRev, lastWeekCount = lastWeekCount,
            monthRevenue = monthRev, monthCount = monthCount,
            lastMonthRevenue = lastMonthRev, lastMonthCount = lastMonthCount,
        )
    }

    /**
     * Pre-populates 31 daily buckets from `(today − 30 days)` through today
     * inclusive (`0..<31`, matching iOS), then folds [sales] into them. Days
     * without sales stay present with revenue 0 / count 0 rather than being
     * omitted, so the chart renders a gap instead of a hole in the axis.
     */
    fun buildDailyChart(sales: List<Sale>, now: LocalDateTime, zone: TimeZone): List<DailySales> {
        val today = now.date
        val start = today.minus(30, DateTimeUnit.DAY)

        val buckets = LinkedHashMap<LocalDate, DailySales>()
        for (offset in 0 until 31) {
            val day = start.plus(offset, DateTimeUnit.DAY)
            buckets[day] = DailySales(date = day, revenue = 0.0, count = 0)
        }

        for (sale in sales) {
            val createdAt = sale.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: continue
            val day = createdAt.toLocalDateTime(zone).date
            val existing = buckets[day] ?: continue // outside the 31-day window
            buckets[day] = existing.copy(
                revenue = existing.revenue + sale.itemPrice,
                count = existing.count + 1,
            )
        }

        return buckets.values.sortedBy { it.date }
    }

    /** Average daily revenue across all [daily] buckets, including zero-revenue days. */
    fun averageOf(daily: List<DailySales>): Double {
        if (daily.isEmpty()) return 0.0
        return daily.sumOf { it.revenue } / daily.size
    }

    /** Total revenue across all [daily] buckets. */
    fun totalOf(daily: List<DailySales>): Double =
        daily.sumOf { it.revenue }
}
