package xyz.vmflow.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Order suggestion for the warehouse "Order" tab: how much of each product to
 * order so that it lasts until the delivery after next.
 *
 *     target days = order interval + lead time + buffer
 *     need        = avg daily sales × target days − (warehouse + machines + on order)
 *     cartons     = ceil(need / units per carton)
 *
 * Machines count only when the company setting says so. A product sits on the
 * list automatically while it needs cartons; a manual entry keeps it there
 * (with at least one carton) until it is ordered or removed. 1:1 port of the
 * web's `lib/orderSuggestion.ts` and iOS `Models/OrderSuggestion.swift`; same
 * test cases in `OrderSuggestionTest.kt`.
 */

/** Company-wide calculation settings (`companies.order_*`). */
data class OrderSettings(
    val bufferDays: Int = 3,
    val countMachineStock: Boolean = true,
    val defaultLeadDays: Int = 3,
    val defaultIntervalDays: Int = 14,
)

/** A supplier with its ordering rhythm; null values fall back to the company defaults. */
data class OrderSupplier(
    val id: String,
    val name: String,
    val customerNumber: String? = null,
    val email: String? = null,
    val leadTimeDays: Int? = null,
    val orderIntervalDays: Int? = null,
    val minOrderValue: Double? = null,
)

/** Everything the suggestion needs to know about one product in one warehouse. */
data class OrderProductInput(
    val productId: String,
    val productName: String,
    val imagePath: String? = null,
    val discontinued: Boolean = false,
    val warehouseQty: Int,
    val machineQty: Int,
    val avgDailySales: Double,
    val onOrder: Int = 0,
    val unitSize: Int = 1,
    /** Preferred supplier, else the supplier of the latest purchase price. */
    val supplierId: String? = null,
    val unitPriceNet: Double? = null,
)

/** A product someone put on the order list by hand (`order_list_items`). */
@Serializable
data class ManualOrderEntry(
    @SerialName("product_id") val productId: String,
    @SerialName("added_by_name") val addedByName: String? = null,
    /** "web" | "ios" | "android" */
    @SerialName("added_via") val addedVia: String = "web",
    @SerialName("created_at") val createdAt: String? = null,
)

data class OrderLine(
    val product: OrderProductInput,
    val available: Int,
    val reachDays: Int?,
    val targetDays: Int,
    val suggestedCartons: Int,
    val cartons: Int,
    val units: Int,
    val value: Double?,
    /** On the list because of stock and reach. */
    val auto: Boolean,
    val manual: ManualOrderEntry?,
    /** The quantity was changed by hand. */
    val overridden: Boolean,
)

data class OrderGroup(
    /** null = "no supplier". */
    val supplier: OrderSupplier?,
    val lines: List<OrderLine>,
    /** Products of this supplier that are not on the list (to add by hand). */
    val others: List<OrderProductInput>,
    val value: Double,
    val belowMinOrder: Boolean,
    val hasEmpty: Boolean,
) {
    val key: String get() = supplier?.id ?: "none"
}

object OrderSuggestion {
    fun leadDays(s: OrderSupplier?, settings: OrderSettings): Int =
        s?.leadTimeDays ?: settings.defaultLeadDays

    fun intervalDays(s: OrderSupplier?, settings: OrderSettings): Int =
        s?.orderIntervalDays ?: settings.defaultIntervalDays

    fun targetDays(s: OrderSupplier?, settings: OrderSettings): Int =
        intervalDays(s, settings) + leadDays(s, settings) + settings.bufferDays

    fun availableUnits(p: OrderProductInput, settings: OrderSettings): Int =
        p.warehouseQty + if (settings.countMachineStock) p.machineQty else 0

    fun reachDays(p: OrderProductInput, settings: OrderSettings): Int? =
        if (p.avgDailySales > 0) (availableUnits(p, settings) / p.avgDailySales).roundToInt() else null

    fun suggestedCartons(p: OrderProductInput, days: Int, settings: OrderSettings): Int {
        if (p.discontinued || p.avgDailySales <= 0) return 0
        val need = p.avgDailySales * days - (availableUnits(p, settings) + p.onOrder)
        // Tolerate float noise (3.1 × 17 is 52.699999…).
        if (need <= 1e-9) return 0
        return ceil(need / max(1, p.unitSize) - 1e-9).toInt()
    }

