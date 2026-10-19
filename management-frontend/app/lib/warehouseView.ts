/**
 * Pure helpers for the warehouse page: which products a stock filter chip
 * shows, and which view an incoming `?tab=` link opens.
 *
 * The page used to have six tabs (overview, stock, incoming, history,
 * positions, settings) and three filter checkboxes. It now has three tabs
 * (stock, incoming, history) plus a separate setup view, and one row of chips.
 */

export type StockChip = 'all' | 'action' | 'expiring' | 'hidden'

export interface StockChipItem {
  product_id: string
  total_quantity: number
  is_below_min: boolean
  expiration_status: 'ok' | 'warning' | 'critical'
  discontinued: boolean
}

/**
 * Hidden by default: discontinued products and products that sit in no
 * machine slot. They are still reachable through the "hidden" chip.
 */
export function isHiddenProduct(p: StockChipItem, assigned: boolean): boolean {
  return p.discontinued || !assigned
}

/** A visible product that is empty, at or below its minimum, or expiring. */
export function needsAction(p: StockChipItem, assigned: boolean): boolean {
  if (isHiddenProduct(p, assigned)) return false
  return p.total_quantity === 0 || p.is_below_min || p.expiration_status !== 'ok'
}

/**
 * Whether a product belongs in a chip. The hidden chip only lists products
 * that still have stock: a zero-stock product in no machine is catalogue
 * noise, the same rule the old "show all out of stock" checkbox applied by
 * default.
 */
export function matchesChip(p: StockChipItem, assigned: boolean, chip: StockChip): boolean {
  switch (chip) {
    case 'all': return !isHiddenProduct(p, assigned)
    case 'action': return needsAction(p, assigned)
    case 'expiring': return !p.discontinued && p.expiration_status !== 'ok' && p.total_quantity > 0
    case 'hidden': return isHiddenProduct(p, assigned) && p.total_quantity > 0
  }
}

export function chipCounts(items: StockChipItem[], assignedIds: Set<string>): Record<StockChip, number> {
  const counts: Record<StockChip, number> = { all: 0, action: 0, expiring: 0, hidden: 0 }
  for (const p of items) {
    const assigned = assignedIds.has(p.product_id)
    for (const chip of Object.keys(counts) as StockChip[]) {
      if (matchesChip(p, assigned, chip)) counts[chip]++
    }
  }
  return counts
}

export type WarehouseTab = 'stock' | 'incoming' | 'history' | 'setup'
export type SetupSection = 'warehouses' | 'positions' | 'barcodes' | 'calculation'

const SETUP_SECTIONS: SetupSection[] = ['warehouses', 'positions', 'barcodes', 'calculation']

/**
 * Map a `?tab=` (and `?section=`) query to a view. Old links keep working:
 * overview → stock, positions → setup/positions, settings → setup/warehouses.
 * Non-admins never land in setup.
 */
export function resolveWarehouseView(
  tab: unknown,
  section: unknown,
  isAdmin: boolean,
): { tab: WarehouseTab; section: SetupSection } {
  const s = SETUP_SECTIONS.includes(section as SetupSection) ? (section as SetupSection) : 'warehouses'
  let resolved: { tab: WarehouseTab; section: SetupSection }
  switch (tab) {
    case 'incoming': resolved = { tab: 'incoming', section: s }; break
    case 'history': resolved = { tab: 'history', section: s }; break
    case 'positions': resolved = { tab: 'setup', section: 'positions' }; break
    case 'settings': resolved = { tab: 'setup', section: 'warehouses' }; break
    case 'setup': resolved = { tab: 'setup', section: s }; break
    default: resolved = { tab: 'stock', section: s }
  }
  if (resolved.tab === 'setup' && !isAdmin) return { tab: 'stock', section: s }
  return resolved
}
