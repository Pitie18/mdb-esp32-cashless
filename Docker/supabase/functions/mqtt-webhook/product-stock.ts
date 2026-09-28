/**
 * Product-level stock for push notifications.
 *
 * A machine can hold the same product in several slots (spirals). Like the
 * clients (`management-frontend/app/lib/stock-health.ts`), the webhook judges
 * stock per product: stock, capacity and both thresholds are summed over the
 * product's slots in the machine. Cola at 0/2/9 is 11 Cola, so an empty or
 * low slot alone does not raise a low-stock push.
 */

import { stockUrgency } from './stock-urgency.ts'

export interface SlotStock {
  item_number: number
  current_stock: number
  capacity: number
  min_stock: number | null
  fill_when_below: number | null
}

export interface ProductStock {
  current_stock: number
  capacity: number
  min_stock: number
  fill_when_below: number
  slotCount: number
}

export function summarizeProductStock(slots: SlotStock[]): ProductStock {
  const sum: ProductStock = { current_stock: 0, capacity: 0, min_stock: 0, fill_when_below: 0, slotCount: slots.length }
  for (const s of slots) {
    sum.current_stock += s.current_stock ?? 0
    sum.capacity += s.capacity ?? 0
    sum.min_stock += s.min_stock ?? 0
    sum.fill_when_below += s.fill_when_below ?? 0
  }
  return sum
}

/**
 * Whether this sale just pushed the product below a line worth a low-stock
 * push. Runs after the sale's stock decrement (one unit), so the product
 * crossed its summed `min_stock` exactly when the total now equals it, and
 * sold out everywhere when it is 0. Firing only on those two edges sends one
 * alert per product and cycle instead of one on every sale below the line.
 * A disabled threshold (sum 0) never alerts, as before.
 */
export function lowStockCrossed(product: ProductStock): boolean {
  if (product.min_stock <= 0) return false
  return product.current_stock === product.min_stock || product.current_stock === 0
}

export interface StockLineStrings {
  left: string
  inMachine: string
  slot: string
  refillAt: (threshold: number) => string
}

/**
 * Stock line of the sale push. One slot: "⚠️ 2/10 left — refill at 5", as
 * before. Several slots: the product total leads and the sold slot follows,
 * "🟡 11/30 in machine · Slot 13: 2/10", urgency judged on the total.
 */
export function saleStockLine(product: ProductStock, sold: SlotStock, s: StockLineStrings): string {
  if (product.slotCount <= 1) {
    const threshold = sold.fill_when_below ?? 0
    const hint = threshold > 0 ? ` — ${s.refillAt(threshold)}` : ''
    return `${stockUrgency(sold.current_stock, threshold)}${sold.current_stock}/${sold.capacity} ${s.left}${hint}`
  }
  const emoji = stockUrgency(product.current_stock, product.fill_when_below)
  return `${emoji}${product.current_stock}/${product.capacity} ${s.inMachine} · ${s.slot} ${sold.item_number}: ${sold.current_stock}/${sold.capacity}`
}
