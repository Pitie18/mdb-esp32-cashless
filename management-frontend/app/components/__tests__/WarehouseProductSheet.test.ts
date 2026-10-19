import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'

vi.stubGlobal('useI18n', () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${Object.values(params).join(',')}` : key),
  locale: ref('de'),
}))

import WarehouseProductSheet from '../warehouse/WarehouseProductSheet.vue'
import type { StockBatch, WarehouseProductSummary } from '@/composables/useWarehouse'

const SUMMARY: WarehouseProductSummary = {
  product_id: 'p1', product_name: 'Snickers 50 g', product_image_path: null,
  total_quantity: 14, min_stock: 12, is_below_min: false, earliest_expiration: '2030-01-20',
  expiration_status: 'ok', batch_count: 2, discontinued: false, avg_daily_sales: 1.8, estimated_days_remaining: 8,
}
const BATCHES: StockBatch[] = [
  { id: 'b1', warehouse_id: 'w1', product_id: 'p1', product_name: 'Snickers 50 g', product_image_path: null, batch_number: 'L1', expiration_date: '2030-01-20', quantity: 4 },
  { id: 'b2', warehouse_id: 'w1', product_id: 'p1', product_name: 'Snickers 50 g', product_image_path: null, batch_number: null, expiration_date: '2030-03-01', quantity: 10 },
] as StockBatch[]

function mountSheet(isAdmin = true) {
  return mount(WarehouseProductSheet, {
    attachTo: document.body,
    props: { open: true, summary: SUMMARY, batches: BATCHES, machineStock: 11, assigned: true, isAdmin },
    global: { stubs: { NuxtLink: { template: '<a><slot /></a>' } } },
  })
}

describe('WarehouseProductSheet', () => {
  beforeEach(() => { document.body.innerHTML = '' })

  it('lists every batch with its quantity', async () => {
    mountSheet()
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('warehouse.unitsShort:4')
    expect(text).toContain('warehouse.unitsShort:10')
    expect(text).toContain('warehouse.noBatchNumber')
  })

  it('emits adjust with the batch', async () => {
    const wrapper = mountSheet()
    await flushPromises()
    const buttons = [...document.body.querySelectorAll('button')].filter(b => b.textContent?.includes('warehouse.adjust'))
    expect(buttons).toHaveLength(2)
    buttons[1]!.click()
    expect(wrapper.emitted('adjust')?.[0]).toEqual([BATCHES[1]])
  })

  it('saves a changed minimum stock', async () => {
    const wrapper = mountSheet()
    await flushPromises()
    const input = document.body.querySelector('#sheet-min-stock') as HTMLInputElement
    input.value = '20'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const save = [...document.body.querySelectorAll('button')].find(b => b.textContent?.trim() === 'common.save')!
    save.click()
    expect(wrapper.emitted('saveMinStock')?.[0]).toEqual([20])
  })

  it('emits the discontinued toggle', async () => {
    const wrapper = mountSheet()
    await flushPromises()
    ;(document.body.querySelector('[role="switch"]') as HTMLElement).click()
    expect(wrapper.emitted('toggleDiscontinued')?.[0]).toEqual([true])
  })

  it('hides editing for viewers', async () => {
    mountSheet(false)
    await flushPromises()
    expect(document.body.querySelector('#sheet-min-stock')).toBeNull()
    expect(document.body.querySelector('[role="switch"]')).toBeNull()
    expect([...document.body.querySelectorAll('button')].some(b => b.textContent?.includes('warehouse.adjust'))).toBe(false)
  })
})
