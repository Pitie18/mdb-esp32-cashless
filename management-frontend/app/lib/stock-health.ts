/**
 * Shared warehouse-aware stock health utilities.
 *
 * Used by useMachines composable (full detail), the refill wizard and the
 * dashboard (summary counts).
 *
 * Stock is judged per **product within a machine**, not per slot: a product
 * that sits in several spirals is one group whose stock, capacity and
 * thresholds are the sums over its slots (`groupTraysByProduct`). Cola in
 * slots 12/13/14 at 0/2/9 is 11 Cola, not one sold-out slot. A slot that is
 * empty while its product is still in another slot is only a hint
 * (`emptySlotsWithStock`), never a critical machine.
 *
 * Ported 1:1 to iOS (`MachineStockHealth.swift`) and Android
 * (`StockHealth.kt`) — change all three together.
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

// ── Product groups (all slots of one product in one machine) ─────────

export interface GroupableTray {
  machine_id: string
  product_id: string | null
  capacity: number
  current_stock: number
  min_stock: number
  fill_when_below: number
}

export interface ProductStockGroup<T extends GroupableTray = GroupableTray> {
  machine_id: string
  product_id: string
  /** The slots holding this product, in input order. */
  trays: T[]
  /** Σ over the slots — same field names as a tray so `classifyTrayStock` applies. */
  current_stock: number
  capacity: number
  min_stock: number
  fill_when_below: number
  state: TrayStockState
  /** Units needed to fill every slot of the product. */
  deficit: number
  /** Slots at 0 while the product still has stock in another slot. */
  emptySlots: number
}

/**
 * Group assigned trays by (machine, product) and classify each group on the
 * summed values. Thresholds add up, so a product in one slot classifies
 * exactly like before and no per-product configuration is needed. A disabled
 * (0) threshold on one slot simply contributes nothing to the sum.
 * Unassigned trays are skipped. Groups keep first-appearance order.
 */
export function groupTraysByProduct<T extends GroupableTray>(trays: T[]): ProductStockGroup<T>[] {
  const groups = new Map<string, ProductStockGroup<T>>()
  for (const tray of trays) {
    if (tray.product_id == null) continue
    const key = `${tray.machine_id}|${tray.product_id}`
    let g = groups.get(key)
    if (!g) {
      g = { machine_id: tray.machine_id, product_id: tray.product_id, trays: [], current_stock: 0, capacity: 0, min_stock: 0, fill_when_below: 0, state: 'ok', deficit: 0, emptySlots: 0 }
      groups.set(key, g)
    }
    g.trays.push(tray)
    g.current_stock += tray.current_stock
    g.capacity += tray.capacity
    g.min_stock += tray.min_stock ?? 0
    g.fill_when_below += tray.fill_when_below ?? 0
  }
  for (const g of groups.values()) {
    g.state = classifyTrayStock(g)
    g.deficit = Math.max(0, g.capacity - g.current_stock)
    g.emptySlots = g.current_stock > 0 ? g.trays.filter(t => t.current_stock === 0).length : 0
  }
  return Array.from(groups.values())
}

/**
 * Whether a product group should be refilled. A `fill`-tier group that is
 * already full (misconfigured `fill_when_below >= capacity`) moves nothing.
 */
export function groupNeedsRefill(group: Pick<ProductStockGroup, 'state' | 'deficit'>): boolean {
  if (group.state === 'ok') return false
  if (group.state === 'fill' && group.deficit <= 0) return false
  return true
}

/**
 * Split `amount` units of one product across its slots, emptiest slot first
 * (ties by slot number), so no selection stays sold out while another slot of
 * the same product gets topped up. Returns fill amounts aligned with `slots`.
 */
export function distributeAcrossSlots(
  slots: { item_number: number; capacity: number; current_stock: number }[],
  amount: number,
): number[] {
  const result = slots.map(() => 0)
  const order = slots
    .map((s, i) => ({ s, i }))
    .sort((a, b) => a.s.current_stock - b.s.current_stock || a.s.item_number - b.s.item_number)
  let remaining = Math.max(0, amount)
  for (const { s, i } of order) {
    if (remaining <= 0) break
    const take = Math.min(Math.max(0, s.capacity - s.current_stock), remaining)
    result[i] = take
    remaining -= take
  }
  return result
}

// ── Simple per-machine stock health (used by dashboard) ──────────────

