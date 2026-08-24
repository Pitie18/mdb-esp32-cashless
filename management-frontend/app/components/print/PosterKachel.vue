<script setup lang="ts">
import { computed } from 'vue'
import QrBlock from './QrBlock.vue'
import type { PosterT, PrintSheet } from '@/lib/printSheet'

const props = defineProps<{ sheet: PrintSheet; t: PosterT }>()

/**
 * Only slots that resolved to an actual code are rendered — an empty tile on a
 * poster is worse than one tile fewer.
 */
const tiles = computed(() =>
  ['tile1', 'tile2', 'tile3']
    .map(id => props.sheet.slots[id])
    .filter((slot): slot is NonNullable<typeof slot> => Boolean(slot?.qr)),
)

const call = computed(() => props.sheet.slots.call)
const showCall = computed(() => Boolean(props.sheet.phone || call.value?.qr))
</script>

<template>
  <div class="motif">
    <div class="head">
      <img v-if="sheet.logoUrl" :src="sheet.logoUrl" class="logo" alt="">
      <div>
        <div class="head-title">{{ sheet.texts.title || t('print.poster.helpTitle') }}</div>
        <div class="head-sub">
          {{ sheet.machineName }}<template v-if="sheet.machineNote"> · {{ sheet.machineNote }}</template>
        </div>
      </div>
    </div>

    <div class="body">
      <div class="tiles" :class="`tiles-${tiles.length}`">
        <div v-for="tile in tiles" :key="tile.id" class="tile">
          <QrBlock class="qr" :svg="tile.qr!" />
          <div class="tile-title">{{ tile.title }}</div>
          <!-- At three tiles the hint wraps to four lines and pushes the
               imprint off the sheet, so it only appears when there is room. -->
          <div v-if="tiles.length < 3" class="tile-sub">{{ tile.hint }}</div>
        </div>
      </div>

      <div v-if="showCall" class="call">
        <QrBlock v-if="call?.qr" class="qr-call" :svg="call.qr" />
        <div>
          <div class="call-label">{{ call?.title || t('print.poster.callDirect') }}</div>
          <div v-if="sheet.phone" class="call-phone">{{ sheet.phone }}</div>
          <div v-else class="call-hint">{{ call?.hint }}</div>
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
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #14110f;
}
.head {
  background: #0f6e56;
  color: #e1f5ee;
  padding: max(var(--pad-min, 5mm), 2.5em);
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
  padding: max(var(--pad-min, 5mm), 2.5em);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.tiles { display: grid; gap: 1em; }
.tiles-0 { display: none; }
.tiles-1 { grid-template-columns: 1fr; justify-items: center; }
.tiles-2 { grid-template-columns: 1fr 1fr; }
.tiles-3 { grid-template-columns: 1fr 1fr 1fr; }
.tile {
  border: 0.3mm solid #d6d3d1;
  border-radius: 1em;
  padding: 0.8em;
  text-align: center;
}
.qr { width: max(var(--qr-min, 30mm), 10.5em); height: max(var(--qr-min, 30mm), 10.5em); margin: 0 auto; }
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
.qr-call { width: max(var(--qr-min, 30mm), 9em); height: max(var(--qr-min, 30mm), 9em); flex: none; }
.call-label { font-size: 1.86em; color: #78716c; }
.call-phone { font-size: 2.88em; font-weight: 600; margin-top: 0.1em; }
.call-hint { font-size: 1.86em; margin-top: 0.1em; }
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
