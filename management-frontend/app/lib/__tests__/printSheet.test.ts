import { describe, it, expect } from 'vitest'
import {
  buildPrintSheetBase,
  distributeStickers,
  inherit,
  isPublicOrigin,
  normalizePhone,
  posterFingerprint,
  qrErrorLevel,
  toWaNumber,
} from '../printSheet'
import type { PosterCompany, PosterMachine, PrintBlock } from '../printSheet'

const ALL_BLOCKS: PrintBlock[] = ['phone', 'whatsapp', 'problem', 'imprint']

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
  formatted_address: 'Foyer Nord, Musterstr. 4, 34117 Kassel',
}

function build(
  over: { machine?: Partial<PosterMachine>; company?: Partial<PosterCompany>; blocks?: PrintBlock[] } = {},
) {
  return buildPrintSheetBase({
    machine: { ...machine, ...over.machine },
    company: { ...company, ...over.company },
    publicOrigin: 'https://app.muster-vending.de',
    blocks: over.blocks ?? ALL_BLOCKS,
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

describe('buildPrintSheetBase', () => {
  it('builds the machine page URL from the public origin', () => {
    const sheet = build()
    expect(sheet.pageUrl).toBe('https://app.muster-vending.de/m/m-1')
    expect(sheet.targets.page).toBe('https://app.muster-vending.de/m/m-1')
  })

  it('strips a trailing slash from the origin', () => {
    const sheet = buildPrintSheetBase({
      machine,
      company,
      publicOrigin: 'https://app.muster-vending.de/',
      blocks: ALL_BLOCKS,
      fallbackMachineName: 'Automat',
    })
    expect(sheet.pageUrl).toBe('https://app.muster-vending.de/m/m-1')
  })

  it('prefers machine contact data over company contact data', () => {
    const sheet = build({ machine: { contact_phone: '+49 561 999999' } })
    expect(sheet.phone).toBe('+49 561 999999')
    expect(sheet.targets.tel).toBe('tel:+49561999999')
  })

  it('inherits company contact data when the machine has none', () => {
    const sheet = build()
    expect(sheet.phone).toBe('+49 561 123456')
    expect(sheet.hours).toBe('Mo–Fr 8–18 Uhr')
  })

  it('prefills the WhatsApp message with the machine name', () => {
    const sheet = build()
    expect(sheet.targets.whatsapp).toBe(
      'https://wa.me/4915122334455?text=' +
        encodeURIComponent('Hallo, ich habe ein Problem am Automat 12.'),
    )
  })

  it('points the problem QR at the feedback form', () => {
    expect(build().targets.problem).toBe(
      'https://app.muster-vending.de/m/m-1?feedback=problem',
    )
  })

  it('nulls the targets of disabled blocks', () => {
    const sheet = build({ blocks: ['imprint'] })
    expect(sheet.targets.tel).toBeNull()
    expect(sheet.targets.whatsapp).toBeNull()
    expect(sheet.targets.problem).toBeNull()
    expect(sheet.phone).toBeNull()
    // The page QR is the one constant — it is the reason the poster exists.
    expect(sheet.targets.page).toBeTruthy()
  })

  it('does not report a missing field for a block that is switched off', () => {
    const sheet = build({ blocks: ['problem'], company: { contact_phone: null, whatsapp_phone: null } })
    expect(sheet.missing).toEqual([])
  })

  it('reports a requested phone that is not configured', () => {
    const sheet = build({ blocks: ['phone'], company: { contact_phone: null } })
    expect(sheet.missing).toContain('phone')
    expect(sheet.targets.tel).toBeNull()
  })

  it('reports a WhatsApp number that cannot be made international', () => {
    const sheet = build({ blocks: ['whatsapp'], company: { country_code: null } })
    expect(sheet.missing).toContain('whatsappCountry')
    expect(sheet.targets.whatsapp).toBeNull()
    expect(sheet.whatsapp).toBeNull()
  })

  it('reports missing imprint data', () => {
    const sheet = build({
      blocks: ['imprint'],
      company: { contact_email: null, address_street: null, address_house_number: null, address_postal_code: null, address_city: null },
    })
    expect(sheet.missing).toContain('email')
    expect(sheet.missing).toContain('address')
  })

  it('falls back to the generic machine name', () => {
    expect(build({ machine: { name: '   ' } }).machineName).toBe('Automat')
  })

  it('uses the legal name and formats the company address', () => {
    const sheet = build()
    expect(sheet.companyName).toBe('Muster Vending GmbH')
    expect(sheet.addressLine).toBe('Musterstr. 4 · 34117 Kassel')
  })

  it('builds a machine note from address parts when there is no cached address', () => {
    const sheet = build({
      machine: {
        formatted_address: null,
        address_street: 'Bahnhofstr.',
        address_house_number: '1',
        address_postal_code: '34117',
        address_city: 'Kassel',
      },
    })
    expect(sheet.machineNote).toBe('Bahnhofstr. 1 · 34117 Kassel')
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

  it('changes when the WhatsApp number changes', () => {
    expect(posterFingerprint(build({ company: { whatsapp_phone: '+49 151 99887766' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when the machine is renamed', () => {
    expect(posterFingerprint(build({ machine: { name: 'Automat 13' } })))
      .not.toBe(posterFingerprint(build()))
  })

  it('changes when a block is switched on', () => {
    expect(posterFingerprint(build({ blocks: ['phone'] })))
      .not.toBe(posterFingerprint(build({ blocks: ['phone', 'whatsapp'] })))
  })

  // The whole point of the like-against-like comparison: reprinting the same
  // sign in another language must not read as "the contact data changed".
  it('ignores the language of the prefilled WhatsApp message', () => {
    const de = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de', blocks: ALL_BLOCKS,
      whatsappTemplate: 'Hallo, ich habe ein Problem am %machine%.',
      fallbackMachineName: 'Automat',
    })
    const fr = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de', blocks: ALL_BLOCKS,
      whatsappTemplate: "Bonjour, j'ai un problème avec %machine%.",
      fallbackMachineName: 'Automat',
    })
    expect(posterFingerprint(de)).toBe(posterFingerprint(fr))
  })

  // Free text is per-print and never persisted, so it cannot be part of the
  // "is the sign still correct" question.
  it('ignores the one-off free text', () => {
    const withText = buildPrintSheetBase({
      machine, company, publicOrigin: 'https://app.muster-vending.de', blocks: ALL_BLOCKS,
      customText: 'Standort: 2. OG', fallbackMachineName: 'Automat',
    })
    expect(posterFingerprint(withText)).toBe(posterFingerprint(build()))
  })

  it('ignores a field belonging to a block that was switched off', () => {
    const a = build({ blocks: ['problem'] })
    const b = build({ blocks: ['problem'], company: { contact_phone: '+49 561 999999' } })
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
  it('raises redundancy for stickers', () => {
    expect(qrErrorLevel('sticker-sheet')).toBe('Q')
    expect(qrErrorLevel('a4')).toBe('M')
  })
})
