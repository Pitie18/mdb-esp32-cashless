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
    <!-- Restrained on purpose: hotels, practices and offices refuse a poster
         that shouts, and a refused poster helps nobody. -->
    <div class="frame">
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

      <div v-if="main?.qr" class="qr-block">
        <QrBlock class="qr" :svg="main.qr" />
        <div class="qr-title">{{ main.title }}</div>
        <div class="qr-hint">{{ main.hint }}</div>
        <div v-if="url" class="url">{{ url }}</div>
      </div>

      <div class="rule thin" />

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
  </div>
</template>

<style scoped>
.motif {
  height: 100%;
  box-sizing: border-box;
  padding: max(5mm, 2.5em);
  background: #fff;
  color: #14110f;
  /* System serifs only — a webfont would not be embedded in the print job. */
  font-family: Georgia, 'Times New Roman', 'Liberation Serif', serif;
}
.frame {
  height: 100%;
  box-sizing: border-box;
  border: 0.4mm solid #14110f;
  outline: 0.2mm solid #14110f;
  outline-offset: 0.8mm;
  padding: 1.5em;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.brand {
  display: flex;
  align-items: center;
  gap: 0.7em;
  font-size: 1.86em;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #57534e;
}
.logo { height: 3em; width: auto; object-fit: contain; }
.rule { width: 30%; height: 0.3mm; background: #14110f; margin: 0.9em 0; }
.rule.thin { width: 100%; height: 0.2mm; background: #d6d3d1; margin: 0.9em 0 0.7em; }
.title { font-size: 2.71em; line-height: 1.25; }
.lead { font-size: 2.03em; color: #44403c; margin-top: 0.6em; line-height: 1.5; }
.label {
  font-size: 1.6em;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #78716c;
  margin-top: 1.3em;
}
.phone { font-size: 3.73em; margin-top: 0.2em; }
.hours { font-size: 1.86em; color: #44403c; margin-top: 0.35em; }
.custom { font-size: 1.86em; margin-top: 1.2em; line-height: 1.45; font-style: italic; }
.spacer { flex: 1; min-height: 1em; }
.qr { width: max(30mm, 9.5em); height: max(30mm, 9.5em); margin: 0 auto; }
.qr-title { font-size: 2.03em; margin-top: 0.6em; }
.qr-hint { font-size: 1.6em; color: #57534e; margin-top: 0.2em; line-height: 1.4; }
.url { font-size: 1.6em; color: #a8a29e; margin-top: 0.3em; word-break: break-all; }
.meta { font-size: 1.6em; color: #78716c; line-height: 1.4; }
.meta + .meta { margin-top: 0.25em; }
.meta-label { color: #44403c; }
</style>
