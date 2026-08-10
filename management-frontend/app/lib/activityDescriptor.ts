// Central, data-driven descriptor for activity-log entries.
//
// One place that knows how to turn an `activity_log` row into a human label, a
// leading action icon, an optional product reference, and a set of detail
// "chips" (each with its own icon). Consumed by the /history page and the
// dashboard activity feed so the two never drift apart.
//
// Pure functions (they take an injected `t`, date formatter and machine-name
// resolver) so they are unit-testable without a Nuxt/i18n runtime. The thin
// `useActivityDescriptor` composable binds them to `useI18n()` + live lookups.

export type ChipVariant = 'default' | 'increase' | 'decrease' | 'neutral'

/** Semantic colour bucket for the leading action badge; mapped to CSS by the view. */
export type ChipTint =
  | 'sale' | 'danger' | 'credit' | 'stock' | 'tour' | 'cashbook' | 'config' | 'neutral'

export interface ActivityChip {
  label: string
  value: string
  variant?: ChipVariant
  /** lucide-vue-next component name, resolved to a component by the view. */
  icon?: string
}

export interface ActivityEntryLike {
  action: string
  metadata: Record<string, unknown> | null
}

export interface ActivityIconSpec {
  icon: string
  tint: ChipTint
}

/** Product identity for the row's thumbnail; the view resolves the image. */
export interface ProductRef {
  productId?: string
  productName?: string
}

/**
 * A product entry within a multi-item refill breakdown. `oldStock`/`newStock`
 * are present for rows written after this feature shipped; `quantity` is the
 * legacy fallback for historical `stock_refill_tour` rows that only ever
 * logged a flat requested amount, not an authoritative before/after delta.
 */
export interface ProductRefWithStock {
  productId?: string
  productName?: string
  oldStock?: number
  newStock?: number
  quantity?: number
}

export type ActivityDetailVariant = 'default' | 'warning'

/** A single row in the /history "technical details" expand panel. */
export interface ActivityDetail {
  label: string
  value: string
  variant?: ActivityDetailVariant
}

export type TFn = (key: string, named?: Record<string, unknown>) => string

export interface DescriptorCtx {
  t: TFn
  /** Locale-aware datetime formatter, e.g. `(iso) => formatDateTime(iso, locale)`. */
  formatDateTime?: (iso: string) => string
  /** Resolve a machine_id → display name. Returns undefined when unknown. */
  machineName?: (id: string) => string | undefined
  /** Resolve a device/embedded id → machine name (sale_recorded/credit_sent). */
  machineNameByDevice?: (deviceId: string) => string | undefined
}

// Every action we render a proper (translated) label for. Anything not listed
// falls back to a humanised version of the raw action string.
const KNOWN_ACTIONS = new Set([
  'sale_recorded',
  'sale_deleted',
  'sale_inserted',
  'sale_restored',
  'credit_sent',
  'config_updated',
  'stock_updated',
  'stock_refill_all',
  'stock_refill_tour',
  'stock_refill_tour_skip',
  'tour_started',
  'product_swapped',
  'cash_book_created',
  'cash_book_deleted',
  'cash_book_entry_created',
  'machine_assigned_to_cash_book',
  'machine_unassigned_from_cash_book',
  'cash_book_settings_updated',
  'poster_printed',
])

export function activityActionLabel(action: string, t: TFn): string {
  if (KNOWN_ACTIONS.has(action)) return t(`activity.actions.${action}`)
  return action.replace(/_/g, ' ')
}

/** Leading badge icon + colour bucket per action. */
export function activityIcon(action: string): ActivityIconSpec {
  switch (action) {
    case 'sale_recorded': return { icon: 'ShoppingCart', tint: 'sale' }
    case 'sale_deleted': return { icon: 'Trash2', tint: 'danger' }
    case 'sale_inserted': return { icon: 'PlusCircle', tint: 'sale' }
    case 'sale_restored': return { icon: 'RotateCcw', tint: 'sale' }
    case 'credit_sent': return { icon: 'CircleDollarSign', tint: 'credit' }
    case 'config_updated': return { icon: 'Settings', tint: 'config' }
    case 'stock_updated': return { icon: 'Package', tint: 'stock' }
    case 'stock_refill_all': return { icon: 'PackagePlus', tint: 'stock' }
    case 'stock_refill_tour': return { icon: 'Truck', tint: 'stock' }
    case 'stock_refill_tour_skip': return { icon: 'Truck', tint: 'neutral' }
    case 'tour_started': return { icon: 'Truck', tint: 'tour' }
    case 'product_swapped': return { icon: 'Repeat', tint: 'stock' }
    case 'cash_book_created': return { icon: 'Wallet', tint: 'cashbook' }
    case 'cash_book_deleted': return { icon: 'Trash2', tint: 'danger' }
    case 'cash_book_entry_created': return { icon: 'Coins', tint: 'cashbook' }
    case 'machine_assigned_to_cash_book': return { icon: 'Link', tint: 'cashbook' }
    case 'machine_unassigned_from_cash_book': return { icon: 'Unlink', tint: 'cashbook' }
    case 'cash_book_settings_updated': return { icon: 'Settings', tint: 'cashbook' }
    case 'poster_printed': return { icon: 'Printer', tint: 'neutral' }
    default: return { icon: 'Activity', tint: 'neutral' }
  }
}

