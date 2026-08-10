<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PrintSheet } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

/**
 * The problem QR is the point of this label. If the operator switched that
 * block off we still print a usable sticker by falling back to the machine
 * page, which carries the same feedback form one tap further in.
 */
const svg = computed(() => props.sheet.qr.problem ?? props.sheet.qr.page)
</script>

<template>
  <div class="sticker">
    <QrBlock class="qr" :svg="svg" />
    <div class="text">
      <div class="title">{{ t('print.sticker.problemTitle') }}</div>
      <div class="sub">{{ t('print.sticker.problemHint') }}</div>
      <div v-if="sheet.phone" class="phone">{{ sheet.phone }}</div>
      <div class="machine">{{ sheet.machineName }}</div>
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
  padding: 4mm;
  box-sizing: border-box;
  font-size: 2.6mm;
}
.qr { width: 22mm; height: 22mm; flex: none; }
.text { min-width: 0; }
.title { font-size: 1.35em; font-weight: 600; line-height: 1.2; }
.sub { font-size: 1em; color: #57534e; margin-top: 0.35em; line-height: 1.3; }
.phone { font-size: 1.35em; font-weight: 600; margin-top: 0.35em; }
.machine { font-size: 0.85em; color: #a8a29e; margin-top: 0.35em; }
</style>
