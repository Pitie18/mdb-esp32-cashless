/**
 * Pure logic behind the printable machine posters (`/machines/[id]/print`).
 *
 * Everything here is side-effect free and unit-tested: contact inheritance,
 * phone normalisation, the public-origin guard and the QR *targets*. Actually
 * turning a target into an SVG happens in `useMachinePrint()`, because that
 * part is async and browser-bound.
 */

export type PrintFormat = 'a4' | 'a5' | 'a6' | 'sticker-sheet'

/** Optional content blocks a motif may render, toggled by the operator. */
export type PrintBlock = 'phone' | 'whatsapp' | 'problem' | 'imprint'

/** Fields a poster wants but could not fill — surfaced as a warning, never printed blank. */
export type MissingField =
  | 'companyName'
  | 'phone'
  | 'whatsapp'
  | 'whatsappCountry'
  | 'email'
  | 'address'

export interface PosterCompany {
  id?: string
  name?: string | null
  legal_name?: string | null
  contact_email?: string | null
  contact_phone?: string | null
  whatsapp_phone?: string | null
  support_hours?: string | null
  website?: string | null
  address_street?: string | null
  address_house_number?: string | null
  address_postal_code?: string | null
  address_city?: string | null
  country_code?: string | null
  logo_path?: string | null
}

export interface PosterMachine {
  id: string
  name?: string | null
  formatted_address?: string | null
  address_street?: string | null
  address_house_number?: string | null
  address_postal_code?: string | null
  address_city?: string | null
  contact_phone?: string | null
  whatsapp_phone?: string | null
  support_hours?: string | null
  contact_email?: string | null
}

/** Encoded QR payloads. `null` means "this block is off or has no data". */
export interface PrintSheetTargets {
  page: string
  tel: string | null
  whatsapp: string | null
  problem: string | null
}

/** Rendered QR markup (SVG strings), same nullability as the targets. */
export interface PrintSheetQr {
  page: string
  tel: string | null
  whatsapp: string | null
  problem: string | null
}

/**
 * The complete, already-resolved input to a motif component. Motifs never
 * query the database, never know a machine id and never build a QR code —
 * they lay out exactly this object.
 */
export interface PrintSheetBase {
  machineId: string
  machineName: string
  /** Location hint under the machine name, e.g. the formatted address. */
  machineNote: string | null
  companyName: string
  addressLine: string | null
  email: string | null
  website: string | null
  phone: string | null
  whatsapp: string | null
  hours: string | null
  logoUrl: string | null
  /** One-off note for this print run only; never persisted. */
  customText: string | null
  /** Absolute public URL of the machine page, also printable as plain text. */
  pageUrl: string
  targets: PrintSheetTargets
  missing: MissingField[]
}

export interface PrintSheet extends PrintSheetBase {
  qr: PrintSheetQr
}

/** Physical sheet size in millimetres, portrait. */
export const FORMAT_MM: Record<PrintFormat, { w: number; h: number }> = {
  a4: { w: 210, h: 297 },
  a5: { w: 148, h: 210 },
  a6: { w: 105, h: 148 },
  'sticker-sheet': { w: 210, h: 297 },
}

/**
 * Smallest QR edge that still scans from arm's length. Below these values the
 * poster looks fine on screen and fails at the machine, so motifs treat them
 * as constants rather than as styling.
 */
export const MIN_QR_MM: Record<PrintFormat, number> = {
  a4: 30,
  a5: 30,
  a6: 25,
  'sticker-sheet': 20,
}

/** 90 x 50 mm labels, 2 columns x 4 rows on A4. */
export const STICKER_MM = { w: 90, h: 50, gap: 3 }
export const STICKERS_PER_SHEET = 8

/**
 * QR error correction. Stickers sit next to the coin return and get scratched
 * and dirty, so they carry more redundancy than a poster behind glass.
 */
export function qrErrorLevel(format: PrintFormat): 'M' | 'Q' {
  return format === 'sticker-sheet' ? 'Q' : 'M'
}

