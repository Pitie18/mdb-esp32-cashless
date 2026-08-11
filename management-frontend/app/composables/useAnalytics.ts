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

  const range = computed(() => resolveRange(preset.value, customFrom.value, customTo.value))

  const sortedRows = computed(() => sortRows(rows.value, metric.value))

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

  async function loadFilterOptions() {
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

  return {
    preset, customFrom, customTo, machineIds, categoryIds, metric, dimension,
    summary, rows, machines, categories,
    loading, loadingRows, error, backendUnsupported,
    range, sortedRows, bucketedDaily, rangeLabel, previousRangeLabel,
    loadSummary, loadBreakdown, loadProductMachines, loadFilterOptions, loadAll,
  }
}
