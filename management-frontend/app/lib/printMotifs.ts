import type { Component } from 'vue'
import PosterKlar from '@/components/print/PosterKlar.vue'
import PosterKachel from '@/components/print/PosterKachel.vue'
import PosterQrFirst from '@/components/print/PosterQrFirst.vue'
import PosterDunkel from '@/components/print/PosterDunkel.vue'
import PosterSignal from '@/components/print/PosterSignal.vue'
import PosterDuo from '@/components/print/PosterDuo.vue'
import PosterKlassisch from '@/components/print/PosterKlassisch.vue'
import StickerProblem from '@/components/print/StickerProblem.vue'
import StickerMenu from '@/components/print/StickerMenu.vue'
import StickerDuo from '@/components/print/StickerDuo.vue'
import StickerContact from '@/components/print/StickerContact.vue'
import StickerMini from '@/components/print/StickerMini.vue'
import type { PosterLayout, PrintBlock, PrintFormat, SlotDeclaration } from '@/lib/printSheet'

export type { PosterT } from '@/lib/printSheet'

export type MotifId =
  | 'klar'
  | 'kachel'
  | 'qr-first'
  | 'duo'
  | 'signal'
  | 'klassisch'
  | 'dunkel'
  | 'sticker-problem'
  | 'sticker-menu'
  | 'sticker-duo'
  | 'sticker-contact'
  | 'sticker-mini'

/** An editable headline field. Per-slot labels are declared by the slots. */
export interface MotifTextField {
  /** Key inside `PosterLayout.texts`. */
  id: 'title' | 'lead'
  labelKey: string
  /** Translation key of the value shown when nothing is overridden. */
  defaultKey: string
}

export interface PrintMotif {
  id: MotifId
  labelKey: string
  descriptionKey: string
  component: Component
  /** Formats this motif is laid out for. */
  formats: PrintFormat[]
  /** QR slots, in render order. Each one's source is operator-chosen. */
  slots: SlotDeclaration[]
  /** Editable headline fields. */
  texts: MotifTextField[]
  /** Non-QR content this motif can display. */
  blocks: PrintBlock[]
}

/**
 * Adding a motif is one entry here plus one `.vue` file in
 * `components/print/` — nothing else in the app needs to change.
 */
export const PRINT_MOTIFS: PrintMotif[] = [
  {
    id: 'klar',
    labelKey: 'print.motifs.klar.label',
    descriptionKey: 'print.motifs.klar.description',
    component: PosterKlar,
    formats: ['a4', 'a5', 'a6'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.problemTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.problemLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'kachel',
    labelKey: 'print.motifs.kachel.label',
    descriptionKey: 'print.motifs.kachel.description',
    component: PosterKachel,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'tile1', labelKey: 'print.slots.tile1', compact: true, defaultSource: 'page', optional: false },
      { id: 'tile2', labelKey: 'print.slots.tile2', compact: true, defaultSource: 'whatsapp', optional: true },
      { id: 'tile3', labelKey: 'print.slots.tile3', compact: true, defaultSource: 'problem', optional: true },
      { id: 'call', labelKey: 'print.slots.call', defaultSource: 'tel', optional: true },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.helpTitle' },
    ],
    blocks: ['phone', 'imprint'],
  },
  {
    id: 'qr-first',
    labelKey: 'print.motifs.qrFirst.label',
    descriptionKey: 'print.motifs.qrFirst.description',
    component: PosterQrFirst,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.everythingTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.everythingLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'duo',
    labelKey: 'print.motifs.duo.label',
    descriptionKey: 'print.motifs.duo.description',
    component: PosterDuo,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'main', labelKey: 'print.slots.top', defaultSource: 'page', optional: false },
      { id: 'issue', labelKey: 'print.slots.bottom', compact: true, defaultSource: 'problem', optional: true },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.discoverTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.discoverLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'signal',
    labelKey: 'print.motifs.signal.label',
    descriptionKey: 'print.motifs.signal.description',
    component: PosterSignal,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.helpYouTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.helpYouLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'klassisch',
    labelKey: 'print.motifs.klassisch.label',
    descriptionKey: 'print.motifs.klassisch.description',
    component: PosterKlassisch,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.problemTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.problemLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'dunkel',
    labelKey: 'print.motifs.dunkel.label',
    descriptionKey: 'print.motifs.dunkel.description',
    component: PosterDunkel,
    formats: ['a4', 'a5'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.poster.helpYouTitle' },
      { id: 'lead', labelKey: 'print.texts.lead', defaultKey: 'print.poster.helpYouLead' },
    ],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'sticker-problem',
    labelKey: 'print.motifs.stickerProblem.label',
    descriptionKey: 'print.motifs.stickerProblem.description',
    component: StickerProblem,
    formats: ['sticker-sheet'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', compact: true, defaultSource: 'problem', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.sticker.problemTitle' },
    ],
    blocks: ['phone'],
  },
  {
    id: 'sticker-menu',
    labelKey: 'print.motifs.stickerMenu.label',
    descriptionKey: 'print.motifs.stickerMenu.description',
    component: StickerMenu,
    formats: ['sticker-sheet'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', compact: true, defaultSource: 'page', optional: false },
    ],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.sticker.menuTitle' },
    ],
    blocks: [],
  },
  {
    id: 'sticker-duo',
    labelKey: 'print.motifs.stickerDuo.label',
    descriptionKey: 'print.motifs.stickerDuo.description',
    component: StickerDuo,
    formats: ['sticker-sheet'],
    slots: [
      { id: 'main', labelKey: 'print.slots.left', compact: true, defaultSource: 'page', optional: false },
      { id: 'issue', labelKey: 'print.slots.right', compact: true, defaultSource: 'problem', optional: true },
    ],
    texts: [],
    blocks: ['phone'],
  },
  {
    id: 'sticker-contact',
    labelKey: 'print.motifs.stickerContact.label',
    descriptionKey: 'print.motifs.stickerContact.description',
    component: StickerContact,
    formats: ['sticker-sheet'],
    slots: [],
    texts: [
      { id: 'title', labelKey: 'print.texts.title', defaultKey: 'print.sticker.contactTitle' },
    ],
    blocks: ['phone', 'imprint'],
  },
  {
    id: 'sticker-mini',
    labelKey: 'print.motifs.stickerMini.label',
    descriptionKey: 'print.motifs.stickerMini.description',
    component: StickerMini,
    formats: ['sticker-sheet-small'],
    slots: [
      { id: 'main', labelKey: 'print.slots.main', compact: true, defaultSource: 'page', optional: false },
    ],
    texts: [],
    blocks: ['phone'],
  },
]

export function motifById(id: string): PrintMotif | undefined {
  return PRINT_MOTIFS.find(m => m.id === id)
}

export { isStickerFormat } from '@/lib/printSheet'

/** The configuration a motif starts from before anything is saved or edited. */
export function defaultLayout(motif: PrintMotif): PosterLayout {
  return {
    slots: Object.fromEntries(motif.slots.map(s => [s.id, { source: s.defaultSource }])),
    custom: { url: '', title: '', hint: '' },
    texts: {},
    blocks: [...motif.blocks],
  }
}
