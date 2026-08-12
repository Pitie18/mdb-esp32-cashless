<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import type { PrintFormat, PrintSheet } from '@/lib/printSheet'
import { stickerLayout } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{
  sheets: PrintSheet[]
  motif: Component
  t: PosterT
  format: PrintFormat
}>()

const PAGE = { w: 210, h: 297 }

const grid = computed(() => {
  const { w, h, gap, cols, rows } = stickerLayout(props.format)
  const totalW = cols * w + (cols - 1) * gap
  const totalH = rows * h + (rows - 1) * gap
  return {
    w, h, gap, cols, rows, totalW, totalH,
    offX: (PAGE.w - totalW) / 2,
    offY: (PAGE.h - totalH) / 2,
  }
})

const slots = computed(() => {
  const { w, h, gap, cols, rows, offX, offY } = grid.value
  return props.sheets.slice(0, cols * rows).map((sheet, i) => ({
    sheet,
    key: `${sheet.machineId}-${i}`,
    left: offX + (i % cols) * (w + gap),
    top: offY + Math.floor(i / cols) * (h + gap),
  }))
})

/** Cut guides sit in the margins, never on the label itself. */
const xEdges = computed(() => {
  const { w, gap, cols, offX } = grid.value
  const out: number[] = []
  for (let c = 0; c < cols; c++) {
    out.push(offX + c * (w + gap))
    out.push(offX + c * (w + gap) + w)
  }
  return out
})

const yEdges = computed(() => {
  const { h, gap, rows, offY } = grid.value
  const out: number[] = []
  for (let r = 0; r < rows; r++) {
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
