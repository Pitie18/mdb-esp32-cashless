import { useSupabaseClient } from '#imports'
import { classifyTrayStock } from '@/lib/stock-health'
import { useOrganization } from './useOrganization'
import { useWarehouse } from './useWarehouse'

// ── Types ────────────────────────────────────────────────────────────────────

export type WizardStep = 'packing' | 'refill' | 'summary'

export interface RefillMachine {
  id: string
  name: string
  stock_health: 'ok' | 'low' | 'fill' | 'critical'
  stock_percent: number
  empty_trays: number
  low_trays: number
  total_trays: number
  tray_summary: RefillItem[]
}

export interface RefillItem {
  product_id: string | null
  product_name: string
  deficit: number
  image_path: string | null
  sellprice: number | null
  in_stock?: boolean
}

export type PickingMode = 'per-machine' | 'combined'

export interface CombinedPickItem {
  product_id: string
  product_name: string
  image_path: string | null
  sellprice: number | null
  total_deficit: number
  machines: { id: string; name: string; deficit: number }[]
}

export interface TrayForRefill {
  id: string
  item_number: number
  product_id: string | null
  product_name: string | null
  image_path: string | null
  sellprice: number | null
  capacity: number
  current_stock: number
  min_stock: number
  fill_when_below: number
  /** Amount the refiller will add (editable, defaults to packed amount or deficit) */
  fill_amount: number
}

export interface TourLogEntry {
  machine_id: string
  machine_name: string
  trays_refilled: number
  total_added: number
  skipped: boolean
}

// ── Persistence ──────────────────────────────────────────────────────────────

const STORAGE_KEY = 'refill-tour-state'

interface PersistedTourState {
  currentStep: WizardStep
  machines: RefillMachine[]
  currentMachineIndex: number
  selectedWarehouseId: string | null
  /** packedQuantities as [machineId, [productId, qty][]][] */
  packedQuantities: [string, [string, number][]][]
  /** completedMachineIds as string[] */
  completedMachineIds: string[]
  tourLog: TourLogEntry[]
  pickingMode?: PickingMode
  savedAt: number
}

/** Max age of persisted state (24 hours) */
const MAX_AGE_MS = 24 * 60 * 60 * 1000

/** Check if there is a saved tour to resume (standalone, no composable needed) */
export function hasSavedTour(): boolean {
  if (import.meta.server) return false
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return false
    const state = JSON.parse(raw) as PersistedTourState
    if (Date.now() - state.savedAt > MAX_AGE_MS) {
      localStorage.removeItem(STORAGE_KEY)
      return false
    }
    return state.currentStep === 'refill' || state.currentStep === 'summary'
  } catch {
    return false
  }
}

/** Clear saved tour state (standalone, no composable needed) */
export function clearSavedTourState(): void {
  if (import.meta.server) return
  try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
}

// ── tour_started activity payload ──────────────────────────────────────────

export interface TourStartedEntryInput {
  companyId: string | null | undefined
  user: { id?: string; email?: string | null; user_metadata?: Record<string, unknown> } | null
  tourId: string
  machines: { id: string; name: string }[]
  warehouseId: string | null
  warehouseName: string | null
}

/**
 * Build the `activity_log` insert payload for a tour start (spec:
 * docs/superpowers/specs/2026-06-11-ios-dashboard-activity-feed-design.md §3.1).
 * Pure function — unit-tested in __tests__/useRefillWizard.tourStarted.test.ts.
 * The iOS app writes a field-compatible payload; keep the two in sync.
 */
export function buildTourStartedEntry(input: TourStartedEntryInput) {
  const meta = (input.user?.user_metadata ?? {}) as Record<string, unknown>
  const fullName = [meta.first_name, meta.last_name]
    .filter(Boolean).join(' ').trim()
  const userDisplay = fullName || input.user?.email || null
  return {
    company_id: input.companyId ?? null,
    user_id: input.user?.id ?? null,
    entity_type: 'stock',
    entity_id: input.tourId,
    action: 'tour_started',
    metadata: {
      tour_id: input.tourId,
      machine_count: input.machines.length,
      machine_ids: input.machines.map(m => m.id),
      machine_names: input.machines.map(m => m.name),
      warehouse_id: input.warehouseId,
      warehouse_name: input.warehouseName,
      _user_email: input.user?.email ?? null,
      _user_display: userDisplay,
    },
  }
}

// ── refill snapshot ─────────────────────────────────────────────────────────

/**
 * Join the refill RPC's authoritative per-tray stock deltas with the
 * tray metadata (product) known client-side, producing the same shape
 * `useMachineTrays.ts`'s "refill all" snapshot uses — so both actions
 * render identically in the /history product-refill list.
 */
export function buildRefillSnapshot(
  results: { tray_id: string; old_stock: number; new_stock: number }[],
  traysToRefill: { id: string; item_number: number; product_id: string | null; product_name: string | null }[],
) {
  return results.map(r => {
    const tray = traysToRefill.find(t => t.id === r.tray_id)
    return {
      id: r.tray_id,
      item_number: tray?.item_number,
      product_name: tray?.product_name ?? undefined,
      product_id: tray?.product_id ?? undefined,
      old_stock: r.old_stock,
      new_stock: r.new_stock,
    }
  })
}

// ── Composable ───────────────────────────────────────────────────────────────

