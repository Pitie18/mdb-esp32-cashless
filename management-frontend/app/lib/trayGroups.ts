/**
 * View helpers for the machine detail "Trays & Stock" tab: which product
 * group a slot belongs to, how the slot should be highlighted, and the
 * product-grouped list order. The stock rules themselves live in
 * `stock-health.ts` (`groupTraysByProduct`) — a slot is judged by its
 * product's summed stock, not by its own.
 */

import { groupNeedsRefill, groupTraysByProduct, type GroupableTray, type ProductStockGroup } from './stock-health'

export interface GroupedTray extends GroupableTray {
  id: string
  item_number: number
}

/**
 * - `low`: the product is sold out or at/below its summed min_stock, and this slot has room
 * - `fill`: the product is below its summed fill threshold, and this slot has room
 * - `slotEmpty`: this slot is empty, but the product is fine thanks to other slots
 * - `ok`: nothing to do
 */
export type TrayStockFlag = 'low' | 'fill' | 'slotEmpty' | 'ok'

export interface TrayGroupInfo<T extends GroupedTray = GroupedTray> {
  group: ProductStockGroup<T>
  needsRefill: boolean
  flag: TrayStockFlag
}

/**
 * `linkedSelections`: the machine vends from a sibling slot on its own
 * (`vendingMachine.linked_selections`), so an empty slot of a stocked product
 * is not worth a hint and is flagged `ok`.
 */
export function buildTrayGroupIndex<T extends GroupedTray>(
  trays: T[],
  opts: { linkedSelections?: boolean } = {},
): Map<string, TrayGroupInfo<T>> {
  const index = new Map<string, TrayGroupInfo<T>>()
  for (const group of groupTraysByProduct(trays)) {
    const needsRefill = groupNeedsRefill(group)
    for (const tray of group.trays) {
      index.set(tray.id, { group, needsRefill, flag: trayStockFlag(tray, group, needsRefill, opts.linkedSelections ?? false) })
    }
  }
  return index
}

function trayStockFlag(tray: GroupedTray, group: ProductStockGroup, needsRefill: boolean, linkedSelections: boolean): TrayStockFlag {
  const hasRoom = tray.capacity - tray.current_stock > 0
  if (needsRefill && hasRoom) return group.state === 'fill' ? 'fill' : 'low'
  if (!needsRefill && !linkedSelections && tray.current_stock === 0 && group.current_stock > 0) return 'slotEmpty'
  return 'ok'
}

export interface ProductListRow<T extends GroupedTray> {
  tray: T
  /** Set on the first slot of a product that sits in two or more slots. */
  header?: ProductStockGroup<T>
  /** True for every slot of such a multi-slot product (for indentation). */
  inGroup: boolean
}

/**
 * List order for the "by product" view: products ordered by their lowest slot
 * number, a product's slots right after each other, unassigned slots in their
 * own place. `visible` (e.g. a search result) filters rows but the headers
 * keep the totals of the whole product.
 */
export function productListRows<T extends GroupedTray>(
  trays: T[],
  index: Map<string, TrayGroupInfo<T>>,
  visible: (tray: T) => boolean = () => true,
): ProductListRow<T>[] {
  const bySlot = [...trays].sort((a, b) => a.item_number - b.item_number)
  const rows: ProductListRow<T>[] = []
  const done = new Set<string>()
  for (const tray of bySlot) {
    if (done.has(tray.id)) continue
    const group = index.get(tray.id)?.group
    const members = group ? [...group.trays].sort((a, b) => a.item_number - b.item_number) : [tray]
    const shown = members.filter(visible)
    members.forEach(m => done.add(m.id))
    const multi = members.length > 1
    shown.forEach((m, i) => rows.push({ tray: m, header: multi && i === 0 ? group : undefined, inGroup: multi }))
  }
  return rows
}
