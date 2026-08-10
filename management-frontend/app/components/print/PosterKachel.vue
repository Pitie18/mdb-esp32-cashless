<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PrintSheet } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

/**
 * Only tiles with an actual target are rendered — an unlabelled or empty tile
 * on a poster is worse than one tile fewer.
 */
const tiles = computed(() => {
  const out: { key: string; svg: string; title: string; sub: string }[] = []
  out.push({
    key: 'page',
    svg: props.sheet.qr.page,
    // Short title: at three tiles the full "products · prices · payment"
    // wraps to four lines and pushes the imprint off the sheet.
    title: props.t('print.poster.pageShort'),
    sub: props.t('print.poster.pageHint'),
  })
  if (props.sheet.qr.whatsapp) {
    out.push({
      key: 'whatsapp',
      svg: props.sheet.qr.whatsapp,
      title: props.t('print.poster.whatsappTitle'),
      sub: props.t('print.poster.whatsappHint'),
    })
  }
  if (props.sheet.qr.problem) {
    out.push({
      key: 'problem',
      svg: props.sheet.qr.problem,
      title: props.t('print.poster.problemQrTitle'),
      sub: props.t('print.poster.problemQrHint'),
    })
  }
  return out
})
</script>

<template>
  <div class="motif">
    <div class="head">
      <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
      <div>
        <div class="head-title">{{ t('print.poster.helpTitle') }}</div>
        <div class="head-sub">
          {{ sheet.machineName }}<template v-if="sheet.machineNote"> · {{ sheet.machineNote }}</template>
        </div>
      </div>
    </div>

    <div class="body">
      <div class="tiles" :class="`tiles-${tiles.length}`">
        <div v-for="tile in tiles" :key="tile.key" class="tile">
          <QrBlock class="qr" :svg="tile.svg" />
          <div class="tile-title">{{ tile.title }}</div>
          <div v-if="tiles.length < 3" class="tile-sub">{{ tile.sub }}</div>
        </div>
      </div>

      <div v-if="sheet.phone" class="call">
        <QrBlock v-if="sheet.qr.tel" class="qr-call" :svg="sheet.qr.tel" />
        <div>
          <div class="call-label">{{ t('print.poster.callDirect') }}</div>
          <div class="call-phone">{{ sheet.phone }}</div>
          <div v-if="sheet.hours" class="call-hours">{{ sheet.hours }}</div>
        </div>
      </div>

      <div v-if="sheet.customText" class="custom">{{ sheet.customText }}</div>

      <div class="spacer" />

      <div class="imprint">
        <div>{{ sheet.companyName }}</div>
        <div v-if="sheet.addressLine">{{ sheet.addressLine }}</div>
        <div v-if="sheet.email">{{ sheet.email }}</div>
        <div v-if="sheet.website">{{ sheet.website }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.motif {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #14110f;
}
.head {
  background: #0f6e56;
  color: #e1f5ee;
  padding: 1.4em 1.6em;
  display: flex;
  align-items: center;
  gap: 1em;
}
.logo {
  height: 4em;
  width: auto;
  object-fit: contain;
  background: #fff;
  padding: 0.3em;
  border-radius: 0.4em;
}
.head-title { font-size: 2.71em; font-weight: 600; line-height: 1.2; }
.head-sub { font-size: 1.86em; margin-top: 0.3em; opacity: 0.85; }
.body {
  flex: 1;
  padding: 1.6em;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.tiles { display: grid; gap: 1em; }
.tiles-1 { grid-template-columns: 1fr; justify-items: center; }
.tiles-2 { grid-template-columns: 1fr 1fr; }
.tiles-3 { grid-template-columns: 1fr 1fr 1fr; }
.tile {
  border: 0.3mm solid #d6d3d1;
  border-radius: 1em;
  padding: 0.8em;
  text-align: center;
}
.qr { width: max(30mm, 10.5em); height: max(30mm, 10.5em); margin: 0 auto; }
.tile-title { font-size: 1.86em; font-weight: 600; margin-top: 0.5em; }
.tile-sub { font-size: 1.6em; color: #57534e; margin-top: 0.25em; line-height: 1.35; }
.call {
  border: 0.3mm solid #d6d3d1;
  border-radius: 1em;
  padding: 1em;
  margin-top: 0.9em;
  display: flex;
  gap: 1.2em;
  align-items: center;
}
.qr-call { width: max(30mm, 9em); height: max(30mm, 9em); flex: none; }
.call-label { font-size: 1.86em; color: #78716c; }
.call-phone { font-size: 2.88em; font-weight: 600; margin-top: 0.1em; }
.call-hours { font-size: 1.6em; color: #57534e; margin-top: 0.3em; }
.custom {
  font-size: 1.86em;
  margin-top: 1em;
  padding: 0.8em 1em;
  background: #e1f5ee;
  color: #04342c;
  border-radius: 0.6em;
  line-height: 1.4;
}
.spacer { flex: 1; min-height: 1em; }
.imprint {
  font-size: 1.6em;
  color: #78716c;
  border-top: 0.3mm solid #d6d3d1;
  padding-top: 1em;
  line-height: 1.5;
}
</style>
