import {
  bucketDaily,
  chartBucket,
  resolveRange,
  sortRows,
  type AnalyticsDimension,
  type AnalyticsMetric,
  type AnalyticsSummary,
  type BreakdownRow,
  type RangePreset,
  type SortDirection,
} from '~/lib/analytics'

interface MachineOption { id: string; name: string | null }
interface CategoryOption { id: string; name: string }

/**
 * Filter state and data loading for /analytics.
 *
 * State lives in useState so the filters survive navigating away and back —
 * losing a carefully-set custom range on a round trip to a machine page is the
 * kind of small betrayal that makes a report page feel disposable.
 */
export const useAnalytics = () => {
  const supabase = useSupabaseClient()
  const { organization } = useOrganization()

  const preset = useState<RangePreset>('analytics-preset', () => 'days30')
  const customFrom = useState<string>('analytics-custom-from', () => '')
  const customTo = useState<string>('analytics-custom-to', () => '')
  const machineIds = useState<string[]>('analytics-machine-ids', () => [])
  const categoryIds = useState<string[]>('analytics-category-ids', () => [])
  const metric = useState<AnalyticsMetric>('analytics-metric', () => 'revenue')
  const dimension = useState<AnalyticsDimension>('analytics-dimension', () => 'product')
  const sortDirection = useState<SortDirection>('analytics-sort', () => 'desc')
  /** created_at of the oldest sale, the anchor for the "all time" preset.
   *  null = looked up and there are none; undefined = not looked up yet. */
  const earliestSale = useState<string | null | undefined>('analytics-earliest', () => undefined)

  const summary = useState<AnalyticsSummary | null>('analytics-summary', () => null)
  const rows = useState<BreakdownRow[]>('analytics-rows', () => [])
  const machines = useState<MachineOption[]>('analytics-machines', () => [])
  const categories = useState<CategoryOption[]>('analytics-categories', () => [])

  const loading = useState<boolean>('analytics-loading', () => false)
  const loadingRows = useState<boolean>('analytics-loading-rows', () => false)
  const error = useState<string>('analytics-error', () => '')
  /** The connected backend predates the analytics migrations. */
  const backendUnsupported = useState<boolean>('analytics-unsupported', () => false)

  const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

  const range = computed(() =>
    resolveRange(preset.value, customFrom.value, customTo.value, new Date(), earliestSale.value))

  const sortedRows = computed(() => sortRows(rows.value, metric.value, sortDirection.value))

  const bucketedDaily = computed(() =>
    bucketDaily(summary.value?.daily ?? [], chartBucket(summary.value?.range.days ?? 30)))

  const dayFmt = (iso: string) => new Date(iso).toLocaleDateString(undefined, {
    day: '2-digit', month: '2-digit', year: 'numeric',
  })

  const rangeLabel = computed(() => {
    const r = range.value
    const lastDay = new Date(new Date(r.to).getTime() - 86_400_000)
    return `${dayFmt(r.from)} – ${dayFmt(lastDay.toISOString())}`
  })

  /**
   * The previous window is `[from - span, from)` — not "last month". Spelled
   * out in the UI so nobody reads it as a calendar period.
   */
  const previousRangeLabel = computed(() => {
    const r = range.value
    const span = new Date(r.to).getTime() - new Date(r.from).getTime()
    const prevFrom = new Date(new Date(r.from).getTime() - span)
    const prevLast = new Date(new Date(r.from).getTime() - 86_400_000)
    return `${dayFmt(prevFrom.toISOString())} – ${dayFmt(prevLast.toISOString())}`
  })

  /**
   * Label for the current selection, or null when unfiltered. Lives here
   * rather than in the filter bar because the breakdown list shows the same
   * label, and two copies would drift.
   */
  const activeMachineLabel = computed(() => {
    if (!machineIds.value.length) return null
    if (machineIds.value.length === 1) {
      return machines.value.find(x => x.id === machineIds.value[0])?.name ?? null
    }
    return String(machineIds.value.length)
  })

  const activeCategoryLabel = computed(() => {
    if (!categoryIds.value.length) return null
    if (categoryIds.value.length === 1) {
      return categories.value.find(x => x.id === categoryIds.value[0])?.name ?? null
    }
    return String(categoryIds.value.length)
  })

  async function clearMachineFilter() {
    machineIds.value = []
    await loadAll()
  }

  async function clearCategoryFilter() {
    categoryIds.value = []
    await loadAll()
  }

  function baseParams() {
    const companyId = organization.value?.id
    if (!companyId) throw new Error('No organization')
    const r = range.value
    return {
      p_company_id: companyId,
      p_from: r.from,
      p_to: r.to,
      p_machine_ids: machineIds.value.length ? machineIds.value : null,
      p_category_ids: categoryIds.value.length ? categoryIds.value : null,
      p_timezone: timezone(),
    }
  }

  /**
   * PostgREST answers an unknown function with PGRST202 — a backend-version
   * problem rather than a bug, and the whole page depends on it, so it gets
   * its own state instead of a raw error string.
   */
  function handle(err: unknown) {
    const code = (err as { code?: string })?.code
    const message = (err as { message?: string })?.message ?? String(err)
    if (code === 'PGRST202' || message.includes('Could not find the function')) {
      backendUnsupported.value = true
      error.value = ''
    } else {
      error.value = message
    }
  }

  async function loadSummary() {
    loading.value = true
    error.value = ''
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_summary', baseParams())
      if (err) throw err
      summary.value = data as AnalyticsSummary
      backendUnsupported.value = false
    } catch (err) {
      handle(err)
    } finally {
      loading.value = false
    }
  }

  async function loadBreakdown() {
    loadingRows.value = true
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_breakdown', {
          ...baseParams(),
          p_dimension: dimension.value,
          p_product_id: null,
        })
      if (err) throw err
      rows.value = (data ?? []) as BreakdownRow[]
      backendUnsupported.value = false
    } catch (err) {
      handle(err)
    } finally {
      loadingRows.value = false
    }
  }

  /**
   * Per-machine split of one product — the detail dialog reuses the breakdown
   * RPC with the machine dimension narrowed to a single product.
   */
  async function loadProductMachines(productId: string): Promise<BreakdownRow[]> {
    try {
      const { data, error: err } = await (supabase as any)
        .rpc('get_sales_analytics_breakdown', {
          ...baseParams(),
          p_dimension: 'machine',
          p_product_id: productId,
        })
      if (err) throw err
      return (data ?? []) as BreakdownRow[]
    } catch {
      return []
    }
  }

  /**
   * Oldest sale the caller can see. RLS already scopes this to the company, so
   * no filter is needed. Looked up once and cached: "all time" has to start
   * somewhere, and a fixed early date would make the RPC build one empty daily
   * bucket per day back to that date.
   */
  async function loadEarliestSale() {
    if (earliestSale.value !== undefined) return
    try {
      const { data, error: err } = await (supabase as any)
        .from('sales').select('created_at').order('created_at', { ascending: true }).limit(1)
      if (err) throw err
      earliestSale.value = (data?.[0]?.created_at as string) ?? null
    } catch {
      earliestSale.value = null
    }
  }

  async function loadFilterOptions() {
    await loadEarliestSale()
    const companyId = organization.value?.id
    if (!companyId) return
    const [machineRes, categoryRes] = await Promise.all([
      (supabase as any).from('vendingMachine').select('id, name')
        .eq('company', companyId).order('name'),
      (supabase as any).from('product_category').select('id, name')
        .eq('company', companyId).order('name'),
    ])
    // Non-fatal: without these the page still works as "all machines / all categories".
    machines.value = (machineRes.data ?? []) as MachineOption[]
    categories.value = (categoryRes.data ?? []) as CategoryOption[]
  }

  async function loadAll() {
    await Promise.all([loadSummary(), loadBreakdown()])
  }

  /**
   * Tapping a category or machine row narrows the filter to that entry and
   * switches to products — "which articles make up Drinks?" is the question
   * that row raises. The filter chip then shows the selection, so the step is
   * visible and undoable rather than a hidden mode change.
   *
   * Products are excluded: their click opens the detail dialog instead.
   */
  async function drillDown(row: BreakdownRow) {
    if (!row.key || dimension.value === 'product') return
    if (dimension.value === 'category') categoryIds.value = [row.key]
    else machineIds.value = [row.key]
    dimension.value = 'product'
    await loadAll()
  }

  return {
    preset, customFrom, customTo, machineIds, categoryIds, metric, dimension, sortDirection,
    summary, rows, machines, categories,
    loading, loadingRows, error, backendUnsupported,
    range, sortedRows, bucketedDaily, rangeLabel, previousRangeLabel,
    activeMachineLabel, activeCategoryLabel, clearMachineFilter, clearCategoryFilter,
    loadSummary, loadBreakdown, loadProductMachines, loadFilterOptions, loadAll, drillDown,
    earliestSale, loadEarliestSale,
  }
}