export function useRefillWizard() {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()
  const { fetchOrderedProductIds } = useWarehouse()

  // Wizard state
  const currentStep = ref<WizardStep>('packing')
  const machines = ref<RefillMachine[]>([])
  const currentMachineIndex = ref(0)
  const selectedWarehouseId = ref<string | null>(null)
  const warehouseStock = ref(new Map<string, number>())
  const loading = ref(false)
  const tourStarting = ref(false)
  const confirmingRefill = ref(false)
  /** Surface for the last confirmMachineRefill failure after all retries.
   * Page reads this to show a banner; cleared on next confirm attempt. */
  const confirmError = ref<string | null>(null)
  const tourId = ref('')

  // Packing state: machineId → Set<productKey>
  const packedItems = ref(new Map<string, Set<string>>())
  // Packed quantities: machineId → Map<productId, qty>
  const packedQuantities = ref(new Map<string, Map<string, number>>())
  // Committed quantities during packing: machineId → Map<productKey, qty>
  // Tracks how much warehouse stock is committed per machine per product
  const committedQuantities = ref(new Map<string, Map<string, number>>())
  // Custom quantities set by user during packing: machineId → Map<productId, qty>
  // When set, limits the committed quantity for that product+machine.
  // Unset = use full deficit (default behavior).
  const customQuantities = ref(new Map<string, Map<string, number>>())

  // Refill state: trays for the current machine
  const currentTrays = ref<TrayForRefill[]>([])
  const currentTraysLoading = ref(false)

  // Tour log
  const tourLog = ref<TourLogEntry[]>([])

  // Track which machines have been completed or skipped during refill step
  const completedMachineIds = ref(new Set<string>())

  // Picking mode and warehouse product order
  const pickingMode = ref<PickingMode>('per-machine')
  /** product_id → sort index (0-based). Empty = no positions defined. */
  const warehouseProductOrder = ref(new Map<string, number>())

  // ── Computed ─────────────────────────────────────────────────────────────

  const currentMachine = computed(() => machines.value[currentMachineIndex.value] ?? null)

  const machinesNeedingRefill = computed(() =>
    machines.value.filter(m => m.stock_health !== 'ok')
  )

  const totalMachinesInTour = computed(() => {
    // Count machines that have at least one packed item
    return machines.value.filter(m => {
      const set = packedItems.value.get(m.id)
      return set && set.size > 0
    }).length
  })

  const currentMachineNumber = computed(() => currentMachineIndex.value + 1)

  // ── Packing helpers ──────────────────────────────────────────────────────

  function itemKey(item: { product_id: string | null; product_name: string }): string {
    return item.product_id ?? item.product_name
  }

  function isPacked(machineId: string, item: { product_id: string | null; product_name: string }): boolean {
    return packedItems.value.get(machineId)?.has(itemKey(item)) ?? false
  }

  function togglePacked(machineId: string, item: { product_id: string | null; product_name: string }) {
    if (isOutOfWarehouseStock(item, machineId)) return
    const key = itemKey(item)
    const current = packedItems.value.get(machineId) ?? new Set<string>()
    if (current.has(key)) {
      current.delete(key)
      // Clear custom quantity when unchecking
      if (item.product_id) {
        const machineCustom = customQuantities.value.get(machineId)
        if (machineCustom) {
          machineCustom.delete(item.product_id)
          if (machineCustom.size === 0) customQuantities.value.delete(machineId)
          customQuantities.value = new Map(customQuantities.value)
        }
      }
    } else {
      current.add(key)
    }
    packedItems.value.set(machineId, current)
    packedItems.value = new Map(packedItems.value)
    recalculateCommittedQuantities()
  }

  function allPacked(machineId: string, items: RefillItem[]): boolean {
    if (!items || items.length === 0) return false
    const packable = items.filter(item => !isOutOfWarehouseStock(item, machineId))
    if (packable.length === 0) return false
    const set = packedItems.value.get(machineId)
    if (!set) return false
    return packable.every(item => set.has(itemKey(item)))
  }

  function getWarehouseAvailable(item: { product_id: string | null }): number | null {
    if (!selectedWarehouseId.value || !item.product_id) return null
    return warehouseStock.value.get(item.product_id) ?? 0
  }

  /**
   * Returns remaining warehouse stock for a product after subtracting
   * quantities committed (checked) by all machines.
   */
  function getWarehouseRemaining(productId: string): number {
    const total = warehouseStock.value.get(productId) ?? 0
    let committed = 0
    for (const [, machineMap] of committedQuantities.value) {
      committed += machineMap.get(productId) ?? 0
    }
    return Math.max(0, total - committed)
  }

  /**
   * Recalculate committed quantities across all machines.
   * Processes machines in display order (urgency-sorted), so higher-priority
   * machines get first claim on limited warehouse stock.
   */
  function recalculateCommittedQuantities() {
    const newCommitted = new Map<string, Map<string, number>>()
    // Track remaining warehouse stock per product during calculation
    const remaining = new Map<string, number>()
    for (const [pid, qty] of warehouseStock.value) {
      remaining.set(pid, qty)
    }

    for (const machine of machines.value) {
      const checked = packedItems.value.get(machine.id)
      if (!checked || checked.size === 0) continue

      const machineMap = new Map<string, number>()
      const machineCustom = customQuantities.value.get(machine.id)
      for (const item of machine.tray_summary) {
        if (!item.product_id || !checked.has(itemKey(item))) continue
        const avail = remaining.get(item.product_id) ?? 0
        // Use custom quantity if set, otherwise full deficit
        const customQty = machineCustom?.get(item.product_id)
        const maxQty = customQty !== undefined ? Math.min(customQty, item.deficit) : item.deficit
        const qty = Math.min(maxQty, avail)
        if (qty > 0) {
          machineMap.set(item.product_id, qty)
          remaining.set(item.product_id, avail - qty)
        }
      }
      if (machineMap.size > 0) {
        newCommitted.set(machine.id, machineMap)
      }
    }

    committedQuantities.value = newCommitted
  }

  function effectiveDeficit(item: { product_id: string | null; deficit: number }, machineId?: string): number {
    if (!selectedWarehouseId.value || !item.product_id) return item.deficit

    // If this item is checked for a specific machine, return committed qty
    if (machineId) {
      const checked = packedItems.value.get(machineId)
      if (checked?.has(itemKey(item))) {
        return committedQuantities.value.get(machineId)?.get(item.product_id) ?? 0
      }
    }

    // For unchecked items: show min(deficit, remaining warehouse stock)
    const remaining = getWarehouseRemaining(item.product_id)
    return Math.min(item.deficit, remaining)
  }

  function isOutOfWarehouseStock(item: { product_id: string | null }, machineId?: string): boolean {
    if (!selectedWarehouseId.value || !item.product_id) return false
    // If already checked for this machine, it has committed stock — not out of stock
    if (machineId) {
      const checked = packedItems.value.get(machineId)
      if (checked?.has(itemKey(item))) return false
    }
    const remaining = getWarehouseRemaining(item.product_id)
    return remaining <= 0
  }

  function hasPartialStock(item: { product_id: string | null; deficit: number }, machineId?: string): boolean {
    if (!selectedWarehouseId.value || !item.product_id) return false
    if (machineId) {
      const checked = packedItems.value.get(machineId)
      if (checked?.has(itemKey(item))) {
        const committed = committedQuantities.value.get(machineId)?.get(item.product_id) ?? 0
        return committed > 0 && committed < item.deficit
      }
    }
    const remaining = getWarehouseRemaining(item.product_id)
    return remaining > 0 && remaining < item.deficit
  }

  function hasAnyPackedItems(): boolean {
    for (const set of packedItems.value.values()) {
      if (set.size > 0) return true
    }
    return false
  }

  /** Set a custom packing quantity for a product on a specific machine */
  function setCustomQuantity(machineId: string, productId: string, qty: number) {
    // Find the deficit for this product on this machine
    const machine = machines.value.find(m => m.id === machineId)
    const item = machine?.tray_summary.find(i => i.product_id === productId)
    if (!item) return

    const totalStock = warehouseStock.value.get(productId) ?? item.deficit
    const maxQty = Math.min(item.deficit, totalStock)
    const clamped = Math.max(1, Math.min(maxQty, qty))

    const machineCustom = customQuantities.value.get(machineId) ?? new Map<string, number>()
    machineCustom.set(productId, clamped)
    customQuantities.value.set(machineId, machineCustom)
    customQuantities.value = new Map(customQuantities.value)
    recalculateCommittedQuantities()
  }

  /** Get the custom quantity for a product on a machine, or null if not set */
  function getCustomQuantity(machineId: string, productId: string): number | null {
    return customQuantities.value.get(machineId)?.get(productId) ?? null
  }

  /** Get the max allowed custom quantity for a product on a machine */
  function getMaxCustomQuantity(machineId: string, item: { product_id: string | null; deficit: number }): number {
    if (!item.product_id) return item.deficit
    const totalStock = warehouseStock.value.get(item.product_id) ?? item.deficit
    return Math.min(item.deficit, totalStock)
  }

  /**
   * Get the displayed packing quantity for a checked item.
   * Returns the custom quantity (user's intent) if set, otherwise the committed quantity (default).
   * This is what the quantity adjuster shows and operates on.
   */
  function getDisplayedPackingQty(machineId: string, item: { product_id: string | null; deficit: number }): number {
    if (!item.product_id) return effectiveDeficit(item, machineId)
    const customQty = customQuantities.value.get(machineId)?.get(item.product_id)
    if (customQty !== undefined) return customQty
    // No custom qty: return the committed (default) amount
    return effectiveDeficit(item, machineId)
  }

  // ── Init ─────────────────────────────────────────────────────────────────

  async function initTour() {
    loading.value = true
    try {
      // Fetch machines with stock data (reuse fetchMachines logic via direct query)
      const { data: machineData, error: machErr } = await supabase
        .from('vendingMachine')
        .select('id, name')

      if (machErr) throw machErr

      const machineIds = (machineData ?? []).map((m: any) => m.id)
      if (machineIds.length === 0) {
        machines.value = []
        return
      }

      // Fetch all trays
      const { data: trayData, error: trayErr } = await (supabase as any)
        .from('machine_trays')
        .select('machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, sellprice)')
        .in('machine_id', machineIds)

      if (trayErr) throw trayErr

      // Build machine stock info
      const stockMap = new Map<string, {
        total: number
        low: number
        empty: number
        fill: number
        totalStock: number
        totalCapacity: number
        deficits: Map<string, RefillItem>
        fillBelowPending: any[]
      }>()

      for (const tray of (trayData ?? []) as any[]) {
        if (!tray.machine_id) continue
        let entry = stockMap.get(tray.machine_id)
        if (!entry) {
          entry = { total: 0, low: 0, empty: 0, fill: 0, totalStock: 0, totalCapacity: 0, deficits: new Map(), fillBelowPending: [] }
          stockMap.set(tray.machine_id, entry)
        }
        entry.total++
        entry.totalStock += tray.current_stock
        entry.totalCapacity += tray.capacity

        const state = classifyTrayStock(tray)
        const isLow = state === 'low'
        const isEmpty = state === 'critical'
        const isFillBelow = state === 'fill'

        if (isEmpty) entry.empty++
        else if (isLow) entry.low++
        else if (isFillBelow) entry.fill++

        if (isLow || isEmpty) {
          const deficit = tray.capacity - tray.current_stock
          const productName = tray.products?.name ?? `Slot ${tray.item_number}`
          const imagePath = tray.products?.image_path ?? null
          const sellprice = tray.products?.sellprice ?? null
          const key = tray.product_id ?? `slot-${tray.item_number}`
          const existing = entry.deficits.get(key)
          if (existing) {
            existing.deficit += deficit
          } else {
            entry.deficits.set(key, { product_name: productName, product_id: tray.product_id, deficit, image_path: imagePath, sellprice })
          }
        }

        if (isFillBelow) {
          entry.fillBelowPending.push(tray)
        }
      }

      // Add fill_when_below deficits for every machine (not gated on already
      // having a low/empty tray — a fill-only machine must still surface)
      for (const [, entry] of stockMap) {
        for (const tray of entry.fillBelowPending) {
          const deficit = tray.capacity - tray.current_stock
          if (deficit <= 0) continue
          const productName = tray.products?.name ?? `Slot ${tray.item_number}`
          const imagePath = tray.products?.image_path ?? null
          const sellprice = tray.products?.sellprice ?? null
          const key = tray.product_id ?? `slot-${tray.item_number}`
          const existing = entry.deficits.get(key)
          if (existing) {
            existing.deficit += deficit
          } else {
            entry.deficits.set(key, { product_name: productName, product_id: tray.product_id, deficit, image_path: imagePath, sellprice })
          }
        }
      }

      // Build RefillMachine list (only machines needing refill)
      const result: RefillMachine[] = []
      for (const m of (machineData ?? []) as any[]) {
        const stock = stockMap.get(m.id)
        if (!stock || (stock.empty === 0 && stock.low === 0 && stock.fill === 0)) continue
        result.push({
          id: m.id,
          name: m.name ?? 'Unnamed',
          stock_health: stock.empty > 0 ? 'critical' : stock.low > 0 ? 'low' : 'fill',
          stock_percent: stock.totalCapacity > 0 ? Math.round((stock.totalStock / stock.totalCapacity) * 100) : 0,
          empty_trays: stock.empty,
          low_trays: stock.low + stock.empty,
          total_trays: stock.total,
          tray_summary: Array.from(stock.deficits.values()).sort((a, b) => b.deficit - a.deficit),
        })
      }

      // Sort by urgency
      const healthOrder: Record<string, number> = { critical: 0, low: 1, fill: 2, ok: 3 }
      result.sort((a, b) => {
        const ha = healthOrder[a.stock_health] ?? 2
        const hb = healthOrder[b.stock_health] ?? 2
        if (ha !== hb) return ha - hb
        return b.low_trays - a.low_trays
      })

      machines.value = result
    } finally {
      loading.value = false
    }
  }

  async function loadWarehouseStock() {
    if (!selectedWarehouseId.value) {
      warehouseStock.value = new Map()
      warehouseProductOrder.value = new Map()
      return
    }
    try {
      // Fetch stock and product positions (depth-first via groups) in parallel
      const [stockRes, orderedIds] = await Promise.all([
        (supabase as any)
          .from('warehouse_stock_batches')
          .select('product_id, quantity')
          .eq('warehouse_id', selectedWarehouseId.value)
          .gt('quantity', 0),
        fetchOrderedProductIds(selectedWarehouseId.value).catch(() => [] as string[]),
      ])
      if (stockRes.error) throw stockRes.error

      const map = new Map<string, number>()
      for (const b of (stockRes.data ?? []) as any[]) {
        map.set(b.product_id, (map.get(b.product_id) ?? 0) + b.quantity)
      }
      warehouseStock.value = map

      // Build position order map: product_id → index (depth-first group traversal)
      const orderMap = new Map<string, number>()
      for (let i = 0; i < orderedIds.length; i++) {
        orderMap.set(orderedIds[i], i)
      }
      warehouseProductOrder.value = orderMap

      recalculateCommittedQuantities()
    } catch {
      warehouseStock.value = new Map()
      warehouseProductOrder.value = new Map()
      recalculateCommittedQuantities()
    }
  }

  // Compute effective stock health considering warehouse availability
  // Uses raw warehouse stock (not remaining after commits) so checked items
  // don't cause the machine card to collapse.
  function effectiveStockHealth(machine: RefillMachine): string {
    if (machine.stock_health === 'ok') return 'ok'
    if (!selectedWarehouseId.value) return machine.stock_health
    // If every product in tray_summary is out of warehouse stock, downgrade to ok
    const hasRefillable = machine.tray_summary.some(item => {
      if (!item.product_id) return true
      const total = warehouseStock.value.get(item.product_id) ?? 0
      return total > 0
    })
    return hasRefillable ? machine.stock_health : 'ok'
  }

  // ── Start Tour (warehouse deduction) ────────────────────────────────────

  async function startTour() {
    if (!selectedWarehouseId.value) return
    tourStarting.value = true
    tourId.value = self.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

    try {
      const { data: { session } } = await supabase.auth.getSession()

      // Collect all deductions from committed quantities
      const deductions: { machine_id: string; product_id: string; quantity: number }[] = []

      for (const machine of machines.value) {
        const committed = committedQuantities.value.get(machine.id)
        if (!committed || committed.size === 0) continue

        const machinePackedQty = new Map<string, number>()

        for (const [productId, qty] of committed) {
          if (qty <= 0) continue
          deductions.push({
            machine_id: machine.id,
            product_id: productId,
            quantity: qty,
          })
          machinePackedQty.set(productId, (machinePackedQty.get(productId) ?? 0) + qty)
        }

        packedQuantities.value.set(machine.id, machinePackedQty)
      }

      // Execute all deductions in parallel
      const deductionResults = await Promise.all(
        deductions.map(d =>
          (supabase as any).rpc('deduct_warehouse_stock_fifo', {
            p_warehouse_id: selectedWarehouseId.value,
            p_product_id: d.product_id,
            p_quantity: d.quantity,
            p_user_id: session?.user?.id ?? null,
            p_reference_id: d.machine_id,
            p_notes: 'Refill tour',
            p_metadata: { _user_email: session?.user?.email ?? null, tour_id: tourId.value },
          })
        )
      )
      const firstError = deductionResults.find(r => r.error)
      if (firstError) throw firstError.error

      // Filter machines list to only those with packed items, preserve order
      const tourMachines = machines.value.filter(m => {
        const qty = packedQuantities.value.get(m.id)
        return qty && qty.size > 0
      })
      machines.value = tourMachines

      // Tour-started activity entry (non-critical — failures only logged).
      // Written only after all deductions succeeded so an aborted tour start
      // never leaves an orphaned feed entry (spec §3.1).
      try {
        // Name lookup failure must not suppress the entry itself — a
        // name-less tour_started beats no entry.
        let warehouseName: string | null = null
        try {
          if (selectedWarehouseId.value) {
            const { data: wh } = await (supabase as any)
              .from('warehouses')
              .select('name')
              .eq('id', selectedWarehouseId.value)
              .maybeSingle()
            warehouseName = wh?.name ?? null
          }
        } catch { /* non-critical */ }
        await (supabase as any).from('activity_log').insert(
          buildTourStartedEntry({
            companyId: organization.value?.id,
            user: session?.user ?? null,
            tourId: tourId.value,
            machines: tourMachines.map(m => ({ id: m.id, name: m.name })),
            warehouseId: selectedWarehouseId.value,
            warehouseName,
          }),
        )
      } catch (logErr) {
        console.warn('[refillWizard] tour_started activity_log write failed:', logErr)
      }

      currentMachineIndex.value = 0
      tourLog.value = []
      currentStep.value = 'refill'

      // Load trays for first machine
      if (machines.value.length > 0) {
        await loadTraysForCurrentMachine()
      } else {
        currentStep.value = 'summary'
      }

      saveTourState()
    } finally {
      tourStarting.value = false
    }
  }

  // ── Refill step ────────────────────────────────────────────────────────

  async function loadTraysForCurrentMachine() {
    const machine = currentMachine.value
    if (!machine) return

    currentTraysLoading.value = true
    try {
      const { data, error } = await (supabase as any)
        .from('machine_trays')
        .select('id, machine_id, item_number, product_id, capacity, current_stock, min_stock, fill_when_below, products(name, image_path, sellprice)')
        .eq('machine_id', machine.id)
        .order('item_number')

      if (error) throw error

      const packed = packedQuantities.value.get(machine.id) ?? new Map<string, number>()

      // Build tray list, only trays needing refill
      const trays: TrayForRefill[] = []
      // Track remaining packed quantities to distribute across trays
      const remainingPacked = new Map(packed)

      for (const t of (data ?? []) as any[]) {
        const state = classifyTrayStock(t)
        const isLow = state === 'low'
        const isEmpty = state === 'critical'
        const isFillBelow = state === 'fill'

        if (!isLow && !isEmpty && !isFillBelow) continue

        const deficit = t.capacity - t.current_stock
        if (deficit <= 0) continue

        // Calculate fill amount from packed quantities
        // Only show trays that have packed stock to fill
        let fillAmount = 0
        if (t.product_id && remainingPacked.has(t.product_id)) {
          const available = remainingPacked.get(t.product_id)!
          fillAmount = Math.min(deficit, available)
          remainingPacked.set(t.product_id, available - fillAmount)
        }

        // Skip trays with nothing packed (out-of-stock products)
        if (fillAmount <= 0) continue

        trays.push({
          id: t.id,
          item_number: t.item_number,
          product_id: t.product_id,
          product_name: t.products?.name ?? null,
          image_path: t.products?.image_path ?? null,
          sellprice: t.products?.sellprice ?? null,
          capacity: t.capacity,
          current_stock: t.current_stock,
          min_stock: t.min_stock ?? 0,
          fill_when_below: t.fill_when_below ?? 0,
          fill_amount: fillAmount,
        })
      }

      currentTrays.value = trays
    } finally {
      currentTraysLoading.value = false
    }
  }

  function adjustFillAmount(trayId: string, delta: number) {
    const tray = currentTrays.value.find(t => t.id === trayId)
    if (!tray) return
    const maxFill = tray.capacity - tray.current_stock
    tray.fill_amount = Math.max(0, Math.min(maxFill, tray.fill_amount + delta))
  }

  function setFillAmount(trayId: string, amount: number) {
    const tray = currentTrays.value.find(t => t.id === trayId)
    if (!tray) return
    const maxFill = tray.capacity - tray.current_stock
    tray.fill_amount = Math.max(0, Math.min(maxFill, amount))
  }

  /** Server response row from the `refill_machine_trays` RPC. */
  interface RefillRpcRow {
    tray_id: string
    old_stock: number
    new_stock: number
    fill_amount: number
    was_already_applied: boolean
  }

  /** Sleep helper for backoff between retry attempts. */
  function delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms))
  }

  /**
   * Confirm refill for the current machine. All tray updates run in one
   * atomic RPC (`refill_machine_trays`). On network failures we retry up
   * to 3× with exponential backoff — the RPC is idempotent via
   * `(tour_id, tray_id)`, so retries cannot double-apply.
   *
   * If all 3 attempts fail the machine is **not** marked completed and
   * the wizard stays put. `confirmError` is set so the page can render
   * a banner; the user can hit Confirm again.
   */
  async function confirmMachineRefill() {
    const machine = currentMachine.value
    if (!machine) return

    confirmError.value = null
    confirmingRefill.value = true
    try {
      const traysToRefill = currentTrays.value.filter(t => t.fill_amount > 0)

      // Visit-only stop (no tray writes). Still record the tour stop.
      if (traysToRefill.length === 0) {
        completedMachineIds.value = new Set([...completedMachineIds.value, machine.id])
        tourLog.value.push({
          machine_id: machine.id,
          machine_name: machine.name,
          trays_refilled: 0,
          total_added: 0,
          skipped: false,
        })
        await advanceToNextMachine()
        saveTourState()
        return
      }

      const trayPayload = traysToRefill.map(t => ({
        tray_id: t.id,
        fill_amount: t.fill_amount,
      }))

      const backoffMs = [1000, 3000]   // delay BEFORE attempts 2 and 3
      let results: RefillRpcRow[] | null = null
      let lastError: unknown = null

      for (let attempt = 1; attempt <= 3; attempt++) {
        const { data, error: rpcErr } = await (supabase as any).rpc('refill_machine_trays', {
          p_machine_id: machine.id,
          p_tour_id: tourId.value,
          p_trays: trayPayload,
        })

        if (!rpcErr) {
          results = (data ?? []) as RefillRpcRow[]
          break
        }

        lastError = rpcErr
        console.warn(`[refillWizard] confirmMachineRefill attempt ${attempt}/3 failed:`, rpcErr)
        if (attempt < 3) {
          await delay(backoffMs[attempt - 1]!)
        }
      }

      if (!results) {
        const msg = (lastError as { message?: string })?.message ?? String(lastError ?? 'unknown error')
        confirmError.value = msg
        return  // do NOT mark complete, do NOT advance — user can retry
      }

      // Authoritative server-side delta (handles capacity clamp + dedupe)
      const totalAdded = results.reduce(
        (sum, r) => sum + Math.max(0, r.new_stock - r.old_stock),
        0,
      )

      // Activity log entry (non-critical — failures only logged)
      try {
        const { data: { session } } = await supabase.auth.getSession()
        const u = session?.user ?? null
        const fullName = [u?.user_metadata?.first_name, u?.user_metadata?.last_name]
          .filter(Boolean).join(' ').trim()
        const userDisplay = fullName || u?.email || null

        await (supabase as any).from('activity_log').insert({
          company_id: organization.value?.id,
          user_id: u?.id ?? null,
          entity_type: 'stock',
          entity_id: machine.id,
          action: 'stock_refill_tour',
          metadata: {
            tour_id: tourId.value,
            machine_id: machine.id,
            machine_name: machine.name,
            warehouse_id: selectedWarehouseId.value,
            // Scalar count + flat products stay for the native iOS decoder
            // (ActivityLogMetadata.traysRefilled: Int?, .products) — never
            // change an existing key's type; add a new one instead.
            trays_refilled: results.length,
            products: traysToRefill.map(t => ({
              product_id: t.product_id,
              product_name: t.product_name,
              quantity: t.fill_amount,
            })),
            // Rich per-tray old/new snapshot for the PWA /history view only.
            trays_detail: buildRefillSnapshot(results, traysToRefill),
            total_added: totalAdded,
            _user_email: u?.email ?? null,
            _user_display: userDisplay,
          },
        })
      } catch (logErr) {
        console.warn('[refillWizard] activity_log write failed:', logErr)
      }

      completedMachineIds.value = new Set([...completedMachineIds.value, machine.id])

      tourLog.value.push({
        machine_id: machine.id,
        machine_name: machine.name,
        trays_refilled: results.length,
        total_added: totalAdded,
        skipped: false,
      })

      await advanceToNextMachine()
      saveTourState()
    } finally {
      confirmingRefill.value = false
    }
  }

  async function skipMachine() {
    const machine = currentMachine.value
    if (machine) {
      completedMachineIds.value = new Set([...completedMachineIds.value, machine.id])
      tourLog.value.push({
        machine_id: machine.id,
        machine_name: machine.name,
        trays_refilled: 0,
        total_added: 0,
        skipped: true,
      })

      // Log skip activity (non-blocking, non-critical)
      try {
        const { data: { session } } = await supabase.auth.getSession()
        const u = session?.user ?? null
        const fullName = [u?.user_metadata?.first_name, u?.user_metadata?.last_name]
          .filter(Boolean).join(' ').trim()
        const userDisplay = fullName || u?.email || null

        await (supabase as any).from('activity_log').insert({
          company_id: organization.value?.id,
          user_id: u?.id ?? null,
          entity_type: 'stock',
          entity_id: machine.id,
          action: 'stock_refill_tour_skip',
          metadata: {
            tour_id: tourId.value,
            machine_id: machine.id,
            machine_name: machine.name,
            _user_email: u?.email ?? null,
            _user_display: userDisplay,
          },
        })
      } catch {
        // activity log failure is non-critical
      }
    }
    await advanceToNextMachine()
    saveTourState()
  }

  /** Navigate to a specific machine by index (for tab bar switching) */
  async function goToMachine(index: number) {
    if (index < 0 || index >= machines.value.length) return
    if (index === currentMachineIndex.value) return
    currentMachineIndex.value = index
    await loadTraysForCurrentMachine()
  }

  /** Check if a machine has been completed or skipped */
  function isMachineCompleted(machineId: string): boolean {
    return completedMachineIds.value.has(machineId)
  }

  /** Check if all machines are completed */
  const allMachinesCompleted = computed(() => {
    return machines.value.every(m => completedMachineIds.value.has(m.id))
  })

  // ── Position-sorted computeds ──────────────────────────────────────────

  /** Sort helper: sort RefillItems by warehouse position, unpositioned last alphabetically */
  function sortByPosition(items: RefillItem[]): RefillItem[] {
    const order = warehouseProductOrder.value
    if (order.size === 0) return items // no positions defined — keep deficit-based order
    return [...items].sort((a, b) => {
      const posA = a.product_id ? order.get(a.product_id) : undefined
      const posB = b.product_id ? order.get(b.product_id) : undefined
      // Both positioned: sort by position
      if (posA !== undefined && posB !== undefined) return posA - posB
      // Only one positioned: positioned comes first
      if (posA !== undefined) return -1
      if (posB !== undefined) return 1
      // Neither positioned: alphabetical
      return a.product_name.localeCompare(b.product_name)
    })
  }

  /** Machines with tray_summary sorted by warehouse position */
  const sortedMachines = computed<RefillMachine[]>(() => {
    return machines.value.map(m => ({
      ...m,
      tray_summary: sortByPosition(m.tray_summary),
    }))
  })

  /** All products across all machines merged and sorted by warehouse position */
  const combinedPickList = computed<CombinedPickItem[]>(() => {
    const grouped = new Map<string, CombinedPickItem>()
    for (const machine of machines.value) {
      for (const item of machine.tray_summary) {
        if (!item.product_id) continue
        const existing = grouped.get(item.product_id)
        if (existing) {
          existing.total_deficit += item.deficit
          existing.machines.push({ id: machine.id, name: machine.name, deficit: item.deficit })
        } else {
          grouped.set(item.product_id, {
            product_id: item.product_id,
            product_name: item.product_name,
            image_path: item.image_path,
            sellprice: item.sellprice,
            total_deficit: item.deficit,
            machines: [{ id: machine.id, name: machine.name, deficit: item.deficit }],
          })
        }
      }
    }

    const items = Array.from(grouped.values())
    const order = warehouseProductOrder.value
    if (order.size === 0) {
      // No positions — sort by total deficit descending
      return items.sort((a, b) => b.total_deficit - a.total_deficit)
    }
    return items.sort((a, b) => {
      const posA = order.get(a.product_id)
      const posB = order.get(b.product_id)
      if (posA !== undefined && posB !== undefined) return posA - posB
      if (posA !== undefined) return -1
      if (posB !== undefined) return 1
      return a.product_name.localeCompare(b.product_name)
    })
  })

  // ── Combined mode helpers ──────────────────────────────────────────────

  /** Check if a product is packed for ALL machines that need it */
  function isPackedCombined(productId: string): boolean {
    for (const machine of machines.value) {
      const needsIt = machine.tray_summary.some(item => item.product_id === productId)
      if (!needsIt) continue
      const set = packedItems.value.get(machine.id)
      if (!set || !set.has(productId)) return false
    }
    return true
  }

  /** Toggle pack/unpack for a product across ALL machines that need it */
  function togglePackedCombined(productId: string) {
    const allPacked = isPackedCombined(productId)
    for (const machine of machines.value) {
      const item = machine.tray_summary.find(i => i.product_id === productId)
      if (!item) continue
      if (isOutOfWarehouseStock(item, machine.id) && !allPacked) continue

      const current = packedItems.value.get(machine.id) ?? new Set<string>()
      if (allPacked) {
        current.delete(productId)
        // Clear custom quantities when unchecking in combined mode
        const machineCustom = customQuantities.value.get(machine.id)
        if (machineCustom) {
          machineCustom.delete(productId)
          if (machineCustom.size === 0) customQuantities.value.delete(machine.id)
        }
      } else {
        current.add(productId)
      }
      packedItems.value.set(machine.id, current)
    }
    packedItems.value = new Map(packedItems.value)
    customQuantities.value = new Map(customQuantities.value)
    recalculateCommittedQuantities()
  }

  /** Check if all combined pick list items are packed */
  const allPackedCombined = computed(() => {
    if (combinedPickList.value.length === 0) return false
    return combinedPickList.value.every(item => isPackedCombined(item.product_id))
  })

  /** Get effective deficit for a combined pick item, capped by warehouse stock */
  function effectiveDeficitCombined(productId: string): number {
    // Sum raw deficit across all machines
    let totalDeficit = 0
    for (const machine of machines.value) {
      const item = machine.tray_summary.find(i => i.product_id === productId)
      if (!item) continue
      totalDeficit += item.deficit
    }

    if (!selectedWarehouseId.value) return totalDeficit

    // If all machines have this product packed, sum committed quantities
    let totalCommitted = 0
    let allChecked = true
    for (const machine of machines.value) {
      const item = machine.tray_summary.find(i => i.product_id === productId)
      if (!item) continue
      const checked = packedItems.value.get(machine.id)
      if (checked?.has(productId)) {
        totalCommitted += committedQuantities.value.get(machine.id)?.get(productId) ?? 0
      } else {
        allChecked = false
      }
    }

    if (allChecked) return totalCommitted

    // Unchecked: cap total deficit by total warehouse stock
    const warehouseTotal = warehouseStock.value.get(productId) ?? 0
    return Math.min(totalDeficit, warehouseTotal)
  }

  async function advanceToNextMachine() {
    // Find the next uncompleted machine
    let nextIndex = -1
    for (let i = 0; i < machines.value.length; i++) {
      if (!completedMachineIds.value.has(machines.value[i].id)) {
        nextIndex = i
        break
      }
    }

    if (nextIndex === -1) {
      // All machines completed
      currentStep.value = 'summary'
      return
    }
    currentMachineIndex.value = nextIndex
    await loadTraysForCurrentMachine()
  }

  // ── Summary ────────────────────────────────────────────────────────────

  const tourSummary = computed(() => {
    const totalMachines = tourLog.value.length
    const machinesRefilled = tourLog.value.filter(e => !e.skipped).length
    const machinesSkipped = tourLog.value.filter(e => e.skipped).length
    const totalTraysRefilled = tourLog.value.reduce((sum, e) => sum + e.trays_refilled, 0)
    const totalItemsAdded = tourLog.value.reduce((sum, e) => sum + e.total_added, 0)
    return { totalMachines, machinesRefilled, machinesSkipped, totalTraysRefilled, totalItemsAdded }
  })

  // ── Persistence ──────────────────────────────────────────────────────────

  function saveTourState() {
    if (import.meta.server) return
    if (currentStep.value === 'packing') return // nothing to persist before tour starts

    const state: PersistedTourState = {
      currentStep: currentStep.value,
      machines: machines.value,
      currentMachineIndex: currentMachineIndex.value,
      selectedWarehouseId: selectedWarehouseId.value,
      packedQuantities: Array.from(packedQuantities.value.entries()).map(
        ([mid, pmap]) => [mid, Array.from(pmap.entries())] as [string, [string, number][]]
      ),
      completedMachineIds: Array.from(completedMachineIds.value),
      tourLog: tourLog.value,
      pickingMode: pickingMode.value,
      savedAt: Date.now(),
    }
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
    } catch {
      // storage full or unavailable — non-critical
    }
  }

  /** Restore a previously saved tour. Returns true if successful. */
  async function resumeTour(): Promise<boolean> {
    if (import.meta.server) return false
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return false
      const state = JSON.parse(raw) as PersistedTourState
      if (Date.now() - state.savedAt > MAX_AGE_MS) {
        localStorage.removeItem(STORAGE_KEY)
        return false
      }
      if (state.currentStep !== 'refill' && state.currentStep !== 'summary') return false

      // Restore state
      currentStep.value = state.currentStep
      machines.value = state.machines
      currentMachineIndex.value = state.currentMachineIndex
      selectedWarehouseId.value = state.selectedWarehouseId
      completedMachineIds.value = new Set(state.completedMachineIds)
      tourLog.value = state.tourLog
      pickingMode.value = state.pickingMode ?? 'per-machine'

      const pq = new Map<string, Map<string, number>>()
      for (const [mid, entries] of state.packedQuantities) {
        pq.set(mid, new Map(entries))
      }
      packedQuantities.value = pq

      // Load trays for current machine if in refill step
      if (state.currentStep === 'refill' && machines.value.length > 0) {
        await loadTraysForCurrentMachine()
      }

      return true
    } catch {
      return false
    }
  }

  function resetWizard() {
    currentStep.value = 'packing'
    machines.value = []
    currentMachineIndex.value = 0
    packedItems.value = new Map()
    packedQuantities.value = new Map()
    committedQuantities.value = new Map()
    customQuantities.value = new Map()
    completedMachineIds.value = new Set()
    currentTrays.value = []
    tourLog.value = []
    tourId.value = ''
    pickingMode.value = 'per-machine'
    warehouseProductOrder.value = new Map()
    clearSavedTourState()
  }

  return {
    // State
    currentStep,
    machines,
    currentMachineIndex,
    selectedWarehouseId,
    warehouseStock,
    loading,
    tourStarting,
    confirmingRefill,
    packedItems,
    packedQuantities,
    currentTrays,
    currentTraysLoading,
    tourLog,
    pickingMode,

    // Computed
    currentMachine,
    machinesNeedingRefill,
    totalMachinesInTour,
    currentMachineNumber,
    tourSummary,
    allMachinesCompleted,
    completedMachineIds,
    sortedMachines,
    combinedPickList,
    allPackedCombined,

    // Packing
    isPacked,
    togglePacked,
    allPacked,
    getWarehouseAvailable,
    effectiveDeficit,
    isOutOfWarehouseStock,
    hasPartialStock,
    hasAnyPackedItems,
    effectiveStockHealth,
    isPackedCombined,
    togglePackedCombined,
    effectiveDeficitCombined,
    customQuantities,
    setCustomQuantity,
    getCustomQuantity,
    getMaxCustomQuantity,
    getDisplayedPackingQty,

    // Actions
    initTour,
    loadWarehouseStock,
    startTour,
    loadTraysForCurrentMachine,
    adjustFillAmount,
    setFillAmount,
    confirmMachineRefill,
    confirmError,
    skipMachine,
    goToMachine,
    isMachineCompleted,
    resetWizard,
    resumeTour,
  }
}
