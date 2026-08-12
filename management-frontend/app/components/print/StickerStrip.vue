<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
</script>

<template>
  <!-- The long band that runs across a machine front, above or below the
       product window. Read from a step away, so it carries one message and
       one code — never a paragraph. -->
  <div class="sticker">
    <div class="text">
      <div class="title">{{ sheet.texts.title || t('print.sticker.stripTitle') }}</div>
      <div v-if="sheet.phone" class="phone">{{ sheet.phone }}</div>
      <div v-else-if="main" class="hint">{{ main.hint }}</div>
      <div class="foot">
        {{ sheet.companyName }}<template v-if="sheet.hours"> · {{ sheet.hours }}</template>
      </div>
    </div>
    <div v-if="main?.qr" class="side">
      <QrBlock class="qr" :svg="main.qr" />
      <div class="side-label">{{ main.title }}</div>
    </div>
  </div>
</template>

<style scoped>
.sticker {
  width: 100%;
  height: 100%;
  background: #14110f;
  color: #f5f5f4;
  display: flex;
  align-items: center;
  gap: 5mm;
  padding: 4mm 6mm;
  box-sizing: border-box;
  font-size: 2.8mm;
}
.text { flex: 1; min-width: 0; }
.title { font-size: 1.5em; font-weight: 600; line-height: 1.15; }
.phone { font-size: 1.9em; font-weight: 600; margin-top: 0.8mm; letter-spacing: -0.01em; }
.hint { font-size: 1em; color: #a8a29e; margin-top: 0.8mm; }
.foot { font-size: 0.85em; color: #a8a29e; margin-top: 1mm; line-height: 1.3; }
.side { flex: none; text-align: center; }
.qr {
  width: 24mm;
  height: 24mm;
  background: #fff;
  border-radius: 1mm;
  padding: 1mm;
  box-sizing: border-box;
}
.side-label { font-size: 0.8em; color: #a8a29e; margin-top: 0.8mm; line-height: 1.15; }
</style>
