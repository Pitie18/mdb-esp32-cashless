package xyz.vmflow.models

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors ios/VMflow/Models/ActivityFeed.swift. Both clients must decode and
// group this data identically or the same warehouse session / tour looks
// different on each phone.

// MARK: - Raw rows (PostgREST decoding)

/**
 * Row from `activity_log` for the dashboard feed.
 *
 * Only the columns/metadata keys the feed renders; metadata is decoded
 * tolerantly because old rows may carry fewer fields.
 */
@Serializable
data class ActivityLogRow(
    val id: String,
    @SerialName("created_at") val createdAt: Instant,
    val action: String,
    val metadata: ActivityLogMetadata? = null,
)

/**
 * `activity_log.metadata` is a live cross-client contract shared with the PWA
 * and iOS. Every field is nullable with a default and unknown keys are
 * ignored on decode (see the Json instance that uses this class) - a
 * stricter decoder has already broken the iOS feed once when a field
 * changed shape.
 */
@Serializable
data class ActivityLogMetadata(
    @SerialName("tour_id") val tourId: String? = null,
    @SerialName("machine_name") val machineName: String? = null,
    @SerialName("trays_refilled") val traysRefilled: Int? = null,
    @SerialName("total_added") val totalAdded: Int? = null,
    @SerialName("machine_count") val machineCount: Int? = null,
    @SerialName("machine_names") val machineNames: List<String>? = null,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    // NOTE: the wire key really is "_user_display" (leading underscore) -
    // see ios/VMflow/Models/ActivityFeed.swift CodingKeys. Not "user_display".
    @SerialName("_user_display") val userDisplay: String? = null,
    val products: List<ActivityProductLine>? = null,
    // Cash-book (Barkasse) entry metadata - only present on
    // cash_book_entry_created rows.
    // NOTE: the wire key really is "type" (see Swift CodingKeys), not
    // "cash_type".
    @SerialName("type") val cashType: String? = null,
    val amount: Double? = null,
    val category: String? = null,
    // NOTE: the wire key really is "description" (see Swift CodingKeys),
    // not "note".
    @SerialName("description") val note: String? = null,
)

@Serializable
data class ActivityProductLine(
    @SerialName("product_name") val productName: String? = null,
    val quantity: Int? = null,
)

/**
 * Row from `warehouse_transactions` (transaction_type 'incoming'/'intake')
 * with FK-joined product and warehouse names.
 */
@Serializable
data class IntakeTransactionRow(
    val id: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("warehouse_id") val warehouseId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("quantity_change") val quantityChange: Int,
    val products: NameOnly? = null,
    val warehouses: NameOnly? = null,
)

@Serializable
data class NameOnly(val name: String? = null)

// MARK: - Feed items

data class ProductLine(val name: String, val quantity: Int)

data class RefillActivity(
    val id: String,
    val createdAt: Instant,
    val machineName: String,
    val traysRefilled: Int,
    val totalAdded: Int,
    val userDisplay: String?,
    val tourId: String?,
    val products: List<ProductLine>,
)

data class TourActivity(
    val id: String,
    val createdAt: Instant,
    val userDisplay: String?,
    val machineCount: Int,
    val machineNames: List<String>,
    val warehouseName: String?,
    val tourId: String?,
)

/**
 * One cash-book (Barkasse) money movement - a `cash_book_entry_created` row.
 * The `type` drives icon/tint/label.
 */
data class CashBookActivity(
    val id: String,
    val createdAt: Instant,
    val type: CashBookEntryType,
    val amount: Double,
    val category: String?,
    val note: String?,
    val userDisplay: String?,
)

/**
 * Full set of raw values taken from the real `CashBookEntryType` enum in
 * ios/VMflow/Models/CashBook.swift (deposit is not a real case there -
 * "initial", "correction", "payout" and "reversal" are). Unknown/absent
 * raw strings degrade to [UNKNOWN] rather than throwing.
 */
enum class CashBookEntryType(val raw: String) {
    INITIAL("initial"),
    WITHDRAWAL("withdrawal"),
    CORRECTION("correction"),
    PAYOUT("payout"),
    EXPENSE("expense"),
    REVERSAL("reversal"),
    UNKNOWN("unknown");

    companion object {
        fun fromRaw(raw: String?): CashBookEntryType =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * One intake "session": consecutive incoming warehouse transactions by the
 * same user into the same warehouse with <=15 min between bookings.
 */
data class IntakeGroup(
    /**
     * Deterministic: id of the OLDEST transaction in the group - stable
     * across reloads so row expansion state survives (spec, rule 2).
     */
    val id: String,
    /** Newest transaction time in the group (used for sorting/day grouping). */
    val date: Instant,
    val userId: String?,
    val userDisplay: String? = null,
    val warehouseName: String?,
    val totalUnits: Int,
    val products: List<ProductLine>,
) {
    val productCount: Int get() = products.size
}

/** Sale joined with the machine/product info the feed renders. */
data class SaleWithMachine(
    val sale: Sale,
    val machineName: String?,
    val productName: String?,
    val productImagePath: String?,
) {
    val id: String get() = sale.id

    val date: Instant
        get() = sale.createdAt?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                Instant.DISTANT_PAST
            }
        } ?: Instant.DISTANT_PAST
}

/** One entry in the dashboard "Recent Activity" timeline. */
sealed interface ActivityFeedItem {
    val id: String
    val date: Instant

    data class Sale(val sale: SaleWithMachine) : ActivityFeedItem {
        override val id: String = "sale-${sale.id}"
        override val date: Instant = sale.date
    }

    data class MachineRefilled(val activity: RefillActivity) : ActivityFeedItem {
        override val id: String = "refill-${activity.id}"
        override val date: Instant = activity.createdAt
    }

    data class TourStarted(val activity: TourActivity) : ActivityFeedItem {
        override val id: String = "tour-${activity.id}"
        override val date: Instant = activity.createdAt
    }

    data class StockIntake(val group: IntakeGroup) : ActivityFeedItem {
        override val id: String = "intake-${group.id}"
        override val date: Instant = group.date
    }

    data class CashBookEntry(val activity: CashBookActivity) : ActivityFeedItem {
        override val id: String = "cashentry-${activity.id}"
        override val date: Instant = activity.createdAt
    }
}
