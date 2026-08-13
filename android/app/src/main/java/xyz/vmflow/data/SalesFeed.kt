package xyz.vmflow.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.DurationUnit
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SuppressedSale

/** One row in the merged Sales-tab feed: a real sale or an auto-removed (suppressed) duplicate. */
sealed class SalesFeedItem {
    abstract val id: String
    abstract val date: Instant

    data class SaleRow(val sale: Sale, override val date: Instant) : SalesFeedItem() {
        override val id: String get() = "sale-${sale.id}"
    }

    data class SuppressedRow(val suppressed: SuppressedSale, override val date: Instant) : SalesFeedItem() {
        override val id: String get() = "sup-${suppressed.id}"
    }
}

/** One calendar day's worth of merged feed rows, newest first; [saleCount] excludes suppressed rows. */
data class SalesFeedDayGroup(val date: LocalDate, val items: List<SalesFeedItem>, val saleCount: Int)

/**
 * Pure merge/group logic for the machine detail Sales tab — no I/O, so it is
 * unit-testable. Mirrors `groupFeedByDay`/`SalesFeedItem`/`FeedDayGroup`/
 * `salesFeedItems` in `ios/VMflow/Views/Machines/MachineDetailView.swift`
 * (~L451-492): suppressed (auto-removed) sales are merged into the same
 * day-grouped feed as ordinary sales, rendered but not counted in each day's
 * header count, and only included from the oldest loaded sale onward so a
 * suppressed-only day can't dangle below the last loaded (limit-50) sale.
 */
object SalesFeed {

    /**
     * Merges [sales] with [suppressed] rows whose `received_at` is at/after the
     * oldest loaded sale's `created_at`. Rows with an unparseable or missing
     * timestamp are dropped — they have nothing to group by. If no sale has a
     * usable timestamp, no suppressed rows are attached (no cutoff to anchor to).
     */
    fun buildItems(sales: List<Sale>, suppressed: List<SuppressedSale>): List<SalesFeedItem> {
        val saleRows = sales.mapNotNull { sale ->
            sale.createdAt?.let(::parseInstantOrNull)?.let { SalesFeedItem.SaleRow(sale, it) }
        }
        val cutoff = saleRows.minOfOrNull { it.date } ?: return saleRows
        val suppressedRows = suppressed.mapNotNull { s ->
            parseInstantOrNull(s.receivedAt)
                ?.takeIf { it >= cutoff }
                ?.let { SalesFeedItem.SuppressedRow(s, it) }
        }
        return saleRows + suppressedRows
    }

    /** Groups a merged feed into calendar-day buckets (newest day, newest item within a day, first). */
    fun groupByDay(items: List<SalesFeedItem>, zone: TimeZone): List<SalesFeedDayGroup> =
        items.groupBy { it.date.toLocalDateTime(zone).date }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayItems) ->
                val sorted = dayItems.sortedByDescending { it.date }
                SalesFeedDayGroup(date, sorted, sorted.count { it is SalesFeedItem.SaleRow })
            }

    /**
     * Seconds between a suppressed row's server arrival and its matched real
     * sale, rounded to the nearest second (matches iOS's
     * `Int(abs(...).rounded())`) — null when unmatched or unparseable. Feeds
     * the "identical sale Ns earlier" reason-text fragment.
     */
    fun suppressedGapSeconds(sale: SuppressedSale): Long? {
        val matchedAt = sale.matched?.createdAt?.let(::parseInstantOrNull) ?: return null
        val receivedAt = parseInstantOrNull(sale.receivedAt) ?: return null
        val seconds = kotlin.math.abs((receivedAt - matchedAt).toDouble(DurationUnit.SECONDS))
        return kotlin.math.round(seconds).toLong()
    }

    private fun parseInstantOrNull(iso: String): Instant? =
        try {
            Instant.parse(iso)
        } catch (_: Exception) {
            null
        }
}
