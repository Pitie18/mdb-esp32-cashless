/**
 * Order suggestion for the warehouse "Order" tab: how much of each product to
 * order so that it lasts until the delivery after next.
 *
 *   target days = order interval + lead time + buffer
 *   need        = avg daily sales × target days − (warehouse + machines + on order)
 *   cartons     = ceil(need / units per carton)
 *
 * Machines count only when the company setting says so. A product sits on the
 * list automatically while it needs cartons; a manual entry keeps it there
 * (with at least one carton) until it is ordered or removed. Pure, so the
 * apps can port it 1:1.
 */

export interface OrderSettings {
  bufferDays: number
  countMachineStock: boolean
  defaultLeadDays: number
  defaultIntervalDays: number
}

export const DEFAULT_ORDER_SETTINGS: OrderSettings = {
  bufferDays: 3,
  countMachineStock: true,
  defaultLeadDays: 3,
  defaultIntervalDays: 14,
}

export interface OrderSupplier {
  id: string
  name: string
  customer_number?: string | null
  email?: string | null
  lead_time_days: number | null
  order_interval_days: number | null
  min_order_value: number | null
}

export interface OrderProductInput {
  product_id: string
  product_name: string
  image_path?: string | null
  discontinued: boolean
  warehouse_qty: number
  machine_qty: number
  avg_daily_sales: number
  on_order: number
  unit_size: number
  /** Preferred supplier, else the supplier of the latest purchase price. */
  supplier_id: string | null
  unit_price_net: number | null
}

export interface ManualEntry {
  product_id: string
  added_by_name: string | null
  added_via: 'web' | 'ios' | 'android'
  created_at: string
}

export interface OrderLine {
  product: OrderProductInput
  available: number
  reachDays: number | null
  targetDays: number
  suggestedCartons: number
  cartons: number
  units: number
  value: number | null
  auto: boolean
  manual: ManualEntry | null
  overridden: boolean
}

export interface OrderGroup {
  supplier: OrderSupplier | null
  lines: OrderLine[]
  /** Products of this supplier that are not on the list (to add by hand). */
  others: OrderProductInput[]
  value: number
  belowMinOrder: boolean
  hasEmpty: boolean
}

export function leadDays(s: OrderSupplier | null, settings: OrderSettings): number {
  return s?.lead_time_days ?? settings.defaultLeadDays
}

export function intervalDays(s: OrderSupplier | null, settings: OrderSettings): number {
  return s?.order_interval_days ?? settings.defaultIntervalDays
}

export function targetDays(s: OrderSupplier | null, settings: OrderSettings): number {
  return intervalDays(s, settings) + leadDays(s, settings) + settings.bufferDays
}

export function availableUnits(p: OrderProductInput, settings: OrderSettings): number {
  return p.warehouse_qty + (settings.countMachineStock ? p.machine_qty : 0)
}

export function reachDays(p: OrderProductInput, settings: OrderSettings): number | null {
  return p.avg_daily_sales > 0 ? Math.round(availableUnits(p, settings) / p.avg_daily_sales) : null
}

export function suggestedCartons(p: OrderProductInput, days: number, settings: OrderSettings): number {
  if (p.discontinued || p.avg_daily_sales <= 0) return 0
  const need = p.avg_daily_sales * days - (availableUnits(p, settings) + p.on_order)
  // Tolerate float noise (3.1 × 17 is 52.699999…).
  if (need <= 1e-9) return 0
  return Math.ceil(need / Math.max(1, p.unit_size) - 1e-9)
}

export function buildOrderGroups(input: {
  products: OrderProductInput[]
  suppliers: OrderSupplier[]
  manual: ManualEntry[]
  overrides?: Record<string, number>
  settings: OrderSettings
}): OrderGroup[] {
  const { products, suppliers, settings } = input
  const overrides = input.overrides ?? {}
  const manualById = new Map(input.manual.map(m => [m.product_id, m]))
  const supplierById = new Map(suppliers.map(s => [s.id, s]))

  const buckets = new Map<string | null, OrderGroup>()
  const bucket = (s: OrderSupplier | null) => {
    const key = s?.id ?? null
    let g = buckets.get(key)
    if (!g) {
      g = { supplier: s, lines: [], others: [], value: 0, belowMinOrder: false, hasEmpty: false }
      buckets.set(key, g)
    }
    return g
  }
  for (const s of suppliers) bucket(s)

  for (const p of products) {
    const supplier = p.supplier_id ? supplierById.get(p.supplier_id) ?? null : null
    const days = targetDays(supplier, settings)
    const suggested = suggestedCartons(p, days, settings)
    const manual = manualById.get(p.product_id) ?? null
    const g = bucket(supplier)

    if (suggested === 0 && !manual) {
      if (!p.discontinued) g.others.push(p)
      continue
    }

    const base = manual ? Math.max(1, suggested) : suggested
    const override = overrides[p.product_id]
    const cartons = override !== undefined ? Math.max(0, Math.floor(override)) : base
    const units = cartons * Math.max(1, p.unit_size)
    g.lines.push({
      product: p,
      available: availableUnits(p, settings),
      reachDays: reachDays(p, settings),
      targetDays: days,
      suggestedCartons: suggested,
      cartons,
      units,
      value: p.unit_price_net == null ? null : units * p.unit_price_net,
      auto: suggested > 0,
      manual,
      overridden: override !== undefined && override !== base,
    })
  }

  const groups = [...buckets.values()]
  for (const g of groups) {
    // Emptiest first: shortest reach, unknown reach (no sales) last.
    g.lines.sort((a, b) =>
      (a.reachDays ?? Infinity) - (b.reachDays ?? Infinity)
      || a.product.product_name.localeCompare(b.product.product_name))
    g.others.sort((a, b) => a.product_name.localeCompare(b.product_name))
    g.value = g.lines.reduce((s, l) => s + (l.value ?? 0), 0)
    const min = g.supplier?.min_order_value ?? 0
    g.belowMinOrder = min > 0 && g.lines.some(l => l.cartons > 0) && g.value < min
    g.hasEmpty = g.lines.some(l => l.product.warehouse_qty === 0)
  }

  // Suppliers with something to order first (alphabetical), then the rest;
  // "no supplier" last and only when it has lines.
  return groups
    .filter(g => g.supplier ? (g.lines.length > 0 || g.others.length > 0) : g.lines.length > 0)
    .sort((a, b) => {
      if (!a.supplier) return 1
      if (!b.supplier) return -1
      if ((a.lines.length > 0) !== (b.lines.length > 0)) return a.lines.length > 0 ? -1 : 1
      return a.supplier.name.localeCompare(b.supplier.name)
    })
}

export function orderLineCount(groups: OrderGroup[]): number {
  return groups.reduce((s, g) => s + g.lines.length, 0)
}

/** Plain-text order for e-mail or a supplier portal. */
export function orderText(group: OrderGroup, labels: { customerNumber: string; carton: (n: number, size: number) => string }): string {
  const rows = group.lines.filter(l => l.cartons > 0).map(l => {
    const size = Math.max(1, l.product.unit_size)
    return size > 1
      ? `${l.units} × ${l.product.product_name} (${labels.carton(l.cartons, size)})`
      : `${l.units} × ${l.product.product_name}`
  })
  const head: string[] = []
  if (group.supplier) head.push(group.supplier.name)
  if (group.supplier?.customer_number) head.push(`${labels.customerNumber}: ${group.supplier.customer_number}`)
  return [...head, ...(head.length ? [''] : []), ...rows].join('\n')
}
