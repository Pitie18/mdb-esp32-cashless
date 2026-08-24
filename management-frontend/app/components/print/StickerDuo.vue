<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
const issue = computed(() => props.sheet.slots.issue)
</script>

<template>
  <div class="sticker">
    <!-- Same hierarchy as the Duo poster: the inviting side gets the larger
         code, the fault side is present but does not dominate a 90 x 50 label. -->
    <div v-if="main?.qr" class="lead-block">
      <QrBlock class="qr-main" :svg="main.qr" />
      <div class="lead-title">{{ main.title }}</div>
    </div>

    <div class="rule" />

    <div class="side">
      <div v-if="issue?.qr" class="side-row">
        <QrBlock class="qr-side" :svg="issue.qr" />
        <div class="side-text">
          <div class="side-title">{{ issue.title }}</div>
          <div class="side-hint">{{ issue.hint }}</div>
        </div>
      </div>
      <div v-if="sheet.phone" class="phone">{{ sheet.phone }}</div>
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
  gap: 2.5mm;
  padding: 3mm;
  box-sizing: border-box;
  font-size: 2.4mm;
}
.lead-block { flex: none; text-align: center; }
.qr-main { width: 26mm; height: 26mm; }
.lead-title { font-size: 1.15em; font-weight: 600; margin-top: 0.9mm; line-height: 1.15; }
.rule { width: 0.2mm; align-self: stretch; background: #d6d3d1; flex: none; }
.side { min-width: 0; flex: 1; }
.side-row { display: flex; align-items: center; gap: 1.8mm; }
/* 22mm, not smaller: the trouble-report URL is the longest this sticker
   carries, and below this size the module size drops under 0.5mm. */
.qr-side { width: 22mm; height: 22mm; flex: none; }
.side-text { min-width: 0; }
.side-title { font-size: 1.05em; font-weight: 600; line-height: 1.15; }
.side-hint { font-size: 0.9em; color: #57534e; margin-top: 0.4mm; line-height: 1.25; }
.phone { font-size: 0.95em; color: #57534e; margin-top: 1mm; }
</style>
