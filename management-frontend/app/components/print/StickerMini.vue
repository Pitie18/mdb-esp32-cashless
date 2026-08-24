<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
</script>

<template>
  <!-- 50 x 30 mm: one code and one line. Anything else is unreadable at this
       size, and this label exists for places where 90 x 50 does not fit. -->
  <div class="sticker">
    <QrBlock v-if="main?.qr" class="qr" :svg="main.qr" />
    <div class="text">
      <div class="title">{{ main?.title }}</div>
      <div v-if="sheet.phone" class="phone">{{ sheet.phone }}</div>
      <div v-else class="hint">{{ main?.hint }}</div>
    </div>
  </div>
</template>

<style scoped>
.sticker {
  width: 100%;
  height: 100%;
  background: #fff;
  color: #14110f;
  display: flex;
  align-items: center;
  gap: 2mm;
  padding: 2mm;
  box-sizing: border-box;
  font-size: 2mm;
}
/* 20mm is what a 50 x 30mm sheet yields: the rest of the width carries
   the title and phone number. That still leaves the module size just
   under 0.5mm — the physical limit of this format, not a layout oversight. */
.qr { width: 20mm; height: 20mm; flex: none; }
.text { min-width: 0; }
.title { font-size: 1.15em; font-weight: 600; line-height: 1.15; }
.phone { font-size: 1.05em; font-weight: 600; margin-top: 0.6mm; }
.hint { font-size: 0.95em; color: #57534e; margin-top: 0.6mm; line-height: 1.2; }
</style>
