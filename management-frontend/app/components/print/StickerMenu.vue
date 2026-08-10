<script setup lang="ts">
import QrBlock from './QrBlock.vue'
import type { PrintSheet } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

defineProps<{ sheet: PrintSheet; t: PosterT }>()
</script>

<template>
  <div class="sticker">
    <div class="qr-card">
      <QrBlock class="qr" :svg="sheet.qr.page" />
    </div>
    <div class="text">
      <div class="title">{{ t('print.sticker.menuTitle') }}</div>
      <div class="sub">{{ t('print.sticker.menuHint') }}</div>
      <div class="machine">{{ sheet.machineName }}</div>
    </div>
  </div>
</template>

<style scoped>
.sticker {
  width: 100%;
  height: 100%;
  background: #0f6e56;
  color: #e1f5ee;
  display: flex;
  align-items: center;
  gap: 3mm;
  padding: 4mm;
  box-sizing: border-box;
  font-size: 2.6mm;
}
.qr-card { background: #fff; border-radius: 1mm; padding: 1.2mm; flex: none; }
.qr { width: 22mm; height: 22mm; }
.text { min-width: 0; }
.title { font-size: 1.35em; font-weight: 600; line-height: 1.2; }
.sub { font-size: 1em; margin-top: 0.35em; line-height: 1.3; opacity: 0.88; }
.machine { font-size: 0.85em; margin-top: 0.35em; opacity: 0.7; }
</style>