/** Counts are **products** (groups), not slots. */
export interface MachineStockSummary {
  refillableEmpty: number
  refillableLow: number
  refillableFill: number
  noStockCount: number
  noStockEmptyCount: number
  /** Empty slots whose product is otherwise fine in this machine — a hint, not a health input. */
  emptySlotsWithStock: number
  totalStock: number
  totalCapacity: number
  health: 'ok' | 'low' | 'fill' | 'critical'
  percent: number
}

interface TrayRow {
  machine_id: string
  product_id: string | null
  capacity: number
  current_stock: number
  min_stock: number
  fill_when_below: number
}

/**
 * Compute warehouse-aware stock health per machine from raw tray rows.
 *
 * - Trays are grouped per product (`groupTraysByProduct`); every count is a product.
 * - Trays without a product (`product_id == null`) only count towards the fill percentage.
 * - Groups needing refill are split into "refillable" (product available in
 *   warehouse) and "no-stock" (not available).
 * - `health` is determined only by refillable groups, priority critical > low > fill.
 */
export function computeStockHealthPerMachine(
  trayRows: TrayRow[],
  warehouseStockMap: Map<string, number>,
  hasWarehouses: boolean,
  /** Machines that vend from a sibling slot on their own (`vendingMachine.linked_selections`) — their empty slots are no hint. */
  linkedMachineIds: ReadonlySet<string> = new Set(),
): Map<string, MachineStockSummary> {
  const map = new Map<string, MachineStockSummary>()
  const entryFor = (machineId: string) => {
    let entry = map.get(machineId)
    if (!entry) {
      entry = { refillableEmpty: 0, refillableLow: 0, refillableFill: 0, noStockCount: 0, noStockEmptyCount: 0, emptySlotsWithStock: 0, totalStock: 0, totalCapacity: 0, health: 'ok', percent: 100 }
      map.set(machineId, entry)
    }
    return entry
  }

  for (const tray of trayRows) {
    if (!tray.machine_id) continue
    const entry = entryFor(tray.machine_id)
    entry.totalStock += tray.current_stock
    entry.totalCapacity += tray.capacity
  }

  for (const group of groupTraysByProduct(trayRows.filter(t => t.machine_id))) {
    const entry = entryFor(group.machine_id)
    if (!groupNeedsRefill(group)) {
      if (!linkedMachineIds.has(group.machine_id)) entry.emptySlotsWithStock += group.emptySlots
      continue
    }

    const refillable = isProductRefillable(group.product_id, warehouseStockMap, hasWarehouses)
    if (refillable) {
      if (group.state === 'critical') entry.refillableEmpty++
      else if (group.state === 'low') entry.refillableLow++
      else entry.refillableFill++
    } else {
      entry.noStockCount++
      if (group.state === 'critical') entry.noStockEmptyCount++
    }
  }

  // Derive health + percent
  for (const entry of map.values()) {
    entry.health = entry.refillableEmpty > 0
      ? 'critical'
      : entry.refillableLow > 0
        ? 'low'
        : entry.refillableFill > 0
          ? 'fill'
          : 'ok'
    entry.percent = entry.totalCapacity > 0
      ? Math.round((entry.totalStock / entry.totalCapacity) * 100)
      : 100
  }

  return map
}

// ── Fleet-wide bucket counts (used by the dashboard banner + KPI card) ────────

export interface MachineStockBuckets {
  critical: number
  low: number
  fill: number
  /** Machines that are otherwise fine but hold an empty tray whose product the warehouse can't refill. */
  swap: number
  /** Machines in exactly one of the buckets above — the banner counts machines, not reasons. */
  needingAttention: number
}

/**
 * Fold per-machine summaries into disjoint fleet-wide buckets.
 *
 * Each machine lands in at most one bucket, so the buckets sum to the number of
 * machines needing attention and can never exceed the fleet size.
 */
export function countMachineStockBuckets(
  summaries: Iterable<MachineStockSummary>,
): MachineStockBuckets {
  const buckets: MachineStockBuckets = { critical: 0, low: 0, fill: 0, swap: 0, needingAttention: 0 }

  for (const stock of summaries) {
    if (stock.health === 'critical') buckets.critical++
    else if (stock.health === 'low') buckets.low++
    else if (stock.health === 'fill') buckets.fill++
    else if (stock.noStockEmptyCount > 0) buckets.swap++
    else continue
    buckets.needingAttention++
  }

  return buckets
}
