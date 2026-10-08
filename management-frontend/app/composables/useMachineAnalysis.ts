import { ref, computed, useSupabaseClient } from '#imports'
import { useOrganization } from './useOrganization'
import { getProductImageUrl } from './useProducts'
import { useSlotChangeRequests, type PlannedChange } from './useSlotChangeRequests'

// ─────────────────────────────────────────────────────────────────────────────
// Product-centric machine performance analysis
//
// Performance is treated as a property of the PRODUCT, not the slot: a product's
// sales are aggregated across every slot it occupies, and its "trial clock"
// (tenure) survives being moved between trays. Sales attribution uses the
// snapshotted sales.product_id; tenure comes from machine_product_offerings.
// Both are aggregated server-side by get_machine_product_kpis.
//
// The slot layout grid (rows / columns / wide slots) is still rendered, but each
// slot is coloured by ITS PRODUCT's tier — so a product spanning two slots
// colours both identically. The layout maths is replicated 1:1 from the native
// iOS app (ios/VMflow/Views/Refill/MachineLayoutGrid.swift):
//   row    = max(0, floor(item_number / 10) - 1)
//   column = item_number % 10
//   width  = gap to the next occupied slot in the row.
// ─────────────────────────────────────────────────────────────────────────────

export type SlotTier = 'empty' | 'testing' | 'dead' | 'weak' | 'ok' | 'strong'

export interface Suggestion {
  product_id: string
  name: string
  image_url: string | null
  /** 'bestseller' = proven fleet-wide performer; 'newcomer' = never sold yet. */
  kind: 'bestseller' | 'newcomer'
  /** Fleet-wide avg daily units (0 for newcomers). */
  velocity: number
}

/** Aggregated performance of one product within a single machine. */
export interface ProductAnalysis {
  product_id: string
  name: string
  image_url: string | null
  /** item_numbers of the slots this product currently occupies. */
  slots: number[]
  /** tray ids of those slots (for applying swaps). */
  trayIds: string[]
  units_sold: number
  revenue_eur: number
  total_capacity: number
  total_stock: number
  sell_through_pct: number
  avg_daily_units: number
  days_until_empty: number | null
  /** Days since the product was first offered in this machine (survives moves). */
  tenure_days: number | null
  tier: SlotTier
  suggestions: Suggestion[]
}

/** A single cell in the rendered machine layout grid. */
export interface GridSlot {
  trayId: string
  item_number: number
  row: number
  column: number
  width: number
  product_id: string | null
  product_name: string | null
  image_url: string | null
  tier: SlotTier
  sell_through_pct: number
}

const COLUMNS_PER_ROW = 10

// ── Pure helpers (exported for unit testing) ────────────────────────────────

/** Map a flat item_number to its (row, column) grid position — iOS parity. */
export function slotRowCol(itemNumber: number): { row: number; column: number } {
  return {
    row: Math.max(0, Math.floor(itemNumber / 10) - 1),
    column: ((itemNumber % 10) + 10) % 10,
  }
}

/**
 * Compute the physical width (in columns) of each slot. A slot spans from its
 * own column to the next occupied slot in the same row; the last slot in a row
 * stretches to the end of the row. Gaps in the item_number sequence therefore
 * widen the preceding slot — matching the iOS layout algorithm.
 */
export function computeSlotWidths(items: number[]): Map<number, number> {
  const byRow = new Map<number, number[]>()
  for (const item of items) {
    const { row } = slotRowCol(item)
    if (!byRow.has(row)) byRow.set(row, [])
    byRow.get(row)!.push(item)
  }
  const widths = new Map<number, number>()
  for (const rowItems of byRow.values()) {
    rowItems.sort((a, b) => a - b)
    for (let i = 0; i < rowItems.length; i++) {
      const item = rowItems[i]!
      const { column } = slotRowCol(item)
      const next = i + 1 < rowItems.length ? rowItems[i + 1]! : null
      const width = next != null ? next - item : COLUMNS_PER_ROW - column
      widths.set(item, Math.max(1, Math.min(COLUMNS_PER_ROW - column, width)))
    }
  }
  return widths
}

export interface ScoreOpts {
  /** Lookback window the KPIs were computed over. */
  days: number
  /** Products offered for fewer days than this stay in a "testing" grace period. */
  gracePeriodDays?: number
  /** sell-through below this (%) is "weak". */
  weakSellThrough?: number
  /** sell-through at/above this (%) is "strong". */
  strongSellThrough?: number
}

/**
 * Classify a product's performance in a machine. A product offered for fewer
 * than the grace period — whether freshly placed OR a brand-new test product —
 * that would otherwise score dead/weak is surfaced as "testing" instead, so it
 * isn't condemned before it has had a fair chance. Because tenure is measured
 * per (machine, product), moving the product between slots does NOT reset this.
 */