/**
 * The single product an entry is about, so the view can render a thumbnail.
 * Null for entries with no product or with two products (product_swapped, which
 * renders from/to chips instead).
 */
export function activityProductRef(entry: ActivityEntryLike): ProductRef | null {
  const m = entry.metadata
  if (!m) return null
  switch (entry.action) {
    case 'sale_recorded':
    case 'sale_deleted':
    case 'sale_inserted':
    case 'sale_restored':
    case 'stock_updated': {
      const productId = typeof m.product_id === 'string' ? m.product_id : undefined
      const productName = typeof m.product_name === 'string' ? m.product_name : undefined
      if (!productId && !productName) return null
      return { productId, productName }
    }
    default:
      return null
  }
}

/**
 * The full list of products behind a multi-item refill entry, for the
 * /history expandable product-refill list. Only `stock_refill_all` and
 * `stock_refill_tour` carry this; everything else returns `[]`.
 */
export function activityProductRefs(entry: ActivityEntryLike): ProductRefWithStock[] {
  const m = entry.metadata
  if (!m) return []
  if (entry.action !== 'stock_refill_all' && entry.action !== 'stock_refill_tour') return []

  // stock_refill_tour carries its rich per-tray snapshot under `trays_detail`
  // (an additive key — `trays_refilled` stays a scalar count so the native
  // iOS decoder, which types it as Int, keeps working). stock_refill_all has
  // always carried its array under `trays_refilled` (iOS never reads it).
  const detail = Array.isArray(m.trays_detail)
    ? (m.trays_detail as any[])
    : (Array.isArray(m.trays_refilled) ? (m.trays_refilled as any[]) : [])
  if (detail.length) {
    return detail.map(tr => ({
      productId: tr.product_id ?? undefined,
      productName: tr.product_name
        ? String(tr.product_name)
        : (tr.item_number != null ? `#${tr.item_number}` : undefined),
      oldStock: tr.old_stock != null ? Number(tr.old_stock) : undefined,
      newStock: tr.new_stock != null ? Number(tr.new_stock) : undefined,
    }))
  }

  // Legacy stock_refill_tour rows (and iOS-written tour rows): flat `products`
  // array, no stock deltas.
  const products = Array.isArray(m.products) ? (m.products as any[]) : []
  return products.map(p => ({
    productId: p.product_id ?? undefined,
    productName: p.product_name ? String(p.product_name) : undefined,
    quantity: p.quantity != null ? Number(p.quantity) : undefined,
  }))
}

// ── formatting helpers ──────────────────────────────────────────────────────

function euro(v: unknown): string {
  return `€${Number(v).toFixed(2)}`
}

/** Map a `source:` provenance code to a translated label (null → no chip). */
function sourceLabel(source: unknown, t: TFn): string | null {
  switch (source) {
    case 'manual': return t('activity.sourceManual')
    case 'refill_wizard': return t('activity.sourceRefill')
    case 'refill_full': return t('activity.sourceRefillFull')
    case 'nayax_reconciliation': return t('activity.sourceNayax')
    case 'analysis_swap': return t('activity.sourceAnalysisSwap')
    case 'suppressed_restore': return t('activity.sourceSuppressedRestore')
    default: return null
  }
}

function stockChangeChip(label: string, oldVal: number, newVal: number): ActivityChip {
  const delta = newVal - oldVal
  const arrow = delta > 0 ? '↑' : delta < 0 ? '↓' : ''
  const sign = delta > 0 ? '+' : ''
  const variant: ChipVariant = delta > 0 ? 'increase' : delta < 0 ? 'decrease' : 'neutral'
  const icon = delta > 0 ? 'ArrowUpRight' : delta < 0 ? 'ArrowDownRight' : undefined
  return { label, value: `${oldVal} ${arrow} ${newVal} (${sign}${delta})`, variant, icon }
}

// ── chip builder ────────────────────────────────────────────────────────────

