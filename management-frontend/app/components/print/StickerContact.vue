<script setup lang="ts">
import type { PosterT, PrintSheet } from '@/lib/printSheet'

defineProps<{ sheet: PrintSheet; t: PosterT }>()
</script>

<template>
  <!-- No QR at all. Not every location is one where people pull out a phone
       and scan; a number they can simply dial is sometimes the whole job. -->
  <div class="sticker">
    <div class="title">{{ sheet.texts.title || t('print.sticker.contactTitle') }}</div>
    <div v-if="sheet.phone" class="phone">{{ sheet.phone }}</div>
    <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
    <div class="foot">
      {{ sheet.companyName }}<template v-if="sheet.email"> · {{ sheet.email }}</template>
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
  flex-direction: column;
  justify-content: center;
  text-align: center;
  padding: 3mm 4mm;
  box-sizing: border-box;
  font-size: 2.6mm;
}
.title { font-size: 1.2em; font-weight: 600; line-height: 1.2; }
.phone { font-size: 2.3em; font-weight: 600; margin-top: 1mm; letter-spacing: -0.01em; }
.hours { font-size: 1em; color: #57534e; margin-top: 0.8mm; }
.foot { font-size: 0.85em; color: #a8a29e; margin-top: 1.4mm; line-height: 1.3; }
</style>