export function scoreProduct(
  p: { units_sold: number; sell_through_pct: number; tenure_days: number | null },
  opts: ScoreOpts,
): SlotTier {
  const weak = opts.weakSellThrough ?? 15
  const strong = opts.strongSellThrough ?? 40
  const grace = opts.gracePeriodDays ?? 14

  let base: SlotTier
  if (p.units_sold <= 0) base = 'dead'
  else if (p.sell_through_pct < weak) base = 'weak'
  else if (p.sell_through_pct < strong) base = 'ok'
  else base = 'strong'

  if ((base === 'dead' || base === 'weak') && p.tenure_days != null && p.tenure_days < grace) {
    return 'testing'
  }
  return base
}

export interface SuggestionPoolParams {
  products: { id: string; name: string; image_url: string | null; discontinued: boolean }[]
  /** product_id → fleet-wide avg daily units. */
  velocity: Map<string, number>
  /** product_ids already offered in this machine. */
  productsInMachine: Set<string>
  maxBestsellers?: number
  maxNewcomers?: number
}

/**
 * Build the shared pool of replacement candidates for a machine:
 *   • bestsellers — products with proven fleet-wide velocity not yet in this machine
 *   • newcomers   — catalogue products that have never sold anywhere (test candidates)
 * Discontinued products and products already present in the machine are excluded.
 */
export function buildSuggestionPool(params: SuggestionPoolParams): {
  bestsellers: Suggestion[]
  newcomers: Suggestion[]
} {
  const maxBest = params.maxBestsellers ?? 5
  const maxNew = params.maxNewcomers ?? 5

  const eligible = params.products.filter(
    p => !p.discontinued && !params.productsInMachine.has(p.id),
  )

  const bestsellers: Suggestion[] = eligible
    .map(p => ({ p, v: params.velocity.get(p.id) ?? 0 }))
    .filter(({ v }) => v > 0)
    .sort((a, b) => b.v - a.v)
    .slice(0, maxBest)
    .map(({ p, v }) => ({ product_id: p.id, name: p.name, image_url: p.image_url, kind: 'bestseller' as const, velocity: v }))

  const newcomers: Suggestion[] = eligible
    .filter(p => (params.velocity.get(p.id) ?? 0) <= 0)
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, maxNew)
    .map(p => ({ product_id: p.id, name: p.name, image_url: p.image_url, kind: 'newcomer' as const, velocity: 0 }))

  return { bestsellers, newcomers }
}

/** A catalogue product as offered in the replace-sheet full-catalogue search. */
export interface SearchableProduct {
  product_id: string
  name: string
  image_url: string | null
  /** Fleet-wide avg daily units; 0 if the product has never sold anywhere. */
  velocity: number
  /** item_numbers where this product currently sits in THIS machine (empty if absent). */
  inMachineSlots: number[]
}

/**
 * Filter the searchable catalogue for the replace sheet. An empty/whitespace
 * query returns no results (type-to-search). Matching is a case-insensitive
 * substring on the product name. `excludeProductId` drops that product (you
 * can't replace a slot with the product already in it). Results are capped at
 * `limit` (default 30); `truncated` signals that more matches exist.
 */
export function filterSearchableProducts(
  products: SearchableProduct[],
  query: string,
  opts: { excludeProductId?: string | null; limit?: number } = {},
): { results: SearchableProduct[]; truncated: boolean } {
  const q = query.trim().toLowerCase()
  if (!q) return { results: [], truncated: false }
  const limit = opts.limit ?? 30
  const matched = products.filter(
    p => p.product_id !== opts.excludeProductId && p.name.toLowerCase().includes(q),
  )
  return { results: matched.slice(0, limit), truncated: matched.length > limit }
}

/** Build the layout grid cells, colouring each slot by its product's tier. */
export function buildGridSlots(
  trays: { id: string; item_number: number; product_id: string | null; product_name: string | null; image_url: string | null }[],
  tierByProduct: Map<string, { tier: SlotTier; sell_through_pct: number }>,
): GridSlot[] {
  const widths = computeSlotWidths(trays.map(t => t.item_number))
  return trays.map((t) => {
    const { row, column } = slotRowCol(t.item_number)
    const info = t.product_id ? tierByProduct.get(t.product_id) : undefined
    return {
      trayId: t.id,
      item_number: t.item_number,
      row,
      column,
      width: widths.get(t.item_number) ?? 1,
      product_id: t.product_id,
      product_name: t.product_name,
      image_url: t.image_url,
      tier: t.product_id ? (info?.tier ?? 'empty') : 'empty',
      sell_through_pct: info?.sell_through_pct ?? 0,
    }
  })
}

const TIER_SEVERITY: Record<SlotTier, number> = { dead: 0, weak: 1, testing: 2, ok: 3, strong: 4, empty: 5 }

