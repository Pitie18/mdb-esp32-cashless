import { computed, ref, useRuntimeConfig, useSupabaseClient } from '#imports'
import QRCode from 'qrcode'
import {
  buildPrintSheetBase,
  isPublicOrigin,
  qrErrorLevel,
} from '@/lib/printSheet'
import type {
  PosterCompany,
  PosterMachine,
  PrintBlock,
  PrintFormat,
  PrintSheet,
} from '@/lib/printSheet'
import { useOrganization } from '@/composables/useOrganization'

const COMPANY_COLUMNS =
  'id, name, legal_name, contact_email, contact_phone, whatsapp_phone, support_hours, website, address_street, address_house_number, address_postal_code, address_city, country_code, logo_path'

const MACHINE_COLUMNS =
  'id, name, formatted_address, address_street, address_house_number, address_postal_code, address_city, contact_phone, whatsapp_phone, support_hours, contact_email'

export interface BuildSheetsOptions {
  machineIds: string[]
  blocks: PrintBlock[]
  format: PrintFormat
  /** Prefilled WhatsApp text; `%machine%` is substituted with the machine name. */
  whatsappTemplate?: string
  customText?: string | null
  /** Name used when a machine has none, in the poster's language. */
  fallbackMachineName: string
}

/**
 * Data + QR rendering behind `/machines/[id]/print`.
 *
 * The pure parts (contact inheritance, targets, origin guard) live in
 * `@/lib/printSheet` and are unit-tested; this composable only talks to
 * Supabase and turns targets into SVG.
 */
export function useMachinePrint() {
  const supabase = useSupabaseClient()
  const config = useRuntimeConfig()
  const { organization } = useOrganization()

  const company = ref<PosterCompany | null>(null)
  const machines = ref<PosterMachine[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /**
   * Origin the printed QR codes point at. `SITE_URL` is authoritative — it is
   * the same value GoTrue uses for auth mails, so it is correct wherever
   * password reset works. `window.location.origin` is only a dev fallback and
   * is exactly what `originIsPublic` guards against.
   */
  const publicOrigin = computed(() => {
    const configured = String(config.public.siteUrl ?? '').trim()
    if (configured) return configured.replace(/\/+$/, '')
    return import.meta.client ? window.location.origin : ''
  })

  const originIsPublic = computed(() => isPublicOrigin(publicOrigin.value))

  const logoUrl = computed(() => {
    const path = company.value?.logo_path?.trim()
    if (!path) return null
    const { data } = supabase.storage.from('company-logos').getPublicUrl(path)
    return data?.publicUrl ?? null
  })

  async function load() {
    const companyId = organization.value?.id
    if (!companyId) {
      error.value = 'no-organization'
      return
    }
    loading.value = true
    error.value = null
    try {
      const [companyRes, machineRes] = await Promise.all([
        supabase.from('companies').select(COMPANY_COLUMNS).eq('id', companyId).single(),
        supabase.from('vendingMachine').select(MACHINE_COLUMNS).eq('company', companyId).order('name'),
      ])
      if (companyRes.error) throw companyRes.error
      if (machineRes.error) throw machineRes.error
      company.value = companyRes.data as unknown as PosterCompany
      machines.value = (machineRes.data ?? []) as unknown as PosterMachine[]
    } catch (e) {
      error.value = (e as Error)?.message ?? 'load-failed'
    } finally {
      loading.value = false
    }
  }

  async function renderQr(target: string | null, format: PrintFormat): Promise<string | null> {
    if (!target) return null
    // Vector, not a data URL: at 5 cm on paper the difference is visible.
    // margin 4 is the quiet zone — without it the code does not scan from 50 cm.
    return QRCode.toString(target, {
      type: 'svg',
      margin: 4,
      errorCorrectionLevel: qrErrorLevel(format),
      color: { dark: '#000000', light: '#ffffff' },
    })
  }

  async function buildSheets(opts: BuildSheetsOptions): Promise<PrintSheet[]> {
    const co = company.value
    if (!co) return []
    const byId = new Map(machines.value.map(m => [m.id, m]))
    const selected = opts.machineIds
      .map(id => byId.get(id))
      .filter((m): m is PosterMachine => Boolean(m))

    return Promise.all(
      selected.map(async (machine) => {
        const base = buildPrintSheetBase({
          machine,
          company: co,
          publicOrigin: publicOrigin.value,
          blocks: opts.blocks,
          whatsappTemplate: opts.whatsappTemplate,
          customText: opts.customText,
          logoUrl: logoUrl.value,
          fallbackMachineName: opts.fallbackMachineName,
        })
        const [page, tel, whatsapp, problem] = await Promise.all([
          renderQr(base.targets.page, opts.format),
          renderQr(base.targets.tel, opts.format),
          renderQr(base.targets.whatsapp, opts.format),
          renderQr(base.targets.problem, opts.format),
        ])
        return { ...base, qr: { page: page ?? '', tel, whatsapp, problem } }
      }),
    )
  }

  /**
   * One row per machine. Written when the print is *triggered*: whether the
   * user then cancels the system dialog is not observable from the browser, so
   * the entry claims no more than that.
   */
  async function logPrinted(
    machineIds: string[],
    meta: { motif: string; format: PrintFormat; blocks: PrintBlock[]; sheetLanguage: string },
  ) {
    const companyId = organization.value?.id
    if (!companyId || machineIds.length === 0) return
    const rows = machineIds.map(id => ({
      company_id: companyId,
      entity_type: 'machine',
      entity_id: id,
      action: 'poster_printed',
      metadata: {
        // Repeated from entity_id so the activity descriptor can resolve the
        // machine name the same way it does for every other action.
        machine_id: id,
        motif: meta.motif,
        format: meta.format,
        blocks: meta.blocks,
        sheet_language: meta.sheetLanguage,
      },
    }))
    const { error: insertError } = await supabase.from('activity_log').insert(rows as never)
    // A failed audit row must not block the print job the user asked for.
    if (insertError) console.warn('[machine-print] activity log failed', insertError)
  }

  return {
    company,
    machines,
    loading,
    error,
    publicOrigin,
    originIsPublic,
    logoUrl,
    load,
    buildSheets,
    logPrinted,
  }
}
