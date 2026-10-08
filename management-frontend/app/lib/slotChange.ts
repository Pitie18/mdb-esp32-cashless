/**
 * Slot re-assignment ("Fächer umbelegen") — pure planning and tour maths.
 *
 * The office plans a new layout (which product goes into which spiral, and
 * the spiral's capacity) from hints computed here; saving stores a change
 * request (`save_slot_change_request`), never a tray update. The next refill
 * tour packs what the new slots need (`rebuildPackNeeds`), suggests how to fill
 * them at the machine (`fillPlan`) and books what is left over
 * (`computeLeftovers`) through `apply_slot_change`.
 *
 * Bookkeeping rule: until the refiller quits the rebuild at the machine the
 * old product stays in the slot, so sales keep decrementing it. The units
 * actually taken out ("removed") are counted at the machine; they go into the
 * new slots of the same product first, and only the rest comes from the van.
 *
 * Ported to iOS (`SlotChange.swift`) and Android (`SlotChange.kt`) — change
 * all three together.
 */

import { computeSlotWidths } from '@/composables/useMachineAnalysis'

// ── Planning ────────────────────────────────────────────────────────────────

export interface PlanTray {
  id: string
  item_number: number
  product_id: string | null
  capacity: number
  current_stock: number
}

export interface PlanSlot {
  product_id: string | null
  capacity: number
}

/** tray id → what the slot holds in the plan. */
export type SlotPlan = Record<string, PlanSlot>

export interface PlanChange {
  tray_id: string
  item_number: number
  from_product_id: string | null
  to_product_id: string | null
  from_capacity: number
  to_capacity: number
}

export function planFromTrays(trays: PlanTray[]): SlotPlan {
  const plan: SlotPlan = {}
  for (const t of trays) plan[t.id] = { product_id: t.product_id, capacity: t.capacity }
  return plan
}

/** Slots whose product or capacity differs from today, by slot number. */
export function planChanges(trays: PlanTray[], plan: SlotPlan): PlanChange[] {
  return trays
    .filter((t) => {
      const p = plan[t.id]
      return p && (p.product_id !== t.product_id || p.capacity !== t.capacity)
    })
    .sort((a, b) => a.item_number - b.item_number)
    .map((t) => ({
      tray_id: t.id,
      item_number: t.item_number,
      from_product_id: t.product_id,
      to_product_id: plan[t.id]!.product_id,
      from_capacity: t.capacity,
      to_capacity: plan[t.id]!.capacity,
    }))
}

export interface ProductReach {
  product_id: string
  /** Slot numbers holding the product in the plan, ascending. */
  slots: number[]
  capacity: number
  /** Units sold per day. */
  rate: number
  /** Days a full machine lasts for this product (Infinity when it doesn't sell). */
  reachDays: number
}

/** Per product in the plan: its slots, total capacity and how long that lasts. */
export function productReach(
  trays: PlanTray[],
  plan: SlotPlan,
  rates: Map<string, number>,
): Map<string, ProductReach> {
  const out = new Map<string, ProductReach>()
  for (const t of [...trays].sort((a, b) => a.item_number - b.item_number)) {
    const slot = plan[t.id]
    if (!slot?.product_id) continue
    let r = out.get(slot.product_id)
    if (!r) {
      r = { product_id: slot.product_id, slots: [], capacity: 0, rate: rates.get(slot.product_id) ?? 0, reachDays: Infinity }
      out.set(slot.product_id, r)
    }
    r.slots.push(t.item_number)
    r.capacity += slot.capacity
  }
  for (const r of out.values()) r.reachDays = r.rate > 0 ? r.capacity / r.rate : Infinity
  return out
}

export interface SlotNeed extends ProductReach {
  /** Spirals missing so the product lasts `targetDays`. */
  extra: number
}

/**
 * Products that run out before `targetDays`, most urgent first, with how many
 * more spirals of their average size they would need.
 */
export function slotNeeds(
  trays: PlanTray[],
  plan: SlotPlan,
  rates: Map<string, number>,
  targetDays: number,
): SlotNeed[] {
  const out: SlotNeed[] = []
  for (const r of productReach(trays, plan, rates).values()) {
    if (r.reachDays >= targetDays) continue
    const avgCap = r.capacity / r.slots.length
    const extra = Math.max(1, Math.ceil((r.rate * targetDays) / avgCap) - r.slots.length)
    out.push({ ...r, extra })
  }
  return out.sort((a, b) => a.reachDays - b.reachDays)
}

/**
 * Products in the machine today that sold at most `maxUnits` in the analysis
 * window. Products still in their test period (offered fewer than
 * `graceDays`) are never candidates. Slowest first.
 */
export function removalCandidates(
  trays: PlanTray[],
  unitsInWindow: Map<string, number>,
  tenureDays: Map<string, number | null>,
  opts: { maxUnits?: number; graceDays?: number } = {},
): string[] {
  const maxUnits = opts.maxUnits ?? 4
  const grace = opts.graceDays ?? 14
  const ids = [...new Set(trays.map(t => t.product_id).filter((id): id is string => !!id))]
  return ids
    .filter((id) => {
      const tenure = tenureDays.get(id)
      if (tenure != null && tenure < grace) return false
      return (unitsInWindow.get(id) ?? 0) <= maxUnits
    })
    .sort((a, b) => (unitsInWindow.get(a) ?? 0) - (unitsInWindow.get(b) ?? 0))
}

