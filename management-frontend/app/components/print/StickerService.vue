<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
</script>

<template>
  <!-- The classic service label: the first thing a support call needs is which
       machine is calling, so the identifier outranks everything but the
       number to dial. -->
  <div class="sticker">
    <div class="text">
      <div class="title">{{ sheet.texts.title || t('print.sticker.serviceTitle') }}</div>
      <div class="machine">{{ sheet.machineName }}</div>
      <div v-if="sheet.phone" class="phone-row">
        <span class="phone-label">{{ t('print.poster.supportPhone') }}</span>
        <span class="phone">{{ sheet.phone }}</span>
      </div>
      <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
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
  background: #fff;
  color: #14110f;
  display: flex;
  align-items: center;
  gap: 3mm;
  padding: 3.5mm 4mm;
  box-sizing: border-box;
  font-size: 2.4mm;
  border-left: 2mm solid #14110f;
}
.text { flex: 1; min-width: 0; }
.title {
  font-size: 0.95em;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #78716c;
}
.machine { font-size: 1.75em; font-weight: 600; line-height: 1.15; margin-top: 0.6mm; }
.phone-row { display: flex; align-items: baseline; gap: 1.5mm; margin-top: 1.2mm; }
.phone-label { font-size: 0.9em; color: #78716c; }
.phone { font-size: 1.4em; font-weight: 600; }
.hours { font-size: 0.9em; color: #57534e; margin-top: 0.4mm; }
.side { flex: none; text-align: center; }
.qr { width: 20mm; height: 20mm; }
.side-label { font-size: 0.85em; color: #57534e; margin-top: 0.6mm; line-height: 1.15; }
</style>
