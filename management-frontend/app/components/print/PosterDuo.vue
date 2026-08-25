<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import { readableUrl } from '@/lib/printSheet'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

const main = computed(() => props.sheet.slots.main)
const issue = computed(() => props.sheet.slots.issue)
const url = computed(() => (props.sheet.showUrl ? readableUrl(main.value?.target) : null))

/** The strip only earns its space if there is something to put in it. */
const showIssue = computed(() => Boolean(issue.value?.qr || props.sheet.phone))
</script>

<template>
  <div class="motif">
    <div class="top">
      <div class="brand">
        <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
        <span>{{ sheet.companyName }}</span>
      </div>

      <div class="title">{{ sheet.texts.title || t('print.poster.discoverTitle') }}</div>
      <div class="lead">{{ sheet.texts.lead || t('print.poster.discoverLead') }}</div>

      <div v-if="main?.qr" class="main-qr">
        <QrBlock class="qr" :svg="main.qr" />
        <div class="main-title">{{ main.title }}</div>
        <div class="main-hint">{{ main.hint }}</div>
        <div v-if="url" class="url">{{ url }}</div>
      </div>

      <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>
    </div>

    <!-- Deliberately a narrow strip, not a second half: a fault block the same
         size as the main block advertises that the machine breaks often. -->
    <div v-if="showIssue" class="strip">
      <QrBlock v-if="issue?.qr" class="strip-qr" :svg="issue.qr" />
      <div class="strip-text">
        <div class="strip-title">{{ issue?.title || t('print.poster.problemQrTitle') }}</div>
        <div class="strip-sub">
          <template v-if="sheet.phone">{{ sheet.phone }}<template v-if="sheet.hours"> · {{ sheet.hours }}</template></template>
          <template v-else>{{ issue?.hint }}</template>
        </div>
      </div>
    </div>

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
.top { flex: 1; display: flex; flex-direction: column; align-items: center; text-align: center; }
.brand {
  display: flex;
  align-items: center;
  gap: 0.7em;
  font-size: 1.86em;
  letter-spacing: 0.04em;
  color: #78716c;
}
.logo { height: 3em; width: auto; object-fit: contain; }
.title { font-size: 2.71em; font-weight: 600; line-height: 1.2; margin-top: 0.8em; }
.lead { font-size: 1.86em; color: #57534e; margin-top: 0.5em; line-height: 1.45; }
.main-qr { margin-top: 1em; }
.qr { width: max(var(--qr-min, 30mm), 11em); height: max(var(--qr-min, 30mm), 11em); margin: 0 auto; }
.main-title { font-size: 2.2em; font-weight: 600; margin-top: 0.6em; }
.main-hint { font-size: 1.86em; color: #57534e; margin-top: 0.25em; line-height: 1.4; }
.url { font-size: 1.6em; color: #a8a29e; margin-top: 0.4em; word-break: break-all; }
.custom {
  font-size: 1.86em;
  margin-top: 0.8em;
  padding: 0.7em 1em;
  background: #f5f5f4;
  border-radius: 0.6em;
  line-height: 1.4;
}
.strip {
  display: flex;
  align-items: center;
  gap: 1em;
  border: 0.3mm solid #d6d3d1;
  border-radius: 0.8em;
  padding: 0.8em 1em;
  margin-top: 0.9em;
  background: #fafaf9;
}
/* The secondary code, floored independently of the main one: two thirds of a
   shrinking floor lands under 0.3 mm per module on the smallest tile. */
.strip-qr { width: max(var(--qr-min-2, 20mm), 7em); height: max(var(--qr-min-2, 20mm), 7em); flex: none; }
.strip-text { min-width: 0; }
.strip-title { font-size: 1.86em; font-weight: 600; }
.strip-sub { font-size: 1.6em; color: #57534e; margin-top: 0.2em; line-height: 1.4; }
.meta { font-size: 1.6em; color: #a8a29e; margin-top: 1em; line-height: 1.4; }
.meta + .meta { margin-top: 0.3em; }
.meta-label { color: #57534e; font-weight: 600; }
</style>
