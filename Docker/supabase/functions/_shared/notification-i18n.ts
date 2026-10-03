/**
 * Per-locale notification strings + price formatting for push pushes.
 *
 * Single source of truth for the ~8 strings we send to user devices.
 * Emoji prefixes (🛒 💵 🟡 ⚠️ 🚨) stay universal and are concatenated
 * by the callers; this module only supplies translated words and
 * locale-aware currency formatting.
 */

export type Locale = 'en' | 'de' | 'fr' | 'nl'

/**
 * Clamp any input (user-supplied locale, Accept-Language header,
 * iOS `Locale.current` code) to our supported set. Unknown → 'en'.
 */
export function normalizeLocale(raw: string | null | undefined): Locale {
  if (!raw) return 'en'
  const prefix = raw.toLowerCase().split(/[-_]/)[0] ?? ''
  if (prefix === 'de') return 'de'
  if (prefix === 'fr') return 'fr'
  if (prefix === 'nl') return 'nl'
  return 'en'
}

export interface TranslationSet {
  sale: string
  left: string
  refillAt: (threshold: number) => string
  inMachine: string
  slot: string
  noStockInfo: string
  lowStockTitle: string
  remaining: string
  testMachine: string
  sampleProduct: string
  newDealsTitle: string
  newDealsBody: (count: number, retailers: string) => string
}

const en: TranslationSet = {
  sale: 'Sale',
  left: 'left',
  inMachine: 'in machine',
  slot: 'Slot',
  refillAt: (n) => `refill at ${n}`,
  noStockInfo: 'No stock info',
  lowStockTitle: 'Low Stock Alert',
  remaining: 'remaining',
  testMachine: 'Test Machine',
  sampleProduct: 'Sample Product',
  newDealsTitle: 'New deals',
  newDealsBody: (n, r) =>
    r
      ? `${n} new ${n === 1 ? 'deal' : 'deals'} — ${r}`
      : `${n} new ${n === 1 ? 'deal' : 'deals'}`,
}

const de: TranslationSet = {
  sale: 'Verkauf',
  left: 'übrig',
  inMachine: 'im Automaten',
  slot: 'Fach',
  refillAt: (n) => `nachfüllen bei ${n}`,
  noStockInfo: 'Kein Bestand',
  lowStockTitle: 'Bestandswarnung',
  remaining: 'übrig',
  testMachine: 'Testmaschine',
  sampleProduct: 'Beispielprodukt',
  newDealsTitle: 'Neue Angebote',
  newDealsBody: (n, r) =>
    r
      ? `${n} ${n === 1 ? 'neues Angebot' : 'neue Angebote'} — ${r}`
      : `${n} ${n === 1 ? 'neues Angebot' : 'neue Angebote'}`,
}

const fr: TranslationSet = {
  sale: 'Vente',
  left: 'restant',
  inMachine: 'dans la machine',
  slot: 'Emplacement',
  refillAt: (n) => `réapprovisionner à ${n}`,
  noStockInfo: 'Aucune info de stock',
  lowStockTitle: 'Alerte stock bas',
  remaining: 'restant',
  testMachine: 'Machine de test',
  sampleProduct: 'Produit exemple',
  newDealsTitle: 'Nouvelles offres',
  newDealsBody: (n, r) =>
    r
      ? `${n} ${n === 1 ? 'nouvelle offre' : 'nouvelles offres'} — ${r}`
      : `${n} ${n === 1 ? 'nouvelle offre' : 'nouvelles offres'}`,
}

const nl: TranslationSet = {
  sale: 'Verkoop',
  left: 'over',
  inMachine: 'in automaat',
  slot: 'Vak',
  refillAt: (n) => `bijvullen bij ${n}`,
  noStockInfo: 'Geen voorraadinfo',
  lowStockTitle: 'Lage voorraad melding',
  remaining: 'over',
  testMachine: 'Testmachine',
  sampleProduct: 'Voorbeeldproduct',
  newDealsTitle: 'Nieuwe aanbiedingen',
  newDealsBody: (n, r) =>
    r
      ? `${n} nieuwe ${n === 1 ? 'aanbieding' : 'aanbiedingen'} — ${r}`
      : `${n} nieuwe ${n === 1 ? 'aanbieding' : 'aanbiedingen'}`,
}

export function t(locale: Locale): TranslationSet {
  if (locale === 'de') return de
  if (locale === 'fr') return fr
  if (locale === 'nl') return nl
  return en
}

/**
 * Locale-aware EUR currency formatting.
 *   en → '€2.50'   (en-GB style, symbol-first)
 *   de → '2,50 €'  (de-DE style, symbol-last with NBSP separator)
 *   fr → '2,50 €'  (fr-FR style, symbol-last with NBSP separator)
 *   nl → '€ 2,50'  (nl-BE style, symbol-first with NBSP separator)
 *
 * Callers embed the returned string directly in the notification body.
 */
export function formatPrice(amount: number, locale: Locale): string {
  const bcp47 = locale === 'de' ? 'de-DE' : locale === 'fr' ? 'fr-FR' : locale === 'nl' ? 'nl-BE' : 'en-GB'
  return new Intl.NumberFormat(bcp47, {
    style: 'currency',
    currency: 'EUR',
  }).format(amount)
}
