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
    <div class="brand">
      <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
      <span>{{ sheet.companyName }}</span>
    </div>
    <div class="rule" />

    <div class="title">{{ sheet.texts.title || t('print.poster.problemTitle') }}</div>
    <div class="lead">{{ sheet.texts.lead || t('print.poster.problemLead') }}</div>

    <template v-if="sheet.phone">
      <div class="label">{{ t('print.poster.supportPhone') }}</div>
      <div class="phone">{{ sheet.phone }}</div>
      <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
    </template>

    <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>

    <div class="spacer" />

    <div v-if="main?.qr" class="foot">
      <QrBlock class="qr" :svg="main.qr" />
      <div class="foot-text">
        <div class="foot-title">{{ main.title }}</div>
        <div class="foot-sub">{{ main.hint }}</div>
        <div v-if="url" class="url">{{ url }}</div>
      </div>
    </div>

    <!-- Labelled so the machine's location cannot be mistaken for the
         operator's registered address — they are often different places. -->
    <div class="meta">
      <span class="meta-label">{{ t('print.poster.locationLabel') }}</span>
      {{ sheet.machineName }}<template v-if="sheet.machineNote"> · {{ sheet.machineNote }}</template>
    </div>
    <div v-if="sheet.addressLine || sheet.email" class="meta">
      <span class="meta-label">{{ t('print.poster.operatorLabel') }}</span>
      {{ sheet.companyName
      }}<template v-if="sheet.addressLine"> · {{ sheet.addressLine }}</template
      ><template v-if="sheet.email"> · {{ sheet.email }}</template>
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
  background: #fff;
  color: #14110f;
}
.brand {
  display: flex;
  align-items: center;
  gap: 0.7em;
  font-size: 1.86em;
  letter-spacing: 0.04em;
  color: #78716c;
}
.logo { height: 3em; width: auto; object-fit: contain; }
.rule { height: 0.5mm; background: #14110f; margin: 0.8em 0 1.4em; }
.title { font-size: 3.39em; font-weight: 600; line-height: 1.2; }
.lead { font-size: 2.03em; color: #57534e; margin-top: 0.5em; line-height: 1.45; }
.label { font-size: 1.86em; color: #78716c; margin-top: 2em; }
.phone { font-size: 3.9em; font-weight: 600; margin-top: 0.15em; letter-spacing: -0.01em; }
.hours { font-size: 1.86em; color: #57534e; margin-top: 0.4em; }
.custom {
  font-size: 2.03em;
  color: #14110f;
  margin-top: 1.6em;
  padding: 0.8em 1em;
  border-left: 0.6mm solid #14110f;
  background: #f5f5f4;
  line-height: 1.4;
}
.spacer { flex: 1; min-height: 1.5em; }
.foot {
  display: flex;
  gap: 1.4em;
  align-items: center;
  border-top: 0.3mm solid #d6d3d1;
  padding-top: 1.4em;
}
.qr { width: max(var(--qr-min, 30mm), 12.5em); height: max(var(--qr-min, 30mm), 12.5em); flex: none; }
.foot-text { min-width: 0; }
.foot-title { font-size: 2.2em; font-weight: 600; }
.foot-sub { font-size: 1.86em; color: #57534e; margin-top: 0.25em; line-height: 1.4; }
.url { font-size: 1.6em; color: #a8a29e; margin-top: 0.5em; word-break: break-all; }
.meta { font-size: 1.6em; color: #a8a29e; margin-top: 1.2em; line-height: 1.4; }
.meta + .meta { margin-top: 0.3em; }
.meta-label { color: #57534e; font-weight: 600; }
</style>
