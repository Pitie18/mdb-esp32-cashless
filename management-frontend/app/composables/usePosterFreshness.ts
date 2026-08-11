import { computed, ref, useI18n, useRuntimeConfig, useSupabaseClient } from '#imports'
import { buildPrintSheetBase, posterFingerprint } from '@/lib/printSheet'
import type { PosterCompany, PosterLayout, PosterMachine, SlotDeclaration } from '@/lib/printSheet'
import { useOrganization } from '@/composables/useOrganization'

const COMPANY_COLUMNS =
  'id, name, legal_name, contact_email, contact_phone, whatsapp_phone, support_hours, website, address_street, address_house_number, address_postal_code, address_city, country_code'

const MACHINE_COLUMNS =
  'id, name, formatted_address, address_street, address_house_number, address_postal_code, address_city, contact_phone, whatsapp_phone, support_hours, contact_email'

/**
 * How many `poster_printed` rows to look at. We only need the newest row per
 * machine; the cap keeps a long-lived company's audit history from being
 * pulled down whole.
 */
const HISTORY_LIMIT = 500

export type PosterState = 'never' | 'current' | 'outdated'

export interface PosterStatus {
  state: PosterState
  /** When the machine was last printed. Null for `never`. */
  printedAt: string | null
}

interface PrintedRow {
  entity_id: string | null
  created_at: string
  metadata: {
    layout?: PosterLayout
    slot_declarations?: SlotDeclaration[]
    sheet_language?: string
    contact_fingerprint?: string
  } | null
}

/**
 * Answers, per machine: does the sign hanging on it still show the current
 * contact data?
 *
 * The comparison recomputes the fingerprint from *today's* data using the
 * blocks that were actually printed, and holds it against the fingerprint
 * stored with the print. Like against like — switching motifs or reprinting in
 * another language is not a change; a new support number is.
 */
export function usePosterFreshness() {
  const supabase = useSupabaseClient()
  const config = useRuntimeConfig()
  const { t } = useI18n()
  const { organization } = useOrganization()

  const statuses = ref<Record<string, PosterStatus>>({})
  const loading = ref(false)

  const outdatedCount = computed(
    () => Object.values(statuses.value).filter(s => s.state === 'outdated').length,
  )

  function statusFor(machineId: string): PosterStatus {
    return statuses.value[machineId] ?? { state: 'never', printedAt: null }
  }

  function isOutdated(machineId: string): boolean {
    return statusFor(machineId).state === 'outdated'
  }

  async function load() {
    const companyId = organization.value?.id
    if (!companyId) return
    loading.value = true
    try {
      const [companyRes, machineRes, printedRes] = await Promise.all([
        supabase.from('companies').select(COMPANY_COLUMNS).eq('id', companyId).single(),
        supabase.from('vendingMachine').select(MACHINE_COLUMNS).eq('company', companyId),
        supabase
          .from('activity_log')
          .select('entity_id, created_at, metadata')
          .eq('company_id', companyId)
          .eq('action', 'poster_printed')
          .order('created_at', { ascending: false })
          .limit(HISTORY_LIMIT),
      ])
      if (companyRes.error || machineRes.error || printedRes.error) return

      const company = companyRes.data as unknown as PosterCompany
      const machines = (machineRes.data ?? []) as unknown as PosterMachine[]
      const rows = (printedRes.data ?? []) as unknown as PrintedRow[]

      // Rows arrive newest first, so the first hit per machine is the latest.
      const latest = new Map<string, PrintedRow>()
      for (const row of rows) {
        if (!row.entity_id || latest.has(row.entity_id)) continue
        latest.set(row.entity_id, row)
      }

      const next: Record<string, PosterStatus> = {}
      const publicOrigin =
        String(config.public.siteUrl ?? '').trim().replace(/\/+$/, '') ||
        (import.meta.client ? window.location.origin : '')

      for (const machine of machines) {
        const row = latest.get(machine.id)
        const stored = row?.metadata?.contact_fingerprint
        const layout = row?.metadata?.layout
        const declarations = row?.metadata?.slot_declarations
        // Without a stored layout there is nothing to compare like against
        // like, so we claim nothing rather than guessing "outdated".
        if (!row || !stored || !layout || !declarations) {
          next[machine.id] = { state: 'never', printedAt: row?.created_at ?? null }
          continue
        }
        const language = row.metadata?.sheet_language
        const sheetT = (key: string) =>
          language
            ? (t as unknown as (k: string, n: Record<string, unknown>, o: { locale: string }) => string)(
                key, {}, { locale: language },
              )
            : t(key)
        const base = buildPrintSheetBase({
          machine,
          company,
          publicOrigin,
          slotDeclarations: declarations,
          layout,
          t: sheetT,
          fallbackMachineName: sheetT('print.fallbackMachineName'),
        })
        next[machine.id] = {
          state: posterFingerprint(base) === stored ? 'current' : 'outdated',
          printedAt: row.created_at,
        }
      }
      statuses.value = next
    } finally {
      loading.value = false
    }
  }

  return { statuses, loading, outdatedCount, statusFor, isOutdated, load }
}
