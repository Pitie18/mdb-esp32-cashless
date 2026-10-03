<script setup lang="ts">
/**
 * Renders a QR SVG string produced by `useMachinePrint()`. The markup is
 * generated locally by the qrcode library, never user input.
 *
 * Size comes from the parent via CSS; the inner `<svg>` carries width/height
 * presentation attributes which the stylesheet below deliberately overrides.
 */
defineProps<{ svg: string }>()
</script>

<template>
  <div class="qr" v-html="svg" />
</template>

<style scoped>
.qr {
  background: #fff;
  line-height: 0;
}
.qr :deep(svg) {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
