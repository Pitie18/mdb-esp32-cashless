<script setup lang="ts">
import QrBlock from './QrBlock.vue'
import type { PrintSheet } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

defineProps<{ sheet: PrintSheet; t: PosterT }>()
</script>

<template>
  <div class="motif">
    <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
    <div class="title">{{ t('print.poster.everythingTitle') }}</div>
    <div class="lead">{{ t('print.poster.everythingLead') }}</div>

    <QrBlock class="qr" :svg="sheet.qr.page" />
    <div class="scan">{{ t('print.poster.scanHint') }}</div>
    <div class="url">{{ sheet.pageUrl }}</div>

    <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>

    <div class="spacer" />

    <div class="foot">
      <template v-if="sheet.phone">
        <div class="foot-label">{{ t('print.poster.ratherCall') }}</div>
        <div class="phone">{{ sheet.phone }}</div>
        <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
      </template>
      <div class="meta">
        {{ sheet.companyName }} · {{ sheet.machineName }}
      </div>
      <div v-if="sheet.addressLine || sheet.email" class="imprint">
        <span v-if="sheet.addressLine">{{ sheet.addressLine }}</span>
        <span v-if="sheet.addressLine && sheet.email"> · </span>
        <span v-if="sheet.email">{{ sheet.email }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.motif {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  background: #fff;
  color: #14110f;
}
.logo { height: 5em; width: auto; object-fit: contain; margin-bottom: 1.2em; }
.title { font-size: 3.22em; font-weight: 600; line-height: 1.25; }
.lead { font-size: 2.03em; color: #57534e; margin-top: 0.6em; line-height: 1.45; }
.qr { width: max(30mm, 21.5em); height: max(30mm, 21.5em); margin-top: 1.3em; }
.scan { font-size: 1.86em; color: #78716c; margin-top: 0.7em; }
.url { font-size: 1.86em; color: #14110f; margin-top: 0.4em; word-break: break-all; }
.custom {
  font-size: 1.86em;
  margin-top: 1em;
  padding: 0.7em 1em;
  background: #f5f5f4;
  border-radius: 0.6em;
  line-height: 1.4;
}
.spacer { flex: 1; min-height: 1em; }
.foot {
  width: 100%;
  border-top: 0.3mm solid #d6d3d1;
  padding-top: 1.4em;
}
.foot-label { font-size: 1.86em; color: #78716c; }
.phone { font-size: 3.39em; font-weight: 600; margin-top: 0.15em; }
.hours { font-size: 1.6em; color: #57534e; margin-top: 0.3em; }
.meta { font-size: 1.6em; color: #a8a29e; margin-top: 1em; }
.imprint { font-size: 1.6em; color: #a8a29e; margin-top: 0.25em; }
</style>
