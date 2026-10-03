/**
 * Run: deno test Docker/supabase/functions/mqtt-webhook/product-stock.test.ts
 */

import { assertEquals } from 'jsr:@std/assert'
import { lowStockCrossed, saleStockLine, summarizeProductStock } from './product-stock.ts'
import type { SlotStock } from './product-stock.ts'

const slot = (item_number: number, current_stock: number, over: Partial<SlotStock> = {}): SlotStock =>
  ({ item_number, current_stock, capacity: 10, min_stock: 2, fill_when_below: 5, ...over })

const strings = { left: 'left', inMachine: 'in machine', slot: 'Slot', refillAt: (n: number) => `refill at ${n}` }

Deno.test('summarizeProductStock: sums stock, capacity and both thresholds', () => {
  assertEquals(
    summarizeProductStock([slot(12, 0), slot(13, 2), slot(14, 9)]),
    { current_stock: 11, capacity: 30, min_stock: 6, fill_when_below: 15, slotCount: 3 },
  )
})

Deno.test('summarizeProductStock: treats null thresholds as disabled', () => {
  const p = summarizeProductStock([slot(12, 3, { min_stock: null, fill_when_below: null }), slot(13, 3)])
  assertEquals([p.min_stock, p.fill_when_below], [2, 5])
})

Deno.test('lowStockCrossed: a low slot next to a stocked one does not alert', () => {
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0), slot(13, 2), slot(14, 9)])), false)
})

Deno.test('lowStockCrossed: alerts once when the product total reaches the summed min_stock', () => {
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0), slot(13, 4), slot(14, 2)])), true) // 6 = 2+2+2
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0), slot(13, 3), slot(14, 2)])), false) // 5, already alerted at 6
})

Deno.test('lowStockCrossed: alerts again when the product is sold out everywhere', () => {
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0), slot(13, 0)])), true)
})

Deno.test('lowStockCrossed: never alerts with a disabled threshold', () => {
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0, { min_stock: 0 })])), false)
})

Deno.test('lowStockCrossed: single slot alerts at min_stock and at 0', () => {
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 2)])), true)
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 1)])), false)
  assertEquals(lowStockCrossed(summarizeProductStock([slot(12, 0)])), true)
})

Deno.test('saleStockLine: single slot keeps the old format', () => {
  assertEquals(saleStockLine(summarizeProductStock([slot(12, 2)]), slot(12, 2), strings), '⚠️ 2/10 left — refill at 5')
  assertEquals(saleStockLine(summarizeProductStock([slot(12, 9, { fill_when_below: 0 })]), slot(12, 9, { fill_when_below: 0 }), strings), '9/10 left')
})

Deno.test('saleStockLine: several slots lead with the product total and judge urgency on it', () => {
  const slots = [slot(12, 0), slot(13, 2), slot(14, 9)]
  assertEquals(saleStockLine(summarizeProductStock(slots), slots[1]!, strings), '⚠️ 11/30 in machine · Slot 13: 2/10')
  const full = [slot(12, 0), slot(13, 10), slot(14, 10)]
  assertEquals(saleStockLine(summarizeProductStock(full), full[0]!, strings), '🟡 20/30 in machine · Slot 12: 0/10')
})
