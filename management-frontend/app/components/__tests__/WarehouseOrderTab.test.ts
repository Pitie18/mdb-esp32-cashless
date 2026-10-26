import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'

vi.stubGlobal('useI18n', () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${Object.values(params).join(',')}` : key),
  locale: ref('de'),
}))

import WarehouseOrderTab from '../warehouse/WarehouseOrderTab.vue'
import WarehouseExpectedDeliveries from '../warehouse/WarehouseExpectedDeliveries.vue'
import { buildOrderGroups, DEFAULT_ORDER_SETTINGS, type OrderProductInput, type OrderSupplier } from '@/lib/orderSuggestion'
import type { PurchaseOrder } from '@/composables/usePurchaseOrders'

const becker: OrderSupplier = { id: 's1', name: 'Becker', lead_time_days: 2, order_interval_days: 14, min_order_value: null }

function product(over: Partial<OrderProductInput> = {}): OrderProductInput {
  return {
    product_id: 'p1', product_name: 'Cola', discontinued: false,
    warehouse_qty: 0, machine_qty: 0, avg_daily_sales: 2, on_order: 0,
    unit_size: 24, supplier_id: 's1', unit_price_net: 0.5, ...over,
  }
}

function mountTab(products: OrderProductInput[], isAdmin = true) {
  const groups = buildOrderGroups({ products, suppliers: [becker], manual: [], settings: DEFAULT_ORDER_SETTINGS })
  return mount(WarehouseOrderTab, {
    props: { groups, openOrders: [], suppliers: [becker], settings: DEFAULT_ORDER_SETTINGS, isAdmin, busySupplierId: null },
  })
}

describe('WarehouseOrderTab', () => {
  it('shows the supplier card with cartons and the auto reason', () => {
    const w = mountTab([product()])
    expect(w.text()).toContain('Becker')
    // 2/day × 19 days = 38 → 2 cartons of 24
    expect(w.text()).toContain('warehouse.order.cartonUnits:24,48')
    expect(w.text()).toContain('warehouse.order.autoReason:0,19')
  })

  it('emits the stepper change and placing the order', async () => {
    const w = mountTab([product()])
    const more = w.findAll('button').find(b => b.attributes('aria-label') === 'warehouse.order.more')!
    await more.trigger('click')
    expect(w.emitted('setCartons')?.[0]).toEqual(['p1', 3])
    const place = w.findAll('button').find(b => b.text() === 'warehouse.order.markOrdered')!
    await place.trigger('click')
    expect((w.emitted('place')?.[0]?.[0] as any).supplier.id).toBe('s1')
  })

  it('hides placing and the supplier picker from viewers', () => {
    const w = mountTab([product(), product({ product_id: 'p2', product_name: 'Nuts', supplier_id: null })], false)
    expect(w.findAll('button').some(b => b.text() === 'warehouse.order.markOrdered')).toBe(false)
    expect(w.find('select[aria-label^="warehouse.order.assignSupplierFor"]').exists()).toBe(false)
    expect(w.text()).toContain('warehouse.order.noSupplierHintViewer')
  })

  it('offers the supplier picker to admins in the no-supplier group', async () => {
    const w = mountTab([product({ supplier_id: null })])
    const select = w.find('select[aria-label^="warehouse.order.assignSupplierFor"]')
    await select.setValue('s1')
    expect(w.emitted('assignSupplier')?.[0]).toEqual(['p1', 's1'])
  })

  it('opens the product sheet from the product name', async () => {
    const w = mountTab([product()])
    const open = w.findAll('button').find(b => b.attributes('aria-label') === 'warehouse.order.openProduct:Cola')!
    await open.trigger('click')
    expect(w.emitted('openProduct')?.[0]).toEqual(['p1'])
  })

  it('says when there is nothing to order', () => {
    const w = mountTab([product({ warehouse_qty: 500 })])
    expect(w.text()).toContain('warehouse.order.nothingToOrder')
  })
})

describe('WarehouseExpectedDeliveries', () => {
  const order: PurchaseOrder = {
    id: 'o1', created_at: '2026-10-19T08:00:00Z', warehouse_id: 'w1', supplier_id: 's1', supplier_name: 'Becker',
    status: 'ordered', note: null, ordered_by_name: 'Ada',
    lines: [
      { id: 'l1', product_id: 'p1', product_name: 'Cola', quantity: 48, unit_size: 24, unit_price_net: 0.5, received_quantity: null },
      { id: 'l2', product_id: 'p2', product_name: 'Mate', quantity: 6, unit_size: 1, unit_price_net: null, received_quantity: null },
    ],
  }

  it('prefills the ordered quantities and books what was entered', async () => {
    const w = mount(WarehouseExpectedDeliveries, { props: { orders: [order], focusOrderId: 'o1', busyOrderId: null } })
    const qty = w.findAll('input[inputmode="numeric"]')
    expect((qty[0]!.element as HTMLInputElement).value).toBe('48')
    await qty[0]!.setValue('47')
    await w.findAll('input[type="date"]')[0]!.setValue('2027-03-31')
    await w.findAll('button').find(b => b.text() === 'warehouse.order.bookAll')!.trigger('click')
    expect(w.emitted('receive')?.[0]?.[1]).toEqual([
      { line_id: 'l1', quantity: 47, expiration_date: '2027-03-31', batch_number: null },
      { line_id: 'l2', quantity: 6, expiration_date: null, batch_number: null },
    ])
  })

  it('closes without booking', async () => {
    const w = mount(WarehouseExpectedDeliveries, { props: { orders: [order], focusOrderId: 'o1', busyOrderId: null } })
    await w.findAll('button').find(b => b.text() === 'warehouse.order.closeWithoutBooking')!.trigger('click')
    expect(w.emitted('close')?.[0]?.[0]).toEqual(order)
  })
})
