package xyz.vmflow.data

/** How urgently a warehouse product needs reordering, by days of stock left. */
enum class ReachLevel { CRITICAL, WARNING, OK, UNKNOWN }

/**
 * Stock reach ("how long does the warehouse last") for the warehouse list.
 * Same formula and thresholds as the web warehouse page
 * (`management-frontend/app/composables/useWarehouse.ts`,
 * `estimated_days_remaining`) and iOS `WarehouseProductSummary`, so all three
 * clients show the same number for the same product.
 */
object StockReach {
    /** Reach above this is shown as ">90 days" — beyond that the exact figure is noise. */
    const val DISPLAY_CAP_DAYS = 90

    /** `round(quantity / avgDailySales)`, or null when the product didn't sell in the window. */
    fun daysRemaining(quantity: Int, avgDailySales: Double): Int? =
        if (avgDailySales > 0) Math.round(quantity / avgDailySales).toInt() else null

    /** ≤ 7 days critical, ≤ 14 warning — the web page's thresholds. */
    fun level(daysRemaining: Int?): ReachLevel = when {
        daysRemaining == null -> ReachLevel.UNKNOWN
        daysRemaining <= 7 -> ReachLevel.CRITICAL
        daysRemaining <= 14 -> ReachLevel.WARNING
        else -> ReachLevel.OK
    }
}
