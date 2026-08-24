<script setup lang="ts">
import { computed } from 'vue'
import { FORMAT_MM, isStickerFormat, tileLayout } from '@/lib/printSheet'
import type { PosterT, PrintSheet } from '@/lib/printSheet'
import type { PrintMotif } from '@/lib/printMotifs'

const props = defineProps<{
  motif: PrintMotif
  sheet: PrintSheet | null
  t: PosterT
  /** Rendered width in CSS pixels. */
  width: number
}>()

const PX_PER_MM = 96 / 25.4

/**
 * A sticker motif's "sheet" is an A4 page of eight labels. Shrunk to a
 * thumbnail that is an unreadable grid of specks, so the gallery shows one
 * label at its real proportions instead.
 */
const mm = computed(() =>
  isStickerFormat(props.motif.formats[0]!)
    ? { w: tileLayout(props.motif.formats[0]!).w, h: tileLayout(props.motif.formats[0]!).h }
    : FORMAT_MM[props.motif.formats[0]!],
)

const scale = computed(() => props.width / (mm.value.w * PX_PER_MM))
const height = computed(() => mm.value.h * PX_PER_MM * scale.value)

const innerStyle = computed(() => ({
  width: `${mm.value.w}mm`,
  height: `${mm.value.h}mm`,
  fontSize: `${(4 * mm.value.w) / 210}mm`,
  transform: `scale(${scale.value})`,
}))
</script>

<template>
  <div class="thumb" :style="{ width: `${width}px`, height: `${height}px` }">
    <div v-if="sheet" class="inner" :style="innerStyle">
      <component :is="motif.component" :sheet="sheet" :t="t" />
    </div>
  </div>
</template>

<style scoped>
.thumb {
  position: relative;
  overflow: hidden;
  background: #fff;
  border-radius: 3px;
}
.inner {
  box-sizing: border-box;
  transform-origin: top left;
  background: #fff;
  color: #14110f;
  overflow: hidden;
}
</style>
