import { describe, it, expect } from 'vitest'
import {
  buildPrintSheetBase,
  distributeStickers,
  inherit,
  isPublicOrigin,
  normalizeCustomUrl,
  normalizePhone,
  posterFingerprint,
  isStickerFormat,
  qrErrorLevel,
  readableUrl,
  stickerLayout,
  stickersPerSheet,
  toWaNumber,
} from '../printSheet'
import type {
  PosterCompany,
  PosterLayout,
  PosterMachine,
  PrintBlock,
  SlotDeclaration,
} from '../printSheet'

// Identity `t`: returns the key so assertions can name which default was used.
const t = (key: string) => key

const ALL_BLOCKS: PrintBlock[] = ['phone', 'imprint', 'url']

const ONE_SLOT: SlotDeclaration[] = [
  { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
]

const THREE_SLOTS: SlotDeclaration[] = [
  { id: 'tile1', labelKey: 'print.slots.tile1', defaultSource: 'page', optional: false },
  { id: 'tile2', labelKey: 'print.slots.tile2', defaultSource: 'whatsapp', optional: true },
  { id: 'tile3', labelKey: 'print.slots.tile3', defaultSource: 'problem', optional: true },
]

const company: PosterCompany = {
  id: 'c-1',
  name: 'Muster Vending',
  legal_name: 'Muster Vending GmbH',
  contact_email: 'service@muster-vending.de',
  contact_phone: '+49 561 123456',
  whatsapp_phone: '0151 22334455',
  support_hours: 'Mo–Fr 8–18 Uhr',
  website: 'https://muster-vending.de',
  address_street: 'Musterstr.',
  address_house_number: '4',
  address_postal_code: '34117',
  address_city: 'Kassel',
  country_code: 'DE',
}

const machine: PosterMachine = {
  id: 'm-1',
  name: 'Automat 12',
  address_street: 'An der Kelter',
  address_house_number: '15',
  address_postal_code: '74653',
  address_city: 'Ingelfingen',
}

function build(
  over: {
    machine?: Partial<PosterMachine>
    company?: Partial<PosterCompany>
    layout?: PosterLayout
    slots?: SlotDeclaration[]
  } = {},
) {
  return buildPrintSheetBase({
    machine: { ...machine, ...over.machine },
    company: { ...company, ...over.company },
    publicOrigin: 'https://app.muster-vending.de',
    slotDeclarations: over.slots ?? ONE_SLOT,
    layout: over.layout ?? { blocks: ALL_BLOCKS },
    t,
    whatsappTemplate: 'Hallo, ich habe ein Problem am %machine%.',
    fallbackMachineName: 'Automat',
  })
}

describe('isPublicOrigin', () => {
  it('accepts a real https domain', () => {
    expect(isPublicOrigin('https://app.muster-vending.de')).toBe(true)
    expect(isPublicOrigin('http://vmflow.example.com')).toBe(true)
    expect(isPublicOrigin('https://app.example.com:443')).toBe(true)
  })

  it('rejects loopback and dev servers', () => {
    expect(isPublicOrigin('http://localhost:3000')).toBe(false)
    expect(isPublicOrigin('http://127.0.0.1:3000')).toBe(false)
    expect(isPublicOrigin('http://[::1]:3000')).toBe(false)
  })

  it('rejects private ranges', () => {
    expect(isPublicOrigin('http://10.0.1.181:3000')).toBe(false)
    expect(isPublicOrigin('http://192.168.1.20')).toBe(false)
    expect(isPublicOrigin('http://172.16.4.9')).toBe(false)
    expect(isPublicOrigin('http://172.31.4.9')).toBe(false)
    // 172.32 is outside the private block and stays public.
    expect(isPublicOrigin('http://172.32.4.9')).toBe(true)
  })

  it('rejects LAN-only hostnames', () => {
    expect(isPublicOrigin('http://vmflow.local')).toBe(false)
    expect(isPublicOrigin('http://nas.lan')).toBe(false)
    expect(isPublicOrigin('http://vmflow')).toBe(false)
  })

  it('rejects a non-default port even on a real domain', () => {
    expect(isPublicOrigin('https://app.muster-vending.de:8443')).toBe(false)
  })

  it('rejects empty and unparsable input', () => {
    expect(isPublicOrigin('')).toBe(false)
    expect(isPublicOrigin(null)).toBe(false)
    expect(isPublicOrigin('not a url')).toBe(false)
    expect(isPublicOrigin('ftp://example.com')).toBe(false)
  })
})

describe('inherit', () => {
  it('prefers the machine value', () => {
    expect(inherit('+49 111', '+49 222')).toBe('+49 111')
  })

  it('falls back to the company value', () => {
    expect(inherit(null, '+49 222')).toBe('+49 222')
    expect(inherit(undefined, '+49 222')).toBe('+49 222')
  })

  it('treats whitespace as unset', () => {
    expect(inherit('   ', '+49 222')).toBe('+49 222')
    expect(inherit('   ', '   ')).toBeNull()
  })

  it('returns null when neither side has a value', () => {
    expect(inherit(null, null)).toBeNull()
  })
})

describe('normalizePhone', () => {
  it('keeps a leading plus and drops formatting', () => {
    expect(normalizePhone('+49 (0)561 123-456')).toBe('+49561123456')
    expect(normalizePhone('0561 / 123 456')).toBe('0561123456')
  })

  it('returns null for empty or digitless input', () => {
    expect(normalizePhone('')).toBeNull()
    expect(normalizePhone('   ')).toBeNull()
    expect(normalizePhone('n/a')).toBeNull()
    expect(normalizePhone(null)).toBeNull()
  })
})

describe('toWaNumber', () => {
  it('strips the plus from an international number', () => {
    expect(toWaNumber('+49 151 22334455', 'DE')).toBe('4915122334455')
  })

  it('strips a 00 prefix', () => {
    expect(toWaNumber('0049 151 22334455', 'DE')).toBe('4915122334455')
  })

  it('expands a national number using the company country', () => {
    expect(toWaNumber('0151 22334455', 'DE')).toBe('4915122334455')
    expect(toWaNumber('06 12345678', 'NL')).toBe('31612345678')
  })

  it('drops the (0) trunk marker', () => {
    expect(toWaNumber('+49 (0)151 22334455', 'DE')).toBe('4915122334455')
  })

  it('refuses to guess a country for a national number', () => {
    expect(toWaNumber('0151 22334455', null)).toBeNull()
    expect(toWaNumber('0151 22334455', 'ZZ')).toBeNull()
  })

  it('returns null for empty input', () => {
    expect(toWaNumber(null, 'DE')).toBeNull()
    expect(toWaNumber('  ', 'DE')).toBeNull()
  })
})

describe('normalizeCustomUrl', () => {
  it('adds https to a bare domain', () => {
    expect(normalizeCustomUrl('vmflow.de')).toBe('https://vmflow.de/')
    expect(normalizeCustomUrl('  shop.example.com/angebote ')).toBe('https://shop.example.com/angebote')
  })

  it('leaves an explicit scheme alone', () => {
    expect(normalizeCustomUrl('http://example.com/x')).toBe('http://example.com/x')
  })

  it('rejects non-web schemes and junk', () => {
    expect(normalizeCustomUrl('javascript:alert(1)')).toBeNull()
    expect(normalizeCustomUrl('mailto:a@b.de')).toBeNull()
    expect(normalizeCustomUrl('')).toBeNull()
    expect(normalizeCustomUrl(null)).toBeNull()
  })
})

describe('readableUrl', () => {
  it('returns web links and nothing else', () => {
    expect(readableUrl('https://example.com/x')).toBe('https://example.com/x')
    // Nobody types a tel: URI off a poster.
    expect(readableUrl('tel:+49561123456')).toBeNull()
    expect(readableUrl(null)).toBeNull()
  })
})

describe('buildPrintSheetBase', () => {
  it('builds the machine page URL from the public origin', () => {
    const sheet = build()
    expect(sheet.pageUrl).toBe('https://app.muster-vending.de/m/m-1')
    expect(sheet.slots.main!.target).toBe('https://app.muster-vending.de/m/m-1')
  })

  it('strips a trailing slash from the origin', () => {
    const sheet = buildPrintSheetBase({
      machine, company,
      publicOrigin: 'https://app.muster-vending.de/',
      slotDeclarations: ONE_SLOT,
      layout: {},
      t,
      fallbackMachineName: 'Automat',
    })
    expect(sheet.pageUrl).toBe('https://app.muster-vending.de/m/m-1')
  })

  it('prefers machine contact data over company contact data', () => {
    const sheet = build({ machine: { contact_phone: '+49 561 999999' } })
    expect(sheet.phone).toBe('+49 561 999999')
  })

  it('inherits company contact data when the machine has none', () => {
    const sheet = build()
    expect(sheet.phone).toBe('+49 561 123456')
    expect(sheet.hours).toBe('Mo–Fr 8–18 Uhr')
  })
})

describe('slot sources', () => {
  it('uses the declared default when nothing is configured', () => {
    const sheet = build({ slots: THREE_SLOTS })
    expect(sheet.slots.tile1!.source).toBe('page')
    expect(sheet.slots.tile2!.source).toBe('whatsapp')
    expect(sheet.slots.tile3!.source).toBe('problem')
  })

  it('points a slot at whatever the operator picked', () => {
    const sheet = build({ layout: { slots: { main: { source: 'problem' } } } })
    expect(sheet.slots.main!.target).toBe('https://app.muster-vending.de/m/m-1?feedback=problem')
  })

  it('resolves a phone slot to a tel: target', () => {
    const sheet = build({ layout: { slots: { main: { source: 'tel' } } } })
    expect(sheet.slots.main!.target).toBe('tel:+49561123456')
  })

  it('prefills the WhatsApp message with the machine name', () => {
    const sheet = build({ layout: { slots: { main: { source: 'whatsapp' } } } })
    expect(sheet.slots.main!.target).toBe(
      'https://wa.me/4915122334455?text=' +
        encodeURIComponent('Hallo, ich habe ein Problem am Automat 12.'),
    )
  })

  it('resolves a custom slot to the operator link', () => {
    const sheet = build({
      layout: { slots: { main: { source: 'custom' } }, custom: { url: 'shop.example.com' } },
    })
    expect(sheet.slots.main!.target).toBe('https://shop.example.com/')
  })

  it('empties a slot set to none', () => {
    const sheet = build({ layout: { slots: { main: { source: 'none' } } } })
    expect(sheet.slots.main!.target).toBeNull()
  })

  // A compact slot is a third-width tile. Handing it the full-width wording
  // wraps the label to three lines and pushes the imprint off the sheet — the
  // exact regression the slot refactor reintroduced once already.
  it('uses the short wording in a compact slot', () => {
    const wide = build({ slots: ONE_SLOT })
    expect(wide.slots.main!.title).toBe('print.poster.pageTitle')
    expect(wide.slots.main!.hint).toBe('print.poster.pageHint')

    const tile = build({
      slots: [{ ...ONE_SLOT[0]!, compact: true }],
    })
    expect(tile.slots.main!.title).toBe('print.poster.pageShort')
    expect(tile.slots.main!.hint).toBe('print.poster.pageHintShort')
  })

  it('keeps compact wording per source', () => {
    const tel = build({
      slots: [{ ...ONE_SLOT[0]!, compact: true }],
      layout: { slots: { main: { source: 'tel' } } },
    })
    expect(tel.slots.main!.title).toBe('print.poster.callShort')

    // Already short: the compact variant is the same string, not a second one.
    const wa = build({
      slots: [{ ...ONE_SLOT[0]!, compact: true }],
      layout: { slots: { main: { source: 'whatsapp' } } },
    })
    expect(wa.slots.main!.title).toBe('print.poster.whatsappTitle')
  })

  it('lets an override beat the compact default too', () => {
    const sheet = build({
      slots: [{ ...ONE_SLOT[0]!, compact: true }],
      layout: { texts: { 'slot.main.title': 'Snacks' } },
    })
    expect(sheet.slots.main!.title).toBe('Snacks')
  })

  it('labels a slot from its source and lets an override win', () => {
    const auto = build({ layout: { slots: { main: { source: 'whatsapp' } } } })
    expect(auto.slots.main!.title).toBe('print.poster.whatsappTitle')

    const named = build({
      layout: {
        slots: { main: { source: 'whatsapp' } },
        texts: { 'slot.main.title': 'Schreib uns' },
      },
    })
    expect(named.slots.main!.title).toBe('Schreib uns')
  })

  it('names a custom slot from the custom link fields', () => {
    const sheet = build({
      layout: {
        slots: { main: { source: 'custom' } },
        custom: { url: 'shop.example.com', title: 'Zum Shop', hint: 'Angebote ansehen' },
      },
    })
    expect(sheet.slots.main!.title).toBe('Zum Shop')
    expect(sheet.slots.main!.hint).toBe('Angebote ansehen')
  })

  it('reports a slot whose data is not configured', () => {
    const noPhone = build({
      company: { contact_phone: null },
      layout: { slots: { main: { source: 'tel' } } },
    })
    expect(noPhone.missing).toContain('phone')
    expect(noPhone.slots.main!.target).toBeNull()

    const noLink = build({ layout: { slots: { main: { source: 'custom' } } } })
    expect(noLink.missing).toContain('customUrl')
  })

  it('does not report a source the layout does not use', () => {
    const sheet = build({ company: { whatsapp_phone: null }, layout: {} })
    expect(sheet.missing).not.toContain('whatsapp')
  })
})

describe('blocks and headlines', () => {
  it('hides imprint fields when the imprint block is off', () => {
    const sheet = build({ layout: { blocks: ['phone'] } })
    expect(sheet.addressLine).toBeNull()
    expect(sheet.email).toBeNull()
    expect(sheet.website).toBeNull()
    // Still shown: the operator's name is who the sign is from.
    expect(sheet.companyName).toBe('Muster Vending GmbH')
  })

  it('exposes imprint fields when the block is on', () => {
    const sheet = build({ layout: { blocks: ['imprint'] } })
    expect(sheet.addressLine).toBe('Musterstr. 4 · 34117 Kassel')
    expect(sheet.email).toBe('service@muster-vending.de')
  })

  it('tracks whether the URL should be printed as text', () => {
    expect(build({ layout: { blocks: ['url'] } }).showUrl).toBe(true)
    expect(build({ layout: { blocks: ['phone'] } }).showUrl).toBe(false)
  })

  it('passes headline overrides through, ignoring blank ones', () => {
    expect(build({ layout: { texts: { title: 'Kaputt?' } } }).texts.title).toBe('Kaputt?')
    expect(build({ layout: { texts: { title: '   ' } } }).texts.title).toBeUndefined()
    expect(build().texts.title).toBeUndefined()
  })
})

describe('machine location', () => {
  it('builds the note from the structured address', () => {
    expect(build().machineNote).toBe('An der Kelter 15 · 74653 Ingelfingen')
  })

  // Nominatim's display_name carries district, county, state and country. It
  // is unreadable on a sign, so the structured columns win whenever they exist.
  it('prefers the structured address over the raw Nominatim display name', () => {
    const sheet = build({
      machine: {
        formatted_address:
          '15, An der Kelter, Criesbach, Ingelfingen, VVG der Stadt Künzelsau, Hohenlohekreis, Baden-Württemberg, 74653, Deutschland',
      },
    })
    expect(sheet.machineNote).toBe('An der Kelter 15 · 74653 Ingelfingen')
  })

  it('trims the display name when no structured address exists', () => {
    const sheet = build({
      machine: {
        formatted_address:
          '15, An der Kelter, Criesbach, Ingelfingen, VVG der Stadt Künzelsau, Hohenlohekreis, Baden-Württemberg, 74653, Deutschland',
        address_street: null, address_house_number: null,
        address_postal_code: null, address_city: null,
      },
    })
    expect(sheet.machineNote).toBe('15, An der Kelter, Criesbach')
  })

  it('leaves a short display name alone', () => {
    const sheet = build({
      machine: {
        formatted_address: '19 Abt-Knittel-Straße 74676 Niedernhall',
        address_street: null, address_house_number: null,
        address_postal_code: null, address_city: null,
      },
    })
    expect(sheet.machineNote).toBe('19 Abt-Knittel-Straße 74676 Niedernhall')
  })

  it('falls back to the generic machine name', () => {
    expect(build({ machine: { name: '   ' } }).machineName).toBe('Automat')
  })
})

describe('posterFingerprint', () => {
  it('is stable for unchanged data', () => {
    expect(posterFingerprint(build())).toBe(posterFingerprint(build()))
  })

  it('changes when the support number changes', () => {
    expect(posterFingerprint(build({ company: { contact_phone: '+49 561 999999' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when a machine override starts shadowing the company number', () => {
    expect(posterFingerprint(build({ machine: { contact_phone: '+49 561 999999' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when the availability text changes', () => {
    expect(posterFingerprint(build({ company: { support_hours: 'Mo–Sa 7–20 Uhr' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when the machine is renamed', () => {
    expect(posterFingerprint(build({ machine: { name: 'Automat 13' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when a QR is repointed', () => {
    expect(posterFingerprint(build({ layout: { slots: { main: { source: 'problem' } } } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when the custom link changes', () => {
    const a = build({ layout: { slots: { main: { source: 'custom' } }, custom: { url: 'a.example.com' } } })
    const b = build({ layout: { slots: { main: { source: 'custom' } }, custom: { url: 'b.example.com' } } })
    expect(posterFingerprint(a)).not.toBe(posterFingerprint(b))
  })

  // The whole point of the like-against-like comparison: reprinting the same
  // sign in another language must not read as "the contact data changed".
  it('ignores the language of the prefilled WhatsApp message', () => {
    const layout: PosterLayout = { slots: { main: { source: 'whatsapp' } }, blocks: ALL_BLOCKS }
    const de = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de',
      slotDeclarations: ONE_SLOT, layout, t,
      whatsappTemplate: 'Hallo, ich habe ein Problem am %machine%.',
      fallbackMachineName: 'Automat',
    })
    const fr = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de',
      slotDeclarations: ONE_SLOT, layout, t,
      whatsappTemplate: "Bonjour, j'ai un problème avec %machine%.",
      fallbackMachineName: 'Automat',
    })
    expect(posterFingerprint(de)).toBe(posterFingerprint(fr))
  })

  it('ignores reworded headlines and labels', () => {
    const reworded = build({
      layout: { blocks: ALL_BLOCKS, texts: { title: 'Kaputt?', 'slot.main.title': 'Hier lang' } },
    })
    expect(posterFingerprint(reworded)).toBe(posterFingerprint(build()))
  })

  // Free text is per-print and never persisted, so it cannot be part of the
  // "is the sign still correct" question.
  it('ignores the one-off free text', () => {
    const withText = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de',
      slotDeclarations: ONE_SLOT, layout: { blocks: ALL_BLOCKS }, t,
      whatsappTemplate: 'Hallo, ich habe ein Problem am %machine%.',
      customText: 'Standort: 2. OG', fallbackMachineName: 'Automat',
    })
    expect(posterFingerprint(withText)).toBe(posterFingerprint(build()))
  })

  it('ignores a field belonging to a block that was switched off', () => {
    const a = build({ layout: { blocks: [] } })
    const b = build({ layout: { blocks: [] }, company: { contact_phone: '+49 561 999999' } })
    expect(posterFingerprint(a)).toBe(posterFingerprint(b))
  })
})

describe('distributeStickers', () => {
  it('packs continuously across sheets', () => {
    const items = Array.from({ length: 11 }, (_, i) => i)
    const sheets = distributeStickers(items)
    expect(sheets).toHaveLength(2)
    expect(sheets[0]).toHaveLength(8)
    expect(sheets[1]).toEqual([8, 9, 10])
  })

  it('wastes no sheet on a short run', () => {
    expect(distributeStickers([1, 2, 3])).toEqual([[1, 2, 3]])
  })

  it('returns nothing for an empty run', () => {
    expect(distributeStickers([])).toEqual([])
  })
})

describe('qrErrorLevel', () => {
  // More error correction means more modules on the same area. Below roughly
  // 0.5 mm module size a code is unreadable no matter how much redundancy it
  // carries — small formats therefore need less, not more.
  it('drops redundancy where the code is small', () => {
    expect(qrErrorLevel('sticker-sheet')).toBe('L')
    expect(qrErrorLevel('sticker-sheet-small')).toBe('L')
    expect(qrErrorLevel('sticker-sheet-strip')).toBe('L')
  })

  it('keeps the print standard where there is room', () => {
    expect(qrErrorLevel('a4')).toBe('M')
    expect(qrErrorLevel('a5')).toBe('M')
    expect(qrErrorLevel('a6')).toBe('M')
  })
})

describe('sticker sheet geometry', () => {
  it('knows how many labels a sheet holds per format', () => {
    expect(stickersPerSheet('sticker-sheet')).toBe(8)
    expect(stickersPerSheet('sticker-sheet-small')).toBe(24)
    // Two 148 mm strips do not fit side by side on a 210 mm page.
    expect(stickersPerSheet('sticker-sheet-strip')).toBe(6)
  })

  it('keeps both grids inside an A4 page', () => {
    for (const format of ['sticker-sheet', 'sticker-sheet-small', 'sticker-sheet-strip'] as const) {
      const { w, h, gap, cols, rows } = stickerLayout(format)
      expect(cols * w + (cols - 1) * gap).toBeLessThanOrEqual(210)
      expect(rows * h + (rows - 1) * gap).toBeLessThanOrEqual(297)
    }
  })

  it('packs the small format 24 to a sheet', () => {
    const items = Array.from({ length: 25 }, (_, i) => i)
    const sheets = distributeStickers(items, stickersPerSheet('sticker-sheet-small'))
    expect(sheets).toHaveLength(2)
    expect(sheets[0]).toHaveLength(24)
    expect(sheets[1]).toEqual([24])
  })

  it('only reports sticker formats as stickers', () => {
    expect(isStickerFormat('sticker-sheet')).toBe(true)
    expect(isStickerFormat('sticker-sheet-small')).toBe(true)
    expect(isStickerFormat('sticker-sheet-strip')).toBe(true)
    expect(isStickerFormat('a4')).toBe(false)
  })
})
