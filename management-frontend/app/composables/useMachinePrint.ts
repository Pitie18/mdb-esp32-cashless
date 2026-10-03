import { computed, ref, useRuntimeConfig, useSupabaseClient } from '#imports'
import QRCode from 'qrcode'
import {
  buildPrintSheetBase,
  isPublicOrigin,
  posterFingerprint,
  qrErrorLevel,
} from '@/lib/printSheet'
import type {
  PosterCompany,
  PosterLayout,
  PosterMachine,
  PosterT,
  PrintFormat,
  PrintSheet,
  SlotDeclaration,
} from '@/lib/printSheet'
import { useOrganization } from '@/composables/useOrganization'

const COMPANY_COLUMNS =
  'id, name, legal_name, contact_email, contact_phone, whatsapp_phone, support_hours, website, address_street, address_house_number, address_postal_code, address_city, country_code, logo_path'

const MACHINE_COLUMNS =
  'id, name, formatted_address, address_street, address_house_number, address_postal_code, address_city, contact_phone, whatsapp_phone, support_hours, contact_email'

export interface BuildSheetsOptions {
  machineIds: string[]
  slotDeclarations: SlotDeclaration[]
  layout: PosterLayout
  format: PrintFormat
  t: PosterT
  /** Prefilled WhatsApp text; `%machine%` is substituted with the machine name. */
  whatsappTemplate?: string
  customText?: string | null
  /** Name used when a machine has none, in the poster's language. */
  fallbackMachineName: string
}

interface LayoutRow {
  id: string
  machine_id: string | null
  motif: string
  config: PosterLayout | null
}

/**
 * Data, QR rendering and layout persistence behind `/machines/[id]/print`.
 *
 * The pure parts (contact inheritance, slot resolution, origin guard) live in
 * `@/lib/printSheet` and are unit-tested; this composable only talks to
 * Supabase and turns targets into SVG.
 */
