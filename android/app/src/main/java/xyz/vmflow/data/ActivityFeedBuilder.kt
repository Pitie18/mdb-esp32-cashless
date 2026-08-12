package xyz.vmflow.data

import xyz.vmflow.models.ActivityFeedItem
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.CashBookActivity
import xyz.vmflow.models.CashBookEntryType
import xyz.vmflow.models.IntakeGroup
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.ProductLine
import xyz.vmflow.models.RefillActivity
import xyz.vmflow.models.SaleWithMachine
import xyz.vmflow.models.TourActivity

/**
 * Pure builders for the dashboard timeline — no I/O, so they are unit
 * testable. Mirrors ActivityFeedBuilder in ios/VMflow/Models/ActivityFeed.swift;
 * both clients must group and order identically or the same tour looks
 * different on each phone.
 */
object ActivityFeedBuilder {

    /** Longest gap between two bookings that still counts as one intake session. */
    const val INTAKE_SESSION_GAP_MS = 15 * 60 * 1000L

    /**
     * Group incoming warehouse transactions into intake sessions.
     *
     * Rows may arrive in any order and are sorted ascending first. A new
     * group starts when the user or the warehouse changes, or when the gap
     * to the previous booking exceeds [INTAKE_SESSION_GAP_MS].
     */
    fun groupIntakes(rows: List<IntakeTransactionRow>): List<IntakeGroup> {
        val sorted = rows.sortedBy { it.createdAt }
        val groups = mutableListOf<IntakeGroup>()
        val current = mutableListOf<IntakeTransactionRow>()

        fun flush() {
            val first = current.firstOrNull() ?: return
            val last = current.last()
            // Aggregate per product name, preserving first-seen order.
            val order = mutableListOf<String>()
            val qty = mutableMapOf<String, Int>()
            for (row in current) {
                val name = row.products?.name ?: "—"
                if (name !in qty) order += name
                qty[name] = (qty[name] ?: 0) + row.quantityChange
            }
            groups += IntakeGroup(
                // Oldest row's id: stable across reloads, so row expansion survives.
                id = first.id,
                // Newest timestamp: drives sorting and day grouping.
                date = last.createdAt,
                userId = first.userId,
                userDisplay = null,
                warehouseName = first.warehouses?.name,
                totalUnits = current.sumOf { it.quantityChange },
                products = order.map { ProductLine(name = it, quantity = qty[it] ?: 0) },
            )
            current.clear()
        }

        for (row in sorted) {
            val prev = current.lastOrNull()
            if (prev != null) {
                val sameSession = prev.userId == row.userId &&
                    prev.warehouseId == row.warehouseId &&
                    (row.createdAt.toEpochMilliseconds() - prev.createdAt.toEpochMilliseconds()) <= INTAKE_SESSION_GAP_MS
                if (!sameSession) flush()
            }
            current += row
        }
        flush()
        return groups
    }

    /** Map activity_log rows to feed items. Unknown actions are skipped. */
    fun makeActivityItems(rows: List<ActivityLogRow>): List<ActivityFeedItem> =
        rows.mapNotNull { row ->
            val meta = row.metadata
            when (row.action) {
                "stock_refill_tour" -> ActivityFeedItem.MachineRefilled(
                    RefillActivity(
                        id = row.id,
                        createdAt = row.createdAt,
                        machineName = meta?.machineName ?: "—",
                        traysRefilled = meta?.traysRefilled ?: 0,
                        totalAdded = meta?.totalAdded ?: 0,
                        userDisplay = meta?.userDisplay,
                        tourId = meta?.tourId,
                        products = meta?.products.orEmpty().mapNotNull { line ->
                            line.productName?.let { ProductLine(it, line.quantity ?: 0) }
                        },
                    )
                )
                "tour_started" -> {
                    val names = meta?.machineNames.orEmpty()
                    ActivityFeedItem.TourStarted(
                        TourActivity(
                            id = row.id,
                            createdAt = row.createdAt,
                            userDisplay = meta?.userDisplay,
                            machineCount = meta?.machineCount ?: names.size,
                            machineNames = names,
                            warehouseName = meta?.warehouseName,
                            tourId = meta?.tourId,
                        )
                    )
                }
                "cash_book_entry_created" -> ActivityFeedItem.CashBookEntry(
                    CashBookActivity(
                        id = row.id,
                        createdAt = row.createdAt,
                        type = CashBookEntryType.fromRaw(meta?.cashType),
                        amount = meta?.amount ?: 0.0,
                        category = meta?.category,
                        note = meta?.note,
                        userDisplay = meta?.userDisplay,
                    )
                )
                else -> null
            }
        }

    /** Merge all sources into one timeline, newest first. */
    fun mergeFeed(
        sales: List<SaleWithMachine>,
        activityRows: List<ActivityLogRow>,
        intakeGroups: List<IntakeGroup>,
    ): List<ActivityFeedItem> =
        (sales.map { ActivityFeedItem.Sale(it) } +
            makeActivityItems(activityRows) +
            intakeGroups.map { ActivityFeedItem.StockIntake(it) })
            .sortedByDescending { it.date }
}
