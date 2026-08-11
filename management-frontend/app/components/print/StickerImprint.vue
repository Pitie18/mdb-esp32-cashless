<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
</script>

<template>
  <!-- The small operator plate machines carry for §5 TMG. Deliberately quiet
       and text-first: it exists to be complete, not to be noticed. -->
  <div class="sticker">
    <div class="text">
      <div class="title">{{ sheet.texts.title || t('print.sticker.imprintTitle') }}</div>
      <div class="name">{{ sheet.companyName }}</div>
      <div v-if="sheet.addressLine" class="line">{{ sheet.addressLine }}</div>
      <div v-if="sheet.phone" class="line">{{ sheet.phone }}</div>
      <div v-if="sheet.email" class="line">{{ sheet.email }}</div>
      <div v-if="sheet.website" class="line">{{ sheet.website }}</div>
      <div class="machine">{{ sheet.machineName }}</div>
    </div>
    <QrBlock v-if="main?.qr" class="qr" :svg="main.qr" />
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
  font-size: 2.2mm;
  border: 0.3mm solid #14110f;
}
.text { flex: 1; min-width: 0; }
.title {
  font-size: 0.9em;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #78716c;
  margin-bottom: 0.8mm;
}
.name { font-size: 1.15em; font-weight: 600; line-height: 1.2; }
.line { font-size: 1em; color: #44403c; margin-top: 0.3mm; line-height: 1.25; }
.machine { font-size: 0.85em; color: #a8a29e; margin-top: 0.9mm; }
.qr { width: 17mm; height: 17mm; flex: none; }
</style>
