import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import TiledSheet from '../print/TiledSheet.vue'
import type { PrintFormat, PrintSheet } from '@/lib/printSheet'

/** Motifs are irrelevant to geometry — this one just marks its slot. */
const Stub = defineComponent({ props: ['sheet', 't'], setup: () => () => h('div', 'x') })

function sheets(n: number): PrintSheet[] {
  return Array.from({ length: n }, (_, i) => ({ machineId: `m${i}` }) as PrintSheet)
}

function mountSheet(format: PrintFormat, n: number) {
  return mount(TiledSheet, {
    props: { sheets: sheets(n), motif: Stub, t: (k: string) => k, format },
  })
}

/** Inline styles, read back as the browser stores them. */
const styleOf = (w: ReturnType<typeof mountSheet>, sel: string, i = 0) =>
  (w.findAll(sel)[i]!.element as HTMLElement).style

describe('TiledSheet geometry', () => {
  it('lays four A6 tiles on a centred 2 x 2 grid', () => {
    const w = mountSheet('a6-4up', 4)
    const cells = w.findAll('.cell')
    expect(cells).toHaveLength(4)
    // Block is 198 x 278 mm on A4, so 6 mm left and 9.5 mm top.
    expect(styleOf(w, '.cell', 0).left).toBe('6mm')
    expect(styleOf(w, '.cell', 0).top).toBe('9.5mm')
    expect(styleOf(w, '.cell', 1).left).toBe('107mm')
    expect(styleOf(w, '.cell', 2).top).toBe('150.5mm')
    expect(styleOf(w, '.cell', 0).width).toBe('97mm')
    expect(styleOf(w, '.cell', 0).height).toBe('137mm')
  })

  it('never renders more tiles than the grid holds', () => {
    expect(mountSheet('a6-4up', 9).findAll('.cell')).toHaveLength(4)
    expect(mountSheet('a7-8up', 20).findAll('.cell')).toHaveLength(8)
  })

  it('lays a rotated tile on its side and pushes it back into its cell', () => {
    const w = mountSheet('a7-8up', 1)
    // The cell is the rotated footprint: 96 wide, 68 tall.
    expect(styleOf(w, '.cell', 0).width).toBe('96mm')
    expect(styleOf(w, '.cell', 0).height).toBe('68mm')
    // The tile itself stays portrait and is rotated into place.
    const tile = styleOf(w, '.tile', 0)
    expect(tile.width).toBe('68mm')
    expect(tile.height).toBe('96mm')
    expect(tile.transform).toBe('translateX(96mm) rotate(90deg)')
    expect(tile.transformOrigin).toBe('top left')
  })

  it('leaves an unrotated tile untransformed', () => {
    expect(styleOf(mountSheet('a6-4up', 1), '.tile', 0).transform).toBe('')
  })

  it('rescales the em base for poster tiles only', () => {
    // A poster motif sizes everything in em against the sheet width; in a
    // 97 mm tile that base has to shrink or A4 text runs off an A6 card.
    // Compared with toBeCloseTo rather than toBe: happy-dom (like real
    // browsers, which store CSS lengths as single-precision floats)
    // re-serializes the inline style with fewer fractional digits than the
    // double-precision JS literal, so an exact string match can never
    // round-trip through the DOM style object.
    expect(Number.parseFloat(styleOf(mountSheet('a6-4up', 1), '.tile', 0).fontSize))
      .toBeCloseTo((4 * 97) / 210, 5)
    // Sticker motifs are tuned to the page base and must keep it.
    expect(styleOf(mountSheet('sticker-sheet', 1), '.tile', 0).fontSize).toBe('')
  })

  it('cuts once per gutter, plus the block edges', () => {
    const w = mountSheet('a6-4up', 4)
    // Two columns: left edge, gutter centre, right edge.
    expect(w.findAll('.cut-v').map((c) => (c.element as HTMLElement).style.left))
      .toEqual(['6mm', '105mm', '204mm'])
    expect(w.findAll('.cut-h').map((c) => (c.element as HTMLElement).style.top))
      .toEqual(['9.5mm', '148.5mm', '287.5mm'])
  })

  it('cuts a rotated grid on its own gutters', () => {
    const w = mountSheet('a7-8up', 8)
    expect(w.findAll('.cut-v').map((c) => (c.element as HTMLElement).style.left))
      .toEqual(['7mm', '105mm', '203mm'])
    expect(w.findAll('.cut-h').map((c) => (c.element as HTMLElement).style.top))
      .toEqual(['6.5mm', '76.5mm', '148.5mm', '220.5mm', '290.5mm'])
  })

  it('passes the format QR floor down to the tile', () => {
    const style = styleOf(mountSheet('a7-8up', 1), '.tile', 0)
    expect(style.getPropertyValue('--qr-min')).toBe('18mm')
    expect(style.getPropertyValue('--pad-min')).toBe('3mm')
  })

  it('keeps the three sticker grids where they were', () => {
    // 90 x 50 with a 3 mm gutter: 183 x 209 mm, centred on A4.
    const w = mountSheet('sticker-sheet', 8)
    expect(w.findAll('.cell')).toHaveLength(8)
    expect(styleOf(w, '.cell', 0).left).toBe('13.5mm')
    expect(styleOf(w, '.cell', 0).top).toBe('44mm')
    expect(styleOf(w, '.cell', 1).left).toBe('106.5mm')
    expect(styleOf(w, '.cell', 2).top).toBe('97mm')
    expect(mountSheet('sticker-sheet-small', 24).findAll('.cell')).toHaveLength(24)
    expect(mountSheet('sticker-sheet-strip', 6).findAll('.cell')).toHaveLength(6)
  })
})