export function activityChips(entry: ActivityEntryLike, ctx: DescriptorCtx): ActivityChip[] {
  const m = entry.metadata
  if (!m) return []

  const { t } = ctx
  const chips: ActivityChip[] = []
  const F = (k: string) => t(`activity.field.${k}`)
  const date = (v: unknown) => (ctx.formatDateTime ? ctx.formatDateTime(String(v)) : String(v))

  const push = (label: string, value: unknown, opts: { variant?: ChipVariant; icon?: string } = {}) => {
    if (value == null || value === '') return
    chips.push({ label, value: String(value), variant: opts.variant, icon: opts.icon })
  }

  // Machine NAME only — resolve machine_id via the injected lookup; never fall
  // back to a raw UUID (the whole point of the redesign).
  const pushMachine = () => {
    const name = m.machine_name
      ?? (m.machine_id ? ctx.machineName?.(String(m.machine_id)) : undefined)
    if (name) push(F('machine'), name, { icon: 'MapPin' })
  }
  const pushSource = () => {
    const label = sourceLabel(m.source, t)
    if (label) push(t('activity.source'), label, { icon: 'RefreshCw' })
  }

  switch (entry.action) {
    case 'sale_recorded': {
      // From the MQTT webhook (service_role): metadata carries the device
      // (embedded) id, not a machine name — resolve it to the machine name so
      // the row shows the vending machine, not a useless UUID.
      const machine = m.device_id ? ctx.machineNameByDevice?.(String(m.device_id)) : undefined
      if (machine) push(F('machine'), machine, { icon: 'MapPin' })
      if (m.item_number != null) push(F('slot'), `#${m.item_number}`, { icon: 'Hash' })
      if (m.price != null) push(F('price'), euro(m.price), { icon: 'Euro' })
      if (m.channel) push(F('channel'), m.channel, { icon: 'CreditCard' })
      break
    }

    case 'sale_deleted':
    case 'sale_inserted':
    case 'sale_restored': {
      // Product is rendered as a thumbnail (activityProductRef), not a chip.
      pushMachine()
      if (m.item_number != null) push(F('slot'), `#${m.item_number}`, { icon: 'Hash' })
      if (m.item_price != null) push(F('price'), euro(m.item_price), { icon: 'Euro' })
      if (m.channel) push(F('channel'), m.channel, { icon: 'CreditCard' })
      if (m.sale_created_at) push(F('saleDate'), date(m.sale_created_at), { icon: 'Clock' })
      if (m.stock_restored != null) {
        push(F('stockRestored'), m.stock_restored ? t('activity.yes') : t('activity.no'), { icon: 'RotateCcw' })
      }
      pushSource()
      break
    }

    case 'credit_sent': {
      if (m.amount != null) push(F('amount'), euro(m.amount), { icon: 'Euro' })
      const machine = m.device_id ? ctx.machineNameByDevice?.(String(m.device_id)) : undefined
      if (machine) push(F('machine'), machine, { icon: 'MapPin' })
      break
    }

    case 'config_updated': {
      const cfg = m.config
      if (cfg && typeof cfg === 'object') {
        for (const [k, v] of Object.entries(cfg as Record<string, unknown>)) {
          push(k, v, { icon: 'Settings' })
        }
      }
      break
    }

    case 'stock_updated': {
      // Product rendered as a thumbnail (activityProductRef), not a chip.
      pushMachine()
      if (m.item_number != null) push(F('slot'), `#${m.item_number}`, { icon: 'Hash' })
      pushSource()
      if (m.old_stock != null && m.new_stock != null) {
        chips.push(stockChangeChip(t('activity.stockLabel'), Number(m.old_stock), Number(m.new_stock)))
      } else if (m.new_stock != null) {
        push(t('activity.stockLabel'), m.new_stock, { icon: 'Package' })
      }
      if (m.old_min_stock != null && m.new_min_stock != null) {
        chips.push(stockChangeChip(F('minStock'), Number(m.old_min_stock), Number(m.new_min_stock)))
      }
      if (m.old_capacity != null && m.new_capacity != null) {
        chips.push(stockChangeChip(F('capacity'), Number(m.old_capacity), Number(m.new_capacity)))
      }
      break
    }

    case 'stock_refill_all': {
      // Per-product breakdown is rendered from activityProductRefs, not chips.
      pushMachine()
      const trays = Array.isArray(m.trays_refilled) ? (m.trays_refilled as any[]) : []
      if (trays.length) {
        push(F('trays'), `${trays.length} ${t('activity.refilled')}`, { icon: 'LayoutGrid' })
      }
      break
    }

    case 'stock_refill_tour': {
      // Per-product breakdown is rendered from activityProductRefs, not chips.
      pushMachine()
      if (m.warehouse_name) push(F('warehouse'), m.warehouse_name, { icon: 'Warehouse' })
      if (m.trays_refilled != null) {
        const n = Array.isArray(m.trays_refilled) ? m.trays_refilled.length : m.trays_refilled
        push(F('trays'), n, { icon: 'LayoutGrid' })
      }
      if (m.total_added != null) push(F('totalAdded'), `+${m.total_added}`, { variant: 'increase', icon: 'Plus' })
      break
    }

    case 'stock_refill_tour_skip': {
      pushMachine()
      break
    }

    case 'tour_started': {
      if (m.warehouse_name) push(F('warehouse'), m.warehouse_name, { icon: 'Warehouse' })
      if (m.machine_count != null) push(F('machines'), String(m.machine_count), { icon: 'Boxes' })
      const names = Array.isArray(m.machine_names) ? (m.machine_names as unknown[]) : []
      if (names.length) {
        const shown = names.slice(0, 4).join(', ')
        push('', names.length > 4 ? `${shown}…` : shown, { icon: 'MapPin' })
      }
      break
    }

    case 'product_swapped': {
      pushMachine()
      if (m.item_number != null) push(F('slot'), `#${m.item_number}`, { icon: 'Hash' })
      if (m.old_product_name) push(F('fromProduct'), m.old_product_name, { variant: 'decrease' })
      if (m.new_product_name) push(F('toProduct'), m.new_product_name, { variant: 'increase', icon: 'ArrowRight' })
      break
    }

    case 'cash_book_created': {
      if (m.name) push(F('cashBook'), m.name, { icon: 'Wallet' })
      if (m.initial_balance != null) push(F('initialBalance'), euro(m.initial_balance), { icon: 'Euro' })
      if (m.threshold != null) push(F('threshold'), euro(m.threshold), { icon: 'Euro' })
      if (m.track_per_machine != null) {
        push(F('perMachine'), m.track_per_machine ? t('activity.yes') : t('activity.no'))
      }
      break
    }

    case 'cash_book_entry_created': {
      if (m.type) push(F('type'), m.type, { icon: 'Tag' })
      if (m.amount != null) push(F('amount'), euro(m.amount), { icon: 'Euro' })
      if (m.category) push(F('category'), m.category, { icon: 'Tag' })
      if (m.description) push(F('note'), m.description, { icon: 'StickyNote' })
      break
    }

    case 'machine_assigned_to_cash_book':
    case 'machine_unassigned_from_cash_book': {
      pushMachine()
      if (m.cash_book_name) push(F('cashBook'), m.cash_book_name, { icon: 'Wallet' })
      else if (m.name) push(F('cashBook'), m.name, { icon: 'Wallet' })
      break
    }

    case 'cash_book_settings_updated': {
      const patch = m.patch
      if (patch && typeof patch === 'object') {
        for (const [k, v] of Object.entries(patch as Record<string, unknown>)) {
          push(k, typeof v === 'boolean' ? (v ? t('activity.yes') : t('activity.no')) : v, { icon: 'Settings' })
        }
      }
      break
    }

    case 'poster_printed': {
      pushMachine()
      if (m.motif) push(F('motif'), t(`print.motifShort.${String(m.motif)}`), { icon: 'LayoutTemplate' })
      if (m.format) push(F('format'), t(`print.formats.${String(m.format)}`), { icon: 'FileText' })
      break
    }

    // cash_book_deleted and any other action → label only, no chips.
    default:
      break
  }

  return chips
}

