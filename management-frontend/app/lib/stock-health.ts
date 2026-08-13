/**
 * Shared warehouse-aware stock health utilities.
 *
 * Used by useMachines composable (full detail) and the dashboard (summary counts).
 */

export interface WarehouseStockInfo {
  /** Aggregated available quantity per product_id */
  warehouseStockMap: Map<string, number>
  /** True when at least one batch with qty > 0 exists (= warehouse feature is active) */
  hasWarehouses: boolean
}

/**
 * Build a map of product_id → total available warehouse quantity
 * from raw `warehouse_stock_batches` rows (pre-filtered with `.gt('quantity', 0)`).
 */
export function buildWarehouseStockInfo(
  batchRows: { product_id: string; quantity: number }[],
): WarehouseStockInfo {
  const warehouseStockMap = new Map<string, number>()
  for (const row of batchRows) {
    if (!row.product_id) continue
    warehouseStockMap.set(
      row.product_id,
      (warehouseStockMap.get(row.product_id) ?? 0) + row.quantity,
    )
  }
  return { warehouseStockMap, hasWarehouses: batchRows.length > 0 }
}

/**
 * Check whether a product is considered "refillable" — i.e. available in warehouse.
 *
 * When no warehouse data exists (`hasWarehouses === false`), all products are
 * treated as refillable for backward compatibility.
 */
export function isProductRefillable(
  productId: string | null,
  warehouseStockMap: Map<string, number>,
  hasWarehouses: boolean,
): boolean {
  if (productId == null) return false
  return !hasWarehouses || warehouseStockMap.has(productId)
}

export type TrayStockState = 'critical' | 'low' | 'fill' | 'ok'

/**
 * Classify a single tray's stock state against its two independent thresholds.
 * A threshold of 0 means "disabled" and is skipped.
 */
export function classifyTrayStock(tray: {
  current_stock: number
  min_stock: number
  fill_when_below: number
}): TrayStockState {
  if (tray.current_stock === 0) return 'critical'
  if (tray.min_stock > 0 && tray.current_stock <= tray.min_stock) return 'low'
  if (tray.fill_when_below > 0 && tray.current_stock <= tray.fill_when_below) return 'fill'
  return 'ok'
}

// ── Simple per-machine stock health (used by dashboard) ──────────────

export interface MachineStockSummary {
  refillableEmpty: number
  refillableLow: number
  noStockCount: number
  noStockEmptyCount: number
  totalStock: number
  totalCapacity: number
  health: 'ok' | 'low' | 'critical'
  percent: number
}

interface TrayRow {
  machine_id: string
  product_id: string | null
  capacity: number
  current_stock: number
  min_stock: number
}

/**
 * Compute warehouse-aware stock health per machine from raw tray rows.
 *
 * - Trays without a product (`product_id == null`) are ignored.
 * - Low/empty trays are split into "refillable" (product available in warehouse)
 *   and "no-stock" (not available).
 * - `health` is determined only by refillable trays.
 */
export function computeStockHealthPerMachine(
  trayRows: TrayRow[],
  warehouseStockMap: Map<string, number>,
  hasWarehouses: boolean,
): Map<string, MachineStockSummary> {
  const map = new Map<string, MachineStockSummary>()

  for (const tray of trayRows) {
    if (!tray.machine_id) continue

    let entry = map.get(tray.machine_id)
    if (!entry) {
      entry = { refillableEmpty: 0, refillableLow: 0, noStockCount: 0, noStockEmptyCount: 0, totalStock: 0, totalCapacity: 0, health: 'ok', percent: 100 }
      map.set(tray.machine_id, entry)
    }

    entry.totalStock += tray.current_stock
    entry.totalCapacity += tray.capacity

    const isEmpty = tray.current_stock === 0
    const isLow = !isEmpty && tray.min_stock > 0 && tray.current_stock <= tray.min_stock

    if (isEmpty || isLow) {
      // Skip unassigned trays — nothing to refill
      if (tray.product_id == null) continue

      const refillable = isProductRefillable(tray.product_id, warehouseStockMap, hasWarehouses)
      if (refillable) {
        if (isEmpty) entry.refillableEmpty++
        else entry.refillableLow++
      } else {
        entry.noStockCount++
        if (isEmpty) entry.noStockEmptyCount++
      }
    }
  }

  // Derive health + percent
  for (const entry of map.values()) {
    entry.health = entry.refillableEmpty > 0 ? 'critical' : (entry.refillableLow > 0 ? 'low' : 'ok')
    entry.percent = entry.totalCapacity > 0
      ? Math.round((entry.totalStock / entry.totalCapacity) * 100)
      : 100
  }

  return map
}