// ── Composable ───────────────────────────────────────────────────────────────

export function useMachineAnalysis() {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()
  const { fetchOpenRequest, saveRequest } = useSlotChangeRequests()

  const products = ref<ProductAnalysis[]>([])
  /** Trays with a pending change on the machine's change request. */
  const pendingTrayIds = ref(new Set<string>())
  const slots = ref<GridSlot[]>([])
  const fillSuggestions = ref<Suggestion[]>([])
  const searchableProducts = ref<SearchableProduct[]>([])
  const rowCount = ref(0)
  const loading = ref(false)
  const error = ref('')
  const days = ref(30)

  let currentMachineId: string | null = null

  // Counts of distinct PRODUCTS in each tier.
  const tierCounts = computed(() => {
    const counts: Record<SlotTier, number> = { empty: 0, testing: 0, dead: 0, weak: 0, ok: 0, strong: 0 }
    for (const p of products.value) counts[p.tier]++
    return counts
  })

  // Counts of SLOTS in each tier (for the grid legend).
  const slotTierCounts = computed(() => {
    const counts: Record<SlotTier, number> = { empty: 0, testing: 0, dead: 0, weak: 0, ok: 0, strong: 0 }
    for (const s of slots.value) counts[s.tier]++
    return counts
  })

  /** Underperforming products, worst first. */
  const weakProducts = computed(() =>
    products.value
      .filter(p => p.tier === 'dead' || p.tier === 'weak')
      .sort((a, b) => TIER_SEVERITY[a.tier] - TIER_SEVERITY[b.tier] || a.sell_through_pct - b.sell_through_pct),
  )

  /** Rough estimate of revenue left on the table by dead/weak products. */
  const lostRevenuePotential = computed(() => {
    const strong = products.value.filter(p => p.tier === 'strong').map(p => p.revenue_eur).sort((a, b) => a - b)
    if (strong.length === 0) return 0
    const median = strong[Math.floor(strong.length / 2)]!
    return weakProducts.value.reduce((sum, p) => sum + Math.max(0, median - p.revenue_eur), 0)
  })

  async function analyze(machineId: string, windowDays = days.value) {
    currentMachineId = machineId
    days.value = windowDays
    loading.value = true
    error.value = ''
    try {
      const companyId = organization.value?.id
      if (!companyId) throw new Error('No organization')

      const [trayRes, kpiRes, velocityRes, productsRes] = await Promise.all([
        (supabase as any)
          .from('machine_trays')
          .select('id, item_number, product_id, products(name, image_path)')
          .eq('machine_id', machineId)
          .order('item_number'),
        (supabase as any).rpc('get_machine_product_kpis', {
          p_machine_id: machineId,
          p_company_id: companyId,
          p_days: windowDays,
        }),
        (supabase as any).rpc('get_product_sales_velocity', {
          p_company_id: companyId,
          p_days: windowDays,
        }),
        (supabase as any)
          .from('products')
          .select('id, name, image_path, discontinued')
          .order('name'),
      ])

      if (trayRes.error) throw trayRes.error
      if (kpiRes.error) throw kpiRes.error

      const trays = ((trayRes.data ?? []) as any[]).map(t => ({
        id: t.id,
        item_number: t.item_number,
        product_id: t.product_id ?? null,
        product_name: t.products?.name ?? null,
        image_url: t.products?.image_path ? getProductImageUrl(t.products.image_path) : null,
      }))

      // Fleet-wide velocity map
      const velocity = new Map<string, number>()
      for (const v of (velocityRes.data ?? []) as any[]) {
        if (v.product_id) velocity.set(v.product_id, parseFloat(v.avg_daily_units) || 0)
      }

      // Catalogue
      const catalogue = ((productsRes.data ?? []) as any[]).map(p => ({
        id: p.id,
        name: p.name,
        image_url: p.image_path ? getProductImageUrl(p.image_path) : null,
        discontinued: !!p.discontinued,
      }))
      const imageByProduct = new Map(catalogue.map(p => [p.id, p.image_url]))

      const productsInMachine = new Set<string>(
        trays.map(t => t.product_id).filter((id): id is string => !!id),
      )
      const { bestsellers, newcomers } = buildSuggestionPool({ products: catalogue, velocity, productsInMachine })
      const sharedSuggestions = [...bestsellers.slice(0, 3), ...newcomers.slice(0, 2)]
      fillSuggestions.value = sharedSuggestions

      const trayIdsByProduct = new Map<string, string[]>()
      const itemNumbersByProduct = new Map<string, number[]>()
      for (const t of trays) {
        if (!t.product_id) continue
        if (!trayIdsByProduct.has(t.product_id)) trayIdsByProduct.set(t.product_id, [])
        trayIdsByProduct.get(t.product_id)!.push(t.id)
        if (!itemNumbersByProduct.has(t.product_id)) itemNumbersByProduct.set(t.product_id, [])
        itemNumbersByProduct.get(t.product_id)!.push(t.item_number)
      }

      // Full catalogue for the replace-sheet search (discontinued excluded),
      // enriched with fleet velocity + which slots of THIS machine hold it.
      searchableProducts.value = catalogue
        .filter(p => !p.discontinued)
        .map(p => ({
          product_id: p.id,
          name: p.name,
          image_url: p.image_url,
          velocity: velocity.get(p.id) ?? 0,
          inMachineSlots: (itemNumbersByProduct.get(p.id) ?? []).slice().sort((a, b) => a - b),
        }))
        .sort((a, b) => a.name.localeCompare(b.name))

      const now = Date.now()
      const analyses: ProductAnalysis[] = ((kpiRes.data?.products ?? []) as any[]).map((row) => {
        const units = Number(row.units_sold) || 0
        const capacity = Number(row.total_capacity) || 0
        const stock = Number(row.total_stock) || 0
        const sellThrough = capacity > 0 && windowDays > 0
          ? Math.min((units / (capacity * windowDays / 7)) * 100, 100)
          : 0
        const avgDaily = windowDays > 0 ? units / windowDays : 0
        const daysUntilEmpty = units > 0 && stock > 0
          ? Math.round(stock / (units / windowDays))
          : (stock === 0 ? 0 : null)
        const tenureDays = row.offered_since
          ? Math.floor((now - new Date(row.offered_since).getTime()) / 86_400_000)
          : null

        const tier = scoreProduct(
          { units_sold: units, sell_through_pct: sellThrough, tenure_days: tenureDays },
          { days: windowDays },
        )

        return {
          product_id: row.product_id,
          name: row.product_name ?? 'Unknown',
          image_url: imageByProduct.get(row.product_id) ?? null,
          slots: (row.slots ?? []) as number[],
          trayIds: trayIdsByProduct.get(row.product_id) ?? [],
          units_sold: units,
          revenue_eur: Number(row.revenue_eur) || 0,
          total_capacity: capacity,
          total_stock: stock,
          sell_through_pct: Math.round(sellThrough * 10) / 10,
          avg_daily_units: Math.round(avgDaily * 100) / 100,
          days_until_empty: daysUntilEmpty,
          tenure_days: tenureDays,
          tier,
          suggestions: (tier === 'dead' || tier === 'weak')
            ? sharedSuggestions.filter(s => s.product_id !== row.product_id)
            : [],
        }
      })

      const tierByProduct = new Map<string, { tier: SlotTier; sell_through_pct: number }>()
      for (const a of analyses) tierByProduct.set(a.product_id, { tier: a.tier, sell_through_pct: a.sell_through_pct })

      products.value = analyses
      slots.value = buildGridSlots(trays, tierByProduct)
      const open = await fetchOpenRequest(machineId).catch(() => null)
      pendingTrayIds.value = new Set((open?.items ?? []).map(i => i.tray_id))
      rowCount.value = slots.value.reduce((max, s) => Math.max(max, s.row + 1), 0)
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : 'Failed to analyze machine'
    } finally {
      loading.value = false
    }
  }

  /**
   * Put a replacement for a slot on the machine's change request. The slot is
   * NOT changed here: the refiller rebuilds it on the next tour and quits it
   * at the machine (apply_slot_change), which keeps sales and stock booked to
   * the product that is physically in the slot until then. Other pending
   * changes of the request are kept; the slot keeps its capacity.
   */
  async function applySwap(trayId: string, productId: string) {
    if (!currentMachineId) return
    const machineId = currentMachineId
    const [open, trayRes] = await Promise.all([
      fetchOpenRequest(machineId),
      (supabase as any).from('machine_trays').select('capacity').eq('id', trayId).single(),
    ])
    if (trayRes.error) throw trayRes.error
    const changes = new Map<string, PlannedChange>(
      (open?.items ?? []).map(i => [i.tray_id, { tray_id: i.tray_id, to_product_id: i.to_product_id, to_capacity: i.to_capacity }]),
    )
    changes.set(trayId, {
      tray_id: trayId,
      to_product_id: productId,
      to_capacity: changes.get(trayId)?.to_capacity ?? trayRes.data.capacity,
    })
    await saveRequest(machineId, [...changes.values()])
    pendingTrayIds.value = new Set(changes.keys())
  }

  return {
    products,
    slots,
    fillSuggestions,
    searchableProducts,
    rowCount,
    loading,
    error,
    days,
    tierCounts,
    slotTierCounts,
    weakProducts,
    lostRevenuePotential,
    analyze,
    applySwap,
    pendingTrayIds,
  }
}