// ── technical/debug details (expand panel) ──────────────────────────────────

/**
 * Curated, operator-useful fields not shown as chips — a deliberate
 * whitelist (not a raw metadata dump) so the expand panel stays readable.
 * Extend this per-action as new debugging needs come up.
 */
export function activityDetails(entry: ActivityEntryLike, ctx: DescriptorCtx): ActivityDetail[] {
  const m = entry.metadata
  if (!m) return []
  const { t } = ctx
  const F = (k: string) => t(`activity.field.${k}`)
  const details: ActivityDetail[] = []

  switch (entry.action) {
    case 'sale_recorded': {
      if (m.sale_seq != null) details.push({ label: F('saleSeq'), value: String(m.sale_seq) })
      if (m.time_uncertain === true) {
        details.push({ label: F('timeUncertain'), value: t('activity.timeUncertainWarning'), variant: 'warning' })
      }
      break
    }
    default:
      break
  }

  return details
}

// ── compact single-line summary (dashboard feed) ────────────────────────────

/** The most salient values joined with " · ", capped for a one-line feed. */
export function activitySummary(entry: ActivityEntryLike, ctx: DescriptorCtx): string {
  const parts: string[] = []
  const product = activityProductRef(entry)
  if (product?.productName) parts.push(product.productName)
  for (const c of activityChips(entry, ctx)) {
    if (parts.length >= 3) break
    parts.push(c.value)
  }
  if (parts.length) return parts.slice(0, 3).join(' · ')

  const m = entry.metadata
  if (!m) return ''
  const name = m.machine_name ?? m.product_name ?? m.device_name ?? m.name
  return name ? String(name) : ''
}
