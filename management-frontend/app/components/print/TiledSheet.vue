<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import type { PrintFormat, PrintSheet } from '@/lib/printSheet'
import { sheetCssVars, tileBlockMm, tileLayout } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{
  sheets: PrintSheet[]
  motif: Component
  t: PosterT
  format: PrintFormat
}>()

const PAGE = { w: 210, h: 297 }

const grid = computed(() => {
  const l = tileLayout(props.format)
  const block = tileBlockMm(props.format)
  // What a tile occupies on the sheet: rotated, it lies on its side.
  const cellW = l.rotate ? l.h : l.w
  const cellH = l.rotate ? l.w : l.h
  return {
    ...l,
    cellW,
    cellH,
    blockW: block.w,
    blockH: block.h,
    offX: (PAGE.w - block.w) / 2,
    offY: (PAGE.h - block.h) / 2,
  }
})

const tiles = computed(() => {
  const g = grid.value
  return props.sheets.slice(0, g.cols * g.rows).map((sheet, i) => ({
    sheet,
    key: `${sheet.machineId}-${i}`,
    left: g.offX + (i % g.cols) * (g.cellW + g.gap),
    top: g.offY + Math.floor(i / g.cols) * (g.cellH + g.gap),
  }))
})

/**
 * One line per cut, not one per tile edge: centred in the gutter, a single
 * ruler stroke separates two tiles, and each card keeps half the gutter as
 * its white margin. The block's outer edges get their own line.
 */
function cuts(count: number, off: number, cell: number, gap: number, block: number): number[] {
  const out = [off]
  for (let i = 1; i < count; i++) out.push(off + i * (cell + gap) - gap / 2)
  out.push(off + block)
  return out
}

const xCuts = computed(() => {
  const g = grid.value
  return cuts(g.cols, g.offX, g.cellW, g.gap, g.blockW)
})

const yCuts = computed(() => {
  const g = grid.value
  return cuts(g.rows, g.offY, g.cellH, g.gap, g.blockH)
})

const tileStyle = computed(() => {
  const g = grid.value
  const style: Record<string, string> = {
    width: `${g.w}mm`,
    height: `${g.h}mm`,
    ...sheetCssVars(props.format),
  }
  // Poster motifs scale everything in em against the sheet width; inside a
  // tile the base has to come from the tile itself, or A4-sized text runs
  // off an A6 card. Sticker motifs are trimmed to the sheet base.
  if (g.scaleToTile) style.fontSize = `${(4 * g.w) / 210}mm`
  // Rotated around its top-left corner, the tile hangs to the left of its
  // cell; translateX shoves it back in by its own height.
  if (g.rotate) {
    style.transform = `translateX(${g.h}mm) rotate(90deg)`
    style.transformOrigin = 'top left'
  }
  return style
})
</script>

<template>
  <div class="tiled-page">
    <div
      v-for="tile in tiles"
      :key="tile.key"
      class="cell"
      :style="{
        left: `${tile.left}mm`,
        top: `${tile.top}mm`,
        width: `${grid.cellW}mm`,
        height: `${grid.cellH}mm`,
      }"
    >
      <div class="tile" :style="tileStyle">
        <component :is="motif" :sheet="tile.sheet" :t="t" />
      </div>
    </div>

    <!-- After the tiles, so the lines at the block edges don't half
         disappear under a tile. -->
    <div v-for="x in xCuts" :key="`cx-${x}`" class="cut cut-v" :style="{ left: `calc(${x}mm - 0.125mm)` }" />
    <div v-for="y in yCuts" :key="`cy-${y}`" class="cut cut-h" :style="{ top: `calc(${y}mm - 0.125mm)` }" />
  </div>
</template>

<style scoped>
.tiled-page {
  position: relative;
  width: 100%;
  height: 100%;
  background: #fff;
}
.cell { position: absolute; overflow: hidden; }
.tile { position: absolute; left: 0; top: 0; box-sizing: border-box; overflow: hidden; }

/* Gradient instead of `border: dashed`: at 0.25mm stroke width every browser
   rounds the dash pattern differently, the gradient prints identically
   everywhere. */
.cut { position: absolute; }
.cut-v {
  top: 0;
  height: 100%;
  width: 0.25mm;
  background: repeating-linear-gradient(to bottom, #b0aca8 0 2mm, transparent 2mm 4mm);
}
.cut-h {
  left: 0;
  width: 100%;
  height: 0.25mm;
  background: repeating-linear-gradient(to right, #b0aca8 0 2mm, transparent 2mm 4mm);
}
</style>