/** The product that runs out first after a full refill — when the next visit is due. */
export function firstEmpty(
  trays: PlanTray[],
  plan: SlotPlan,
  rates: Map<string, number>,
): { product_id: string; reachDays: number } | null {
  let best: { product_id: string; reachDays: number } | null = null
  for (const r of productReach(trays, plan, rates).values()) {
    if (!best || r.reachDays < best.reachDays) best = { product_id: r.product_id, reachDays: r.reachDays }
  }
  return best
}

/**
 * True when the product sits only in slots wider than the target slot today —
 * a hint that it may not fit there (products carry no width of their own).
 */
export function widthWarning(trays: PlanTray[], productId: string, targetTrayId: string): boolean {
  const widths = computeSlotWidths(trays.map(t => t.item_number))
  const current = trays.filter(t => t.product_id === productId && t.id !== targetTrayId)
  if (current.length === 0) return false
  const minWidth = Math.min(...current.map(t => widths.get(t.item_number) ?? 1))
  const target = trays.find(t => t.id === targetTrayId)
  if (!target) return false
  return minWidth > (widths.get(target.item_number) ?? 1)
}

export function priceChanges(
  rows: { item_number: number; to_product_id: string | null; from_price: number | null; to_price: number | null }[],
): { item_number: number; price: number }[] {
  return rows
    .filter(r => r.to_product_id && r.to_price != null && r.to_price !== r.from_price)
    .map(r => ({ item_number: r.item_number, price: r.to_price! }))
    .sort((a, b) => a.item_number - b.item_number)
}

// ── Tour ────────────────────────────────────────────────────────────────────

export interface ChangeItem {
  id: string
  tray_id: string
  item_number: number
  from_product_id: string | null
  to_product_id: string | null
  from_capacity: number
  to_capacity: number
}

/**
 * Units to pack for the rebuild, per product: the capacity of its new slots
 * minus what comes out of the changed slots that held it (that stock moves
 * over). `stockByTray` is the stock known at packing time. Products whose new
 * slots are fully covered by moved stock are listed with 0.
 */
export function rebuildPackNeeds(items: ChangeItem[], stockByTray: Map<string, number>): Map<string, number> {
  const need = new Map<string, number>()
  for (const it of items) {
    if (!it.to_product_id) continue
    need.set(it.to_product_id, (need.get(it.to_product_id) ?? 0) + it.to_capacity)
  }
  for (const it of items) {
    if (!it.from_product_id || !need.has(it.from_product_id)) continue
    need.set(it.from_product_id, need.get(it.from_product_id)! - (stockByTray.get(it.tray_id) ?? 0))
  }
  for (const [k, v] of need) need.set(k, Math.max(0, v))
  return need
}

export interface FillSuggestion {
  /** Units that came out of another changed slot. */
  moved: number
  /** Units from the van. */
  van: number
  total: number
}

/**
 * Suggested fill per rebuilt slot, lowest slot number first: stock taken out
 * of the changed slots goes in first, the van covers the rest.
 */
export function fillPlan(
  items: ChangeItem[],
  removedByItem: Map<string, number>,
  vanByProduct: Map<string, number>,
): Map<string, FillSuggestion> {
  const freed = new Map<string, number>()
  for (const it of items) {
    if (!it.from_product_id) continue
    freed.set(it.from_product_id, (freed.get(it.from_product_id) ?? 0) + (removedByItem.get(it.id) ?? 0))
  }
  const van = new Map(vanByProduct)
  const out = new Map<string, FillSuggestion>()
  for (const it of [...items].sort((a, b) => a.item_number - b.item_number)) {
    if (!it.to_product_id) {
      out.set(it.id, { moved: 0, van: 0, total: 0 })
      continue
    }
    const moved = Math.min(it.to_capacity, freed.get(it.to_product_id) ?? 0)
    freed.set(it.to_product_id, (freed.get(it.to_product_id) ?? 0) - moved)
    const fromVan = Math.min(it.to_capacity - moved, van.get(it.to_product_id) ?? 0)
    van.set(it.to_product_id, (van.get(it.to_product_id) ?? 0) - fromVan)
    out.set(it.id, { moved, van: fromVan, total: moved + fromVan })
  }
  return out
}

export interface ItemOutcome {
  action: 'done' | 'skip'
  removed: number
  filled: number
}

/**
 * What is left over per product after the rebuild, split by origin:
 * `van` = packed for the rebuild but not used (goes back to its batch),
 * `machine` = taken out of a slot and not put into another one (new batch).
 * Moved stock is used before van stock, matching `fillPlan`. Only products
 * with something left are returned.
 */
export function computeLeftovers(
  items: ChangeItem[],
  outcome: Map<string, ItemOutcome>,
  packedByProduct: Map<string, number>,
): Map<string, { van: number; machine: number }> {
  const freed = new Map<string, number>()
  const filledInto = new Map<string, number>()
  for (const it of items) {
    const o = outcome.get(it.id)
    if (!o || o.action !== 'done') continue
    if (it.from_product_id) freed.set(it.from_product_id, (freed.get(it.from_product_id) ?? 0) + Math.max(0, o.removed))
    if (it.to_product_id) filledInto.set(it.to_product_id, (filledInto.get(it.to_product_id) ?? 0) + Math.max(0, o.filled))
  }
  const products = new Set([...freed.keys(), ...filledInto.keys(), ...packedByProduct.keys()])
  const out = new Map<string, { van: number; machine: number }>()
  for (const pid of products) {
    const f = freed.get(pid) ?? 0
    const filled = filledInto.get(pid) ?? 0
    const packed = packedByProduct.get(pid) ?? 0
    const moved = Math.min(f, filled)
    const vanUsed = Math.min(packed, filled - moved)
    const left = { van: packed - vanUsed, machine: f - moved }
    if (left.van + left.machine > 0) out.set(pid, left)
  }
  return out
}