export function useMachinePrint() {
  const supabase = useSupabaseClient()
  const config = useRuntimeConfig()
  const { organization, role } = useOrganization()

  const company = ref<PosterCompany | null>(null)
  const machines = ref<PosterMachine[]>([])
  const layoutRows = ref<LayoutRow[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const canSave = computed(() => role.value === 'admin')

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
      const [companyRes, machineRes, layoutRes] = await Promise.all([
        supabase.from('companies').select(COMPANY_COLUMNS).eq('id', companyId).single(),
        supabase.from('vendingMachine').select(MACHINE_COLUMNS).eq('company', companyId).order('name'),
        supabase.from('poster_layouts').select('id, machine_id, motif, config').eq('company_id', companyId),
      ])
      if (companyRes.error) throw companyRes.error
      if (machineRes.error) throw machineRes.error
      if (layoutRes.error) throw layoutRes.error
      company.value = companyRes.data as unknown as PosterCompany
      machines.value = (machineRes.data ?? []) as unknown as PosterMachine[]
      layoutRows.value = (layoutRes.data ?? []) as unknown as LayoutRow[]
    } catch (e) {
      error.value = (e as Error)?.message ?? 'load-failed'
    } finally {
      loading.value = false
    }
  }

  /** Machine override wins over the company default, same as the contact data. */
  function storedLayout(motif: string, machineId: string): PosterLayout | null {
    const override = layoutRows.value.find(r => r.motif === motif && r.machine_id === machineId)
    if (override?.config) return override.config
    const fallback = layoutRows.value.find(r => r.motif === motif && r.machine_id === null)
    return fallback?.config ?? null
  }

  function layoutScope(motif: string, machineId: string): 'machine' | 'company' | 'none' {
    if (layoutRows.value.some(r => r.motif === motif && r.machine_id === machineId)) return 'machine'
    if (layoutRows.value.some(r => r.motif === motif && r.machine_id === null)) return 'company'
    return 'none'
  }

  async function saveLayout(
    motif: string,
    layout: PosterLayout,
    scope: 'company' | 'machine',
    machineId: string,
  ) {
    const companyId = organization.value?.id
    if (!companyId) return
    const row = {
      company_id: companyId,
      machine_id: scope === 'machine' ? machineId : null,
      motif,
      config: layout,
      updated_at: new Date().toISOString(),
    }
    // The partial unique indexes make (company, motif) and (machine, motif)
    // one row each, so an explicit lookup-then-write keeps the intent clear
    // without depending on a named constraint for onConflict.
    const existing = layoutRows.value.find(
      r => r.motif === motif && r.machine_id === (scope === 'machine' ? machineId : null),
    )
    const { error: writeError } = existing
      ? await supabase.from('poster_layouts').update(row as never).eq('id', existing.id)
      : await supabase.from('poster_layouts').insert(row as never)
    if (writeError) throw writeError
    await load()
  }

  async function deleteLayout(motif: string, scope: 'company' | 'machine', machineId: string) {
    const existing = layoutRows.value.find(
      r => r.motif === motif && r.machine_id === (scope === 'machine' ? machineId : null),
    )
    if (!existing) return
    const { error: deleteError } = await supabase
      .from('poster_layouts')
      .delete()
      .eq('id', existing.id)
    if (deleteError) throw deleteError
    await load()
  }

  /**
   * Same target, same SVG — and the motif gallery asks for a handful of
   * targets across nine motifs, so without this the identical machine-page
   * code gets encoded a dozen times on every render.
   */
  const qrCache = new Map<string, string>()

  async function renderQr(target: string | null, format: PrintFormat): Promise<string | null> {
    if (!target) return null
    const level = qrErrorLevel(format)
    const key = `${level}|${target}`
    const cached = qrCache.get(key)
    if (cached) return cached
    // Vector, not a data URL: at 5 cm on paper the difference is visible.
    // margin 4 is the quiet zone — without it the code does not scan from 50 cm.
    const svg = await QRCode.toString(target, {
      type: 'svg',
      margin: 4,
      errorCorrectionLevel: level,
      color: { dark: '#000000', light: '#ffffff' },
    })
    qrCache.set(key, svg)
    return svg
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
        const sheet = buildPrintSheetBase({
          machine,
          company: co,
          publicOrigin: publicOrigin.value,
          slotDeclarations: opts.slotDeclarations,
          layout: opts.layout,
          t: opts.t,
          whatsappTemplate: opts.whatsappTemplate,
          customText: opts.customText,
          logoUrl: logoUrl.value,
          fallbackMachineName: opts.fallbackMachineName,
        })
        await Promise.all(
          Object.values(sheet.slots).map(async (slot) => {
            slot.qr = await renderQr(slot.target, opts.format)
          }),
        )
        return sheet
      }),
    )
  }

  /**
   * One row per printed sheet. Written when the print is *triggered*: whether
   * the user then cancels the system dialog is not observable from the browser,
   * so the entry claims no more than that.
   *
   * The layout and the contact fingerprint travel with the row. That is what
   * later lets the machine list tell "this sign is still correct" from "this
   * sign shows a number nobody answers any more".
   */
  async function logPrinted(
    sheets: PrintSheet[],
    meta: {
      motif: string
      format: PrintFormat
      layout: PosterLayout
      slotDeclarations: SlotDeclaration[]
      sheetLanguage: string
    },
  ) {
    const companyId = organization.value?.id
    if (!companyId || sheets.length === 0) return
    const rows = sheets.map(sheet => ({
      company_id: companyId,
      entity_type: 'machine',
      entity_id: sheet.machineId,
      action: 'poster_printed',
      metadata: {
        // Repeated from entity_id so the activity descriptor can resolve the
        // machine name the same way it does for every other action.
        machine_id: sheet.machineId,
        motif: meta.motif,
        format: meta.format,
        blocks: meta.layout.blocks ?? [],
        layout: meta.layout,
        slot_declarations: meta.slotDeclarations,
        sheet_language: meta.sheetLanguage,
        contact_fingerprint: posterFingerprint(sheet),
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
    canSave,
    publicOrigin,
    originIsPublic,
    logoUrl,
    load,
    storedLayout,
    layoutScope,
    saveLayout,
    deleteLayout,
    buildSheets,
    logPrinted,
  }
}