const PRIVATE_IPV4 =
  /^(10\.|127\.|0\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/

/**
 * Would a QR built from this origin work on a customer's phone?
 *
 * A printed poster is permanent, so a LAN origin is not a warning but a stack
 * of wasted paper. Ports other than the defaults also count as non-public:
 * they are the tell-tale of a dev server or an unproxied container.
 */
export function isPublicOrigin(origin: string | null | undefined): boolean {
  if (!origin) return false
  let url: URL
  try {
    url = new URL(origin)
  } catch {
    return false
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return false
  if (url.port && url.port !== '80' && url.port !== '443') return false

  const host = url.hostname.toLowerCase()
  if (host === 'localhost' || host.endsWith('.localhost')) return false
  if (host === '::1' || host === '[::1]') return false
  if (host.endsWith('.local') || host.endsWith('.internal') || host.endsWith('.lan')) return false
  if (PRIVATE_IPV4.test(host)) return false
  // A bare hostname without a dot only resolves inside one network.
  if (!host.includes('.')) return false
  return true
}

/**
 * Machine value wins, company value inherits, whitespace counts as unset.
 * Returns `null` when neither side has anything, so callers can branch on it
 * instead of printing an empty line.
 */
export function inherit(
  machineValue: string | null | undefined,
  companyValue: string | null | undefined,
): string | null {
  const m = machineValue?.trim()
  if (m) return m
  const c = companyValue?.trim()
  return c || null
}

/**
 * Digits plus a leading `+`, suitable for a `tel:` href. The German-style
 * `(0)` trunk marker is dropped: kept as a digit it turns a valid
 * international number into one that does not connect.
 */
export function normalizePhone(raw: string | null | undefined): string | null {
  if (!raw) return null
  const trimmed = raw.trim().replace(/\(0\)/g, '')
  if (!trimmed) return null
  const plus = trimmed.startsWith('+')
  const digits = trimmed.replace(/\D/g, '')
  if (!digits) return null
  return plus ? `+${digits}` : digits
}

/**
 * Dialling codes for the countries this product actually ships to. Anything
 * else falls through to `null` rather than guessing — a wrong country code
 * sends the customer's WhatsApp message to a stranger.
 */
const DIALLING_CODES: Record<string, string> = {
  DE: '49', AT: '43', CH: '41', NL: '31', BE: '32', LU: '352',
  FR: '33', IT: '39', ES: '34', PT: '351', PL: '48', CZ: '420',
  DK: '45', SE: '46', NO: '47', FI: '358', GB: '44', IE: '353',
}

/**
 * International digits without `+`, the format `wa.me/<number>` expects.
 *
 * A national number (leading `0`) needs the company's country to be resolvable;
 * without it we return `null` and the caller drops the WhatsApp block.
 */
export function toWaNumber(
  raw: string | null | undefined,
  countryCode: string | null | undefined,
): string | null {
  if (!raw) return null
  const trimmed = raw.trim().replace(/\(0\)/g, '')
  if (!trimmed) return null
  const digits = trimmed.replace(/\D/g, '')
  if (!digits) return null

  if (trimmed.startsWith('+')) return digits
  if (digits.startsWith('00')) return digits.slice(2) || null
  if (digits.startsWith('0')) {
    const cc = countryCode?.trim().toUpperCase()
    const dial = cc ? DIALLING_CODES[cc] : undefined
    if (!dial) return null
    return `${dial}${digits.replace(/^0+/, '')}`
  }
  // Already international without a prefix (e.g. "4915112345678").
  return digits
}

function joinAddress(parts: {
  street?: string | null
  houseNumber?: string | null
  postalCode?: string | null
  city?: string | null
}): string | null {
  const street = [parts.street?.trim(), parts.houseNumber?.trim()].filter(Boolean).join(' ')
  const town = [parts.postalCode?.trim(), parts.city?.trim()].filter(Boolean).join(' ')
  const line = [street, town].filter(Boolean).join(' · ')
  return line || null
}

export interface BuildPrintSheetInput {
  machine: PosterMachine
  company: PosterCompany
  /** Absolute origin the printed QR codes point at, no trailing slash. */
  publicOrigin: string
  blocks: PrintBlock[]
  /**
   * Prefilled WhatsApp message. `%machine%` is replaced with the machine name —
   * deliberately not `{machine}`, which vue-i18n would consume as its own
   * interpolation placeholder before the string ever reaches us.
   */
  whatsappTemplate?: string
  customText?: string | null
  logoUrl?: string | null
  fallbackMachineName: string
}

/**
 * Resolves company + machine into everything a motif needs, and records which
 * requested fields could not be filled.
 */
export function buildPrintSheetBase(input: BuildPrintSheetInput): PrintSheetBase {
  const { machine, company, blocks, publicOrigin } = input
  const origin = publicOrigin.replace(/\/+$/, '')
  const missing: MissingField[] = []

  const companyName =
    company.legal_name?.trim() || company.name?.trim() || ''
  if (!companyName) missing.push('companyName')

  const phone = inherit(machine.contact_phone, company.contact_phone)
  const whatsappRaw = inherit(machine.whatsapp_phone, company.whatsapp_phone)
  const email = inherit(machine.contact_email, company.contact_email)
  const hours = inherit(machine.support_hours, company.support_hours)

  const addressLine =
    joinAddress({
      street: company.address_street,
      houseNumber: company.address_house_number,
      postalCode: company.address_postal_code,
      city: company.address_city,
    })

  const machineNote =
    machine.formatted_address?.trim() ||
    joinAddress({
      street: machine.address_street,
      houseNumber: machine.address_house_number,
      postalCode: machine.address_postal_code,
      city: machine.address_city,
    })

  const wantsPhone = blocks.includes('phone')
  const wantsWhatsapp = blocks.includes('whatsapp')
  const wantsProblem = blocks.includes('problem')
  const wantsImprint = blocks.includes('imprint')

  if (wantsPhone && !phone) missing.push('phone')
  if (wantsImprint && !email) missing.push('email')
  if (wantsImprint && !addressLine) missing.push('address')

  const telNumber = wantsPhone ? normalizePhone(phone) : null
  const waNumber = wantsWhatsapp ? toWaNumber(whatsappRaw, company.country_code) : null
  if (wantsWhatsapp && !whatsappRaw) missing.push('whatsapp')
  if (wantsWhatsapp && whatsappRaw && !waNumber) missing.push('whatsappCountry')

  const machineName = machine.name?.trim() || input.fallbackMachineName
  const pageUrl = `${origin}/m/${machine.id}`

  let whatsappTarget: string | null = null
  if (waNumber) {
    const template = input.whatsappTemplate?.trim()
    const text = template ? template.replaceAll('%machine%', machineName) : ''
    whatsappTarget = text
      ? `https://wa.me/${waNumber}?text=${encodeURIComponent(text)}`
      : `https://wa.me/${waNumber}`
  }

  return {
    machineId: machine.id,
    machineName,
    machineNote,
    companyName,
    addressLine,
    email,
    website: company.website?.trim() || null,
    phone: wantsPhone ? phone : null,
    whatsapp: waNumber ? whatsappRaw : null,
    hours,
    logoUrl: input.logoUrl?.trim() || null,
    customText: input.customText?.trim() || null,
    pageUrl,
    targets: {
      page: pageUrl,
      tel: telNumber ? `tel:${telNumber}` : null,
      whatsapp: whatsappTarget,
      problem: wantsProblem ? `${pageUrl}?feedback=problem` : null,
    },
    missing,
  }
}

/**
 * Fingerprint of the contact data a poster actually carries.
 *
 * Stored alongside each `poster_printed` entry so a later comparison can tell
 * whether the paper on the machine still matches reality — the failure mode
 * being a changed support number that nobody reprints, leaving customers to
 * call a dead line for months.
 *
 * Deliberately excluded:
 * - `customText`, which is per-print and not persisted, so including it would
 *   flag every poster whose one-off note happened to differ.
 * - the logo, which is cosmetic: a new logo does not make a sign *wrong*.
 *   (A logo replaced at the same storage path is invisible here either way.)
 * - `machineNote`, because a re-geocoded address can shift by a word without
 *   anything on the sign becoming incorrect.
 *
 * Blocks that were switched off contribute `null`, so turning a block on later
 * counts as a change while leaving it off never raises a false alarm.
 *
 * The WhatsApp target contributes only a marker, not the full `wa.me` URL: the
 * URL carries a prefilled message in the sheet's language, and reprinting the
 * same sign in French must not read as the contact data having changed.
 */
export function posterFingerprint(base: PrintSheetBase): string {
  const parts = [
    base.machineName,
    base.companyName,
    base.addressLine,
    base.email,
    base.website,
    base.phone,
    base.whatsapp,
    base.hours,
    base.targets.page,
    base.targets.tel,
    base.targets.whatsapp ? 'wa' : null,
    base.targets.problem,
  ]
  return fnv1a(parts.map(p => p ?? ' ').join(''))
}

/**
 * FNV-1a, 32 bit, hex. Not a security primitive — this only ever compares two
 * fingerprints for equality, so collision resistance is irrelevant and a
 * dependency-free hash beats pulling in crypto for a nine-line job.
 */
function fnv1a(input: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}

/**
 * Packs stickers continuously across A4 sheets rather than one sheet per
 * machine — printing three machines should waste zero labels, not 21.
 */
export function distributeStickers<T>(items: T[], perSheet = STICKERS_PER_SHEET): T[][] {
  if (perSheet <= 0) return items.length ? [items] : []
  const sheets: T[][] = []
  for (let i = 0; i < items.length; i += perSheet) {
    sheets.push(items.slice(i, i + perSheet))
  }
  return sheets
}
