import type { Component } from 'vue'
import PosterKlar from '@/components/print/PosterKlar.vue'
import PosterKachel from '@/components/print/PosterKachel.vue'
import PosterQrFirst from '@/components/print/PosterQrFirst.vue'
import PosterDunkel from '@/components/print/PosterDunkel.vue'
import StickerProblem from '@/components/print/StickerProblem.vue'
import StickerMenu from '@/components/print/StickerMenu.vue'
import type { PrintBlock, PrintFormat } from '@/lib/printSheet'

/**
 * Translation function bound to the *poster's* language, which the operator
 * picks independently of the UI language — an English admin still prints a
 * German sign for a machine in Kassel.
 */
export type PosterT = (key: string, named?: Record<string, unknown>) => string

export type MotifId =
  | 'klar'
  | 'kachel'
  | 'qr-first'
  | 'dunkel'
  | 'sticker-problem'
  | 'sticker-menu'

export interface PrintMotif {
  id: MotifId
  labelKey: string
  descriptionKey: string
  component: Component
  /** Formats this motif is laid out for. */
  formats: PrintFormat[]
  /** Optional blocks this motif can actually display. */
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
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'kachel',
    labelKey: 'print.motifs.kachel.label',
    descriptionKey: 'print.motifs.kachel.description',
    component: PosterKachel,
    formats: ['a4', 'a5'],
    blocks: ['phone', 'whatsapp', 'problem', 'imprint'],
  },
  {
    id: 'qr-first',
    labelKey: 'print.motifs.qrFirst.label',
    descriptionKey: 'print.motifs.qrFirst.description',
    component: PosterQrFirst,
    formats: ['a4', 'a5'],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'dunkel',
    labelKey: 'print.motifs.dunkel.label',
    descriptionKey: 'print.motifs.dunkel.description',
    component: PosterDunkel,
    formats: ['a4', 'a5'],
    blocks: ['phone', 'imprint', 'url'],
  },
  {
    id: 'sticker-problem',
    labelKey: 'print.motifs.stickerProblem.label',
    descriptionKey: 'print.motifs.stickerProblem.description',
    component: StickerProblem,
    formats: ['sticker-sheet'],
    blocks: ['phone', 'problem'],
  },
  {
    id: 'sticker-menu',
    labelKey: 'print.motifs.stickerMenu.label',
    descriptionKey: 'print.motifs.stickerMenu.description',
    component: StickerMenu,
    formats: ['sticker-sheet'],
    blocks: [],
  },
]

export function motifById(id: string): PrintMotif | undefined {
  return PRINT_MOTIFS.find(m => m.id === id)
}

export function isStickerFormat(format: PrintFormat): boolean {
  return format === 'sticker-sheet'
}
