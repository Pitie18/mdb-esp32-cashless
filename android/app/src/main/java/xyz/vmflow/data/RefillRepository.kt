package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import xyz.vmflow.models.MachineWithStats
import xyz.vmflow.models.LegacyRefillItem
import xyz.vmflow.models.LegacyRefillMachine
import xyz.vmflow.models.RefillMachine
import xyz.vmflow.models.RefillTray
import xyz.vmflow.models.Tray
import xyz.vmflow.models.WarehousePositionGroup
import xyz.vmflow.models.WarehouseProductPosition

object RefillRepository {
    private val postgrest get() = SupabaseService.client.postgrest

    /**
     * Fetches every vending machine (with its `embeddeds` join, via
     * [MachineRepository.fetchMachines] — same select shape iOS `loadData`
     * uses, L1216-1223) together with **every** tray of every machine, not
     * only trays below threshold: the refill step's UI shows full trays too,
     * collapsed. A machine with zero trays drops out of the result; a
     * machine with trays is included even if none of them currently need a
     * refill — need-based filtering now happens downstream, over the
     * combined packing list, not here (see [RefillTourLogic.buildCombinedPackingList]).
     *
     * `fillAmount` on every [RefillTray] starts at the tray's `deficit`.
     *
     * The returned machine order carries **no meaning** — it is whatever
     * order the `vendingMachine` fetch happened to yield. iOS sorts by
     * urgency inside the `buildRefillMachines` step this function
     * deliberately bypasses, so the caller that establishes a tour's visit
     * order has to sort for itself.
     */
    suspend fun fetchRefillMachines(): Result<List<RefillMachine>> {
        return try {
            val machines = MachineRepository.fetchMachines().getOrThrow()

            val trays = postgrest.from("machine_trays")
                .select(
                    Columns.raw(
                        "id, machine_id, item_number, product_id, capacity, current_stock, " +
                            "min_stock, fill_when_below, products(id, name, image_path, discontinued, sellprice)"
                    )
                ) {
                    order("item_number", Order.ASCENDING)
                }
                .decodeList<Tray>()

            val traysByMachine = trays.groupBy { it.machineId }
            val refillMachines = machines.mapNotNull { machine ->
                val machineTrays = traysByMachine[machine.id]
                if (machineTrays.isNullOrEmpty()) return@mapNotNull null
                RefillMachine(
                    machine = machine,
                    trays = machineTrays.map { tray -> RefillTray(tray = tray, fillAmount = tray.deficit) }
                )
            }
            Result.success(refillMachines)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Aggregates one warehouse's positive-quantity stock batches
     * ([WarehouseRepository.fetchWarehouseStock], already filtered to
     * `quantity > 0` for this warehouse) into `productId -> total quantity`.
     * No new query — mirrors iOS `loadWarehouseStock`'s batch aggregation
     * (L1389-1399).
     */
    suspend fun fetchWarehouseStockTotals(warehouseId: String): Result<Map<String, Int>> {
        return try {
            val batches = WarehouseRepository.fetchWarehouseStock(warehouseId).getOrThrow()
            val totals = mutableMapOf<String, Int>()
            for (batch in batches) {
                totals[batch.productId] = (totals[batch.productId] ?: 0) + batch.quantity
            }
            Result.success(totals)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads the warehouse's position-group tree (`warehouse_position_groups`)
     * and its product positions (`warehouse_product_positions`), both
     * ordered by `sort_order`, and flattens them via
     * [RefillTourLogic.flattenPickOrder] into `productId -> pick index`.
     * Mirrors iOS `fetchOrderedProductIds` (L1456-1500).
     *
     * A failure here is deliberately **not** swallowed into an empty
     * success: the caller (a later task) treats [Result.failure] as an
     * empty map and falls back to quantity-based sorting for the packing
     * list, matching iOS `fetchOrderedProductIdsOrEmpty`. Swallowing the
     * error here would make that distinction impossible for the caller to
     * observe.
     */
    suspend fun fetchPickOrder(warehouseId: String): Result<Map<String, Int>> {
        return try {
            val groups = postgrest.from("warehouse_position_groups")
                .select(Columns.raw("id, parent_id, sort_order")) {
                    filter { eq("warehouse_id", warehouseId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<WarehousePositionGroup>()

            val positions = postgrest.from("warehouse_product_positions")
                .select(Columns.raw("product_id, sort_order, group_id")) {
                    filter { eq("warehouse_id", warehouseId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<WarehouseProductPosition>()

            val orderedIds = RefillTourLogic.flattenPickOrder(groups, positions)
            val orderMap = orderedIds.withIndex().associate { (index, productId) -> productId to index }
            Result.success(orderMap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildRefillPlan(machines: List<MachineWithStats>): List<LegacyRefillMachine> {
        return machines
            .filter { machineStats ->
                machineStats.trays.any { it.isLow || it.isCritical }
            }
            .sortedWith(
                compareBy<MachineWithStats> { it.stockHealth.ordinal }
                    .thenByDescending { it.lowTrayCount }
            )
            .map { machineStats ->
                LegacyRefillMachine(
                    machine = machineStats.machine,
                    items = machineStats.trays
                        .filter { it.isLow || it.isCritical }
                        .sortedBy { it.itemNumber }
                        .map { tray ->
                            LegacyRefillItem(
                                tray = tray,
                                targetStock = tray.capacity,
                                fillAmount = tray.deficit
                            )
                        }
                )
            }
    }

    suspend fun applyRefill(machineItems: List<LegacyRefillItem>): Result<Unit> {
        return try {
            machineItems.filter { it.fillAmount > 0 }.forEach { item ->
                val newStock = item.tray.currentStock + item.fillAmount
                postgrest.from("machine_trays")
                    .update({
                        set("current_stock", newStock.coerceAtMost(item.tray.capacity))
                    }) {
                        filter { eq("id", item.tray.id) }
                    }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
