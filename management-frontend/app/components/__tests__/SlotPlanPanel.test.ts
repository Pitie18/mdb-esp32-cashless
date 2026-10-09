import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'

// The planner only saves a change request — it must never write machine_trays.
const { saveRequest, withdrawRequest, fetchOpenRequest, trayWrites } = vi.hoisted(() => ({
  saveRequest: vi.fn(async () => 'req-1'),
  withdrawRequest: vi.fn(async () => {}),
  fetchOpenRequest: vi.fn(async () => null as unknown),
  trayWrites: [] as unknown[],
}))

vi.mock('@/composables/useSlotChangeRequests', () => ({
  useSlotChangeRequests: () => ({ fetchOpenRequest, saveRequest, withdrawRequest }),
}))
vi.mock('@/composables/useOrganization', () => ({
  useOrganization: () => ({ organization: ref({ id: 'company-1' }) }),
}))

const TRAYS = [
  { id: 't11', item_number: 11, product_id: 'cola', capacity: 10, current_stock: 3 },
  { id: 't12', item_number: 12, product_id: 'tictac', capacity: 10, current_stock: 9 },
  { id: 't13', item_number: 13, product_id: 'snickers', capacity: 12, current_stock: 6 },
]
const PRODUCTS = [
  { id: 'cola', name: 'Cola', image_path: null, sellprice: 2, discontinued: false },
  { id: 'tictac', name: 'Tic Tac', image_path: null, sellprice: 1, discontinued: false },
  { id: 'snickers', name: 'Snickers', image_path: null, sellprice: 1.5, discontinued: false },
]
const KPIS = {
  products: [
    { product_id: 'cola', units_sold: 90, revenue_eur: 180, offered_since: '2026-01-01T00:00:00Z' },
    { product_id: 'tictac', units_sold: 1, revenue_eur: 1, offered_since: '2026-01-01T00:00:00Z' },
    { product_id: 'snickers', units_sold: 30, revenue_eur: 45, offered_since: '2026-01-01T00:00:00Z' },
  ],
}

function query(data: unknown) {
  const q: any = {
    select: () => q, eq: () => q, order: () => q, in: () => q,
    update: (v: unknown) => { trayWrites.push(v); return q },
    then: (res: (v: unknown) => unknown) => Promise.resolve({ data, error: null }).then(res),
  }
  return q
}
const supabase = {
  from: (table: string) => query(table === 'machine_trays' ? TRAYS : table === 'products' ? PRODUCTS : []),
  rpc: (fn: string) => Promise.resolve({ data: fn === 'get_machine_product_kpis' ? KPIS : [], error: null }),
}

vi.stubGlobal('useSupabaseClient', () => supabase)
vi.stubGlobal('useRuntimeConfig', () => ({ public: { supabase: { url: 'http://x' } } }))
vi.stubGlobal('useI18n', () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
  locale: ref('de'),
}))

import SlotPlanPanel from '../slotplan/SlotPlanPanel.vue'

describe('SlotPlanPanel', () => {
  beforeEach(() => {
    saveRequest.mockClear()
    trayWrites.length = 0
  })

  it('places a missing spiral and saves it as a change request only', async () => {
    const w = mount(SlotPlanPanel, { props: { machineId: 'm1', isAdmin: true } })
    await flushPromises()

    // Cola sells 3/day and lasts 3.3 days → listed with spirals to add
    const chip = w.findAll('span').find(s => s.text().includes('slotPlan.oneSpiral'))
    expect(chip).toBeTruthy()
    await chip!.trigger('click')

    // Tap the Tic Tac slot (12) to place cola there
    const cells = w.findAll('[role="button"]')
    const tictacCell = cells.find(c => c.text().includes('Tic Tac'))!
    await tictacCell.trigger('click')
    expect(w.text()).toContain('slotPlan.toRequest:{"n":1}')

    // Step 2 lists the price change (1,00 → 2,00) and saves
    const next = w.findAll('button').find(b => b.text().includes('slotPlan.toRequest'))!
    await next.trigger('click')
    expect(w.text()).toContain('slotPlan.selection:{"n":12}')
    const save = w.findAll('button').find(b => b.text().includes('slotPlan.saveRequest'))!
    await save.trigger('click')
    await flushPromises()

    expect(saveRequest).toHaveBeenCalledWith('m1', [{ tray_id: 't12', to_product_id: 'cola', to_capacity: 10 }])
    expect(trayWrites).toEqual([])
  })

  it('swaps two slots by dragging one onto the other', async () => {
    const w = mount(SlotPlanPanel, { props: { machineId: 'm1', isAdmin: true } })
    await flushPromises()
    const cells = w.findAll('[role="button"]')
    const data = new Map<string, string>()
    const dataTransfer = { setData: (k: string, v: string) => data.set(k, v), getData: (k: string) => data.get(k) ?? '' }
    await cells[0]!.trigger('dragstart', { dataTransfer })
    await cells[2]!.trigger('drop', { dataTransfer })
    expect(w.text()).toContain('slotPlan.toRequest:{"n":2}')
    // Cola and Snickers only change places: marked as moved, not as new
    expect(cells[0]!.text()).toContain('slotPlan.tagMoved')
    expect(cells[0]!.text()).not.toContain('slotPlan.tagNew')
    expect(cells[2]!.classes()).toContain('border-violet-500')
  })

  it('keeps a slow seller marked when it is only moved', async () => {
    const w = mount(SlotPlanPanel, { props: { machineId: 'm1', isAdmin: true } })
    await flushPromises()
    const cells = () => w.findAll('[role="button"]')
    const data = new Map<string, string>()
    const dataTransfer = { setData: (k: string, v: string) => data.set(k, v), getData: (k: string) => data.get(k) ?? '' }
    // Tic Tac (slot 12) sells 1 in 30 days → can go. Swap it with Snickers (13).
    await cells()[1]!.trigger('dragstart', { dataTransfer })
    await cells()[2]!.trigger('drop', { dataTransfer })
    const moved = cells()[2]!  // slot 13 now holds Tic Tac
    expect(moved.text()).toContain('Tic Tac')
    expect(moved.text()).toContain('slotPlan.tagOut')
    expect(moved.classes()).toContain('border-red-500')
    expect(moved.find('[aria-label="slotPlan.tagMoved"]').exists()).toBe(true)
  })
})
