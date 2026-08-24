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

    <div class="title">{{ sheet.texts.title || t('print.poster.helpYouTitle') }}</div>
    <div class="lead">{{ sheet.texts.lead || t('print.poster.helpYouLead') }}</div>

    <div v-if="sheet.phone" class="call">
      <div class="call-label">{{ t('print.poster.supportPhone') }}</div>
      <div class="phone">{{ sheet.phone }}</div>
      <div v-if="sheet.hours" class="hours">{{ sheet.hours }}</div>
    </div>

    <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>

    <div class="spacer" />

    <div v-if="main?.qr" class="foot">
      <div class="qr-card">
        <QrBlock class="qr" :svg="main.qr" />
      </div>
      <div class="foot-text">
        <div class="foot-title">{{ main.title }}</div>
        <div class="foot-sub">{{ main.hint }}</div>
        <div v-if="url" class="url">{{ url }}</div>
      </div>
    </div>

    <div class="meta">
      <div>
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
  background: #14110f;
  color: #f5f5f4;
}
.brand {
  display: flex;
  align-items: center;
  gap: 0.7em;
  font-size: 1.86em;
  letter-spacing: 0.04em;
  color: #ef9f27;
}
.logo {
  height: 3em;
  width: auto;
  object-fit: contain;
  background: #fff;
  padding: 0.25em;
  border-radius: 0.3em;
}
.title { font-size: 3.39em; font-weight: 600; line-height: 1.2; margin-top: 1.4em; }
.lead { font-size: 2.03em; color: #a8a29e; margin-top: 0.6em; line-height: 1.45; }
.call {
  background: #faeeda;
  color: #412402;
  border-radius: 1em;
  padding: 1.2em 1.4em;
  margin-top: 2em;
}
.call-label { font-size: 1.86em; color: #854f0b; }
.phone { font-size: 3.73em; font-weight: 600; margin-top: 0.15em; }
.hours { font-size: 1.6em; color: #854f0b; margin-top: 0.35em; }
.custom {
  font-size: 1.86em;
  margin-top: 1.4em;
  padding: 0.8em 1em;
  border-left: 0.6mm solid #ef9f27;
  color: #f5f5f4;
  line-height: 1.4;
}
.spacer { flex: 1; min-height: 1.5em; }
.foot { display: flex; gap: 1.4em; align-items: center; }
.qr-card { background: #fff; border-radius: 0.6em; padding: 0.7em; flex: none; }
.qr { width: max(var(--qr-min, 30mm), 12.5em); height: max(var(--qr-min, 30mm), 12.5em); }
.foot-text { min-width: 0; }
.foot-title { font-size: 2.2em; font-weight: 600; }
.foot-sub { font-size: 1.86em; color: #a8a29e; margin-top: 0.25em; line-height: 1.4; }
.url { font-size: 1.6em; color: #78716c; margin-top: 0.5em; word-break: break-all; }
.meta {
  font-size: 1.6em;
  color: #78716c;
  margin-top: 1.4em;
  border-top: 0.3mm solid #292524;
  padding-top: 1em;
}
.imprint { margin-top: 0.3em; }
.meta-label { color: #a8a29e; font-weight: 600; }
</style>