    fun buildGroups(
        products: List<OrderProductInput>,
        suppliers: List<OrderSupplier>,
        manual: List<ManualOrderEntry>,
        overrides: Map<String, Int> = emptyMap(),
        settings: OrderSettings,
    ): List<OrderGroup> {
        val manualById = manual.associateBy { it.productId }
        val supplierById = suppliers.associateBy { it.id }

        class Bucket(val supplier: OrderSupplier?) {
            val lines = mutableListOf<OrderLine>()
            val others = mutableListOf<OrderProductInput>()
        }
        // Keyed by supplier id; null = no supplier.
        val buckets = LinkedHashMap<String?, Bucket>()
        fun ensure(s: OrderSupplier?): Bucket = buckets.getOrPut(s?.id) { Bucket(s) }
        suppliers.forEach { ensure(it) }

        for (p in products) {
            val supplier = p.supplierId?.let { supplierById[it] }
            val days = targetDays(supplier, settings)
            val suggested = suggestedCartons(p, days, settings)
            val manualEntry = manualById[p.productId]
            val bucket = ensure(supplier)

            if (suggested == 0 && manualEntry == null) {
                if (!p.discontinued) bucket.others += p
                continue
            }

            val base = if (manualEntry != null) max(1, suggested) else suggested
            val override = overrides[p.productId]
            val cartons = override?.let { max(0, it) } ?: base
            val units = cartons * max(1, p.unitSize)
            bucket.lines += OrderLine(
                product = p,
                available = availableUnits(p, settings),
                reachDays = reachDays(p, settings),
                targetDays = days,
                suggestedCartons = suggested,
                cartons = cartons,
                units = units,
                value = p.unitPriceNet?.let { units * it },
                auto = suggested > 0,
                manual = manualEntry,
                overridden = override != null && override != base,
            )
        }

        val byName = String.CASE_INSENSITIVE_ORDER
        val groups = buckets.values.map { b ->
            // Emptiest first: shortest reach, unknown reach (no sales) last.
            val lines = b.lines.sortedWith(
                compareBy<OrderLine> { it.reachDays ?: Int.MAX_VALUE }
                    .thenBy(byName) { it.product.productName }
            )
            val others = b.others.sortedWith(compareBy(byName) { it.productName })
            val value = lines.sumOf { it.value ?: 0.0 }
            val min = b.supplier?.minOrderValue ?: 0.0
            OrderGroup(
                supplier = b.supplier,
                lines = lines,
                others = others,
                value = value,
                belowMinOrder = min > 0 && lines.any { it.cartons > 0 } && value < min,
                hasEmpty = lines.any { it.product.warehouseQty == 0 },
            )
        }

        // Suppliers with something to order first (alphabetical), then the
        // rest; "no supplier" last and only when it has lines.
        return groups
            .filter { g -> if (g.supplier != null) g.lines.isNotEmpty() || g.others.isNotEmpty() else g.lines.isNotEmpty() }
            .sortedWith(
                compareBy<OrderGroup> { it.supplier == null }
                    .thenBy { it.lines.isEmpty() }
                    .thenBy(byName) { it.supplier?.name ?: "" }
            )
    }

    fun lineCount(groups: List<OrderGroup>): Int = groups.sumOf { it.lines.size }

    /** Plain-text order for e-mail or a supplier portal. */
    fun orderText(
        group: OrderGroup,
        customerNumberLabel: String,
        carton: (count: Int, size: Int) -> String,
    ): String {
        val rows = group.lines.filter { it.cartons > 0 }.map { l ->
            val size = max(1, l.product.unitSize)
            if (size > 1) "${l.units} × ${l.product.productName} (${carton(l.cartons, size)})"
            else "${l.units} × ${l.product.productName}"
        }
        val head = mutableListOf<String>()
        group.supplier?.let { s ->
            head += s.name
            if (!s.customerNumber.isNullOrEmpty()) head += "$customerNumberLabel: ${s.customerNumber}"
        }
        return (head + (if (head.isEmpty()) emptyList() else listOf("")) + rows).joinToString("\n")
    }
}
