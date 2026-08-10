<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import type { PrintSheet } from '@/lib/printSheet'
import { STICKER_MM } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{
  sheets: PrintSheet[]
  motif: Component
  t: PosterT
}>()

const COLS = 2
const ROWS = 4
const PAGE = { w: 210, h: 297 }

const grid = computed(() => {
  const { w, h, gap } = STICKER_MM
  const totalW = COLS * w + (COLS - 1) * gap
  const totalH = ROWS * h + (ROWS - 1) * gap
  const offX = (PAGE.w - totalW) / 2
  const offY = (PAGE.h - totalH) / 2
  return { w, h, gap, totalW, totalH, offX, offY }
})

const slots = computed(() =>
  props.sheets.slice(0, COLS * ROWS).map((sheet, i) => {
    const { w, h, gap, offX, offY } = grid.value
    const col = i % COLS
    const row = Math.floor(i / COLS)
    return {
      sheet,
      key: `${sheet.machineId}-${i}`,
      left: offX + col * (w + gap),
      top: offY + row * (h + gap),
    }
  }),
)

/** Cut guides sit in the margins, never on the label itself. */
const xEdges = computed(() => {
  const { w, gap, offX } = grid.value
  return [offX, offX + w, offX + w + gap, offX + 2 * w + gap]
})

const yEdges = computed(() => {
  const { h, gap, offY } = grid.value
  const out: number[] = []
  for (let r = 0; r < ROWS; r++) {
    out.push(offY + r * (h + gap))
    out.push(offY + r * (h + gap) + h)
  }
  return out
})
</script>

<template>
  <div class="sticker-page">
    <div
      v-for="x in xEdges"
      :key="`vt-${x}`"
      class="mark mark-v"
      :style="{ left: `${x}mm`, top: `${grid.offY - 6}mm` }"
    />
    <div
      v-for="x in xEdges"
      :key="`vb-${x}`"
      class="mark mark-v"
      :style="{ left: `${x}mm`, top: `${grid.offY + grid.totalH + 1}mm` }"
    />
    <div
      v-for="y in yEdges"
      :key="`hl-${y}`"
      class="mark mark-h"
      :style="{ top: `${y}mm`, left: `${grid.offX - 6}mm` }"
    />
    <div
      v-for="y in yEdges"
      :key="`hr-${y}`"
      class="mark mark-h"
      :style="{ top: `${y}mm`, left: `${grid.offX + grid.totalW + 1}mm` }"
    />

    <div
      v-for="slot in slots"
      :key="slot.key"
      class="slot"
      :style="{
        left: `${slot.left}mm`,
        top: `${slot.top}mm`,
        width: `${grid.w}mm`,
        height: `${grid.h}mm`,
      }"
    >
      <component :is="motif" :sheet="slot.sheet" :t="t" />
    </div>
  </div>
</template>

<style scoped>
.sticker-page {
  position: relative;
  width: 100%;
  height: 100%;
  background: #fff;
}
.slot { position: absolute; overflow: hidden; }
.mark { position: absolute; background: #a8a29e; }
.mark-v { width: 0.2mm; height: 5mm; }
.mark-h { height: 0.2mm; width: 5mm; }
</style>
