package xyz.vmflow.data

import io.github.jan.supabase.postgrest.postgrest
import xyz.vmflow.models.MachineWithStats
import xyz.vmflow.models.LegacyRefillItem
import xyz.vmflow.models.LegacyRefillMachine

object RefillRepository {
    private val postgrest get() = SupabaseService.client.postgrest

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
