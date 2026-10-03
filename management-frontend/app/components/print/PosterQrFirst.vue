<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import { readableUrl } from '@/lib/printSheet'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
const url = computed(() => (props.sheet.showUrl ? readableUrl(main.value?.target) : null))
</script>

<template>
  <div class="motif">
    <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
    <div class="title">{{ sheet.texts.title || t('print.poster.everythingTitle') }}</div>
    <div class="lead">{{ sheet.texts.lead || t('print.poster.everythingLead') }}</div>

    <template v-if="main?.qr">
      <QrBlock class="qr" :svg="main.qr" />
      <div class="scan">{{ main.hint }}</div>
      <div v-if="url" class="url">{{ url }}</div>
    </template>

    <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>

    <div class="spacer" />

    <div class="foot">
      <template v-if="sheet.phone">
        <div class="foot-label">{{ t('print.poster.ratherCall') }}</div>
        <div class="phone">{{ sheet.phone }}</div>
        <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
      </template>
      <div class="meta">
        <span class="meta-label">{{ t('print.poster.locationLabel') }}</span>
        {{ sheet.machineName }}<template v-if="sheet.machineNote"> · {{ sheet.machineNote }}</template>
      </div>
      <div v-if="sheet.addressLine || sheet.email" class="imprint">
        <span class="meta-label">{{ t('print.poster.operatorLabel') }}</span>
        {{ sheet.companyName
        }}<template v-if="sheet.addressLine"> · {{ sheet.addressLine }}</template
        ><template v-if="sheet.email"> · {{ sheet.email }}</template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.motif {
  height: 100%;
  box-sizing: border-box;
  padding: max(var(--pad-min, 5mm), 2.5em);
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
.qr { width: max(var(--qr-min, 30mm), 19.5em); height: max(var(--qr-min, 30mm), 19.5em); margin-top: 1.1em; }
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
.meta { font-size: 1.6em; color: #a8a29e; margin-top: 1em; line-height: 1.4; }
.imprint { font-size: 1.6em; color: #a8a29e; margin-top: 0.3em; line-height: 1.4; }
.meta-label { color: #57534e; font-weight: 600; }
</style>
