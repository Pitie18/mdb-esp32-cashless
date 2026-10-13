<script setup lang="ts">
/**
 * Bar chart in the style of the iOS app (Swift Charts): gradient bars with
 * rounded corners, weekend bars lighter, a dashed orange average line with its
 * value on it, horizontal grid lines with the y axis on the leading edge, and
 * a hover/tap selection that draws a vertical rule plus a tooltip card.
 *
 * Plain SVG rather than unovis so every one of those details matches iOS.
 */
import { useElementSize } from '@vueuse/core'
import {
  average as mean,
  barIndexAt,
  compactNumber,
  isWeekend,
  labelStride,
  niceTicks,
  type ChartBucket,
} from '~/lib/barChart'

export interface BarChartRow {
  label: string
  value: string
}

export interface BarChartPoint {
  date: Date
  value: number
  /** Tooltip lines under the date. Defaults to one line with the value. */
  rows?: BarChartRow[]
}

const props = withDefaults(defineProps<{
  data: BarChartPoint[]
  /** Formats the value for the tooltip and the average label. */
  formatValue?: (v: number) => string
  /** Formats y axis labels. Defaults to the compact iOS form (`250`, `2k`). */
  formatAxis?: (v: number) => string
  /** Label of the default tooltip line. */
  valueLabel?: string
  bucket?: ChartBucket
  color?: 'blue' | 'purple'
  showAverage?: boolean
  /** Plot height in px. */
  height?: number
}>(), {
  formatValue: (v: number) => String(Math.round(v * 100) / 100),
  formatAxis: compactNumber,
  valueLabel: '',
  bucket: 'day',
  color: 'blue',
  showAverage: true,
  height: 200,
})

const { locale } = useI18n()

const root = ref<HTMLElement | null>(null)
const { width } = useElementSize(root)

// Leave room above the plot for the average label, below it for the x labels.
const TOP = 16
const BOTTOM = 22
const AXIS_GAP = 6

const ticks = computed(() => niceTicks(Math.max(0, ...props.data.map(p => p.value))))
const yMax = computed(() => ticks.value.at(-1) ?? 1)
const tickLabels = computed(() => ticks.value.map(v => props.formatAxis(v)))

// ~6.5 px per character at 11px — the axis only needs to fit its widest label.
const left = computed(() => Math.ceil(Math.max(...tickLabels.value.map(l => l.length), 1) * 6.5) + AXIS_GAP)
const plotW = computed(() => Math.max(0, width.value - left.value))
const plotH = computed(() => Math.max(0, props.height - TOP - BOTTOM))

const slot = computed(() => (props.data.length ? plotW.value / props.data.length : 0))
// Swift Charts leaves roughly a third of each slot empty; cap thin series so a
// 7-day chart does not turn into a row of fat blocks.
const barW = computed(() => Math.max(1, Math.min(slot.value * (slot.value > 12 ? 0.7 : 0.6), 48)))

const y = (v: number) => TOP + plotH.value - (v / yMax.value) * plotH.value

const bars = computed(() => props.data.map((p, i) => {
  const h = Math.max(0, (p.value / yMax.value) * plotH.value)
  return {
    key: p.date.getTime(),
    x: left.value + i * slot.value + (slot.value - barW.value) / 2,
    y: TOP + plotH.value - h,
    h,
    dim: props.bucket === 'day' && isWeekend(p.date),
  }
}))

const avg = computed(() => mean(props.data.map(p => p.value)))
const avgY = computed(() => y(avg.value))

const dateFmt = computed(() => {
  const opts: Intl.DateTimeFormatOptions = props.bucket === 'month'
    ? { month: 'short', year: '2-digit' }
    : { day: 'numeric', month: 'short' }
  return new Intl.DateTimeFormat(locale.value, opts)
})
const tooltipFmt = computed(() => {
  const opts: Intl.DateTimeFormatOptions = props.bucket === 'month'
    ? { month: 'long', year: 'numeric' }
    : props.bucket === 'week'
      ? { day: 'numeric', month: 'short', year: 'numeric' }
      : { weekday: 'short', day: 'numeric', month: 'short' }
  return new Intl.DateTimeFormat(locale.value, opts)
})

const xLabels = computed(() => {
  const stride = labelStride(props.data.length, props.bucket)
  // Anchor the labels on the newest bar so "today" is always labelled.
  const last = props.data.length - 1
  return props.data
    .map((p, i) => ({ i, p }))
    .filter(({ i }) => (last - i) % stride === 0)
    .map(({ i, p }) => ({
      key: p.date.getTime(),
      x: left.value + i * slot.value + slot.value / 2,
      text: dateFmt.value.format(p.date),
    }))
})

// ── Selection ───────────────────────────────────────────────────────────────
const selected = ref<number | null>(null)

function select(e: PointerEvent) {
  const svg = e.currentTarget as SVGSVGElement
  const rect = svg.getBoundingClientRect()
  const i = barIndexAt(e.clientX - rect.left - left.value, plotW.value, props.data.length)
  selected.value = i < 0 ? null : i
}

function clear(e: PointerEvent) {
  // On touch, keep the selection after lifting the finger (like iOS keeps the
  // tooltip until the next touch elsewhere); a mouse leaving clears it.
  if (e.pointerType === 'mouse') selected.value = null
}

watch(() => props.data, () => { selected.value = null })

const selectedPoint = computed(() => (selected.value == null ? null : props.data[selected.value] ?? null))
const selectedX = computed(() => (selected.value == null ? 0 : left.value + selected.value * slot.value + slot.value / 2))

const tooltip = ref<HTMLElement | null>(null)
const { width: tooltipW } = useElementSize(tooltip)
// Centre on the bar, but keep the card inside the chart (Swift Charts'
// `overflowResolution: .fit(to: .chart)`).
const tooltipLeft = computed(() => {
  const w = tooltipW.value || 150
  return Math.min(Math.max(selectedX.value - w / 2, 0), Math.max(0, width.value - w))
})

const tooltipRows = computed<BarChartRow[]>(() => {
  const p = selectedPoint.value
  if (!p) return []
  return p.rows ?? [{ label: props.valueLabel, value: props.formatValue(p.value) }]
})

// Grow the bars in once on mount, like the iOS chart animating in.
const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => { mounted.value = true }))

const gradientId = `bar-gradient-${useId()}`
</script>

<template>
  <div ref="root" class="bar-chart relative w-full select-none" :class="`bar-chart--${color}`" :style="{ height: `${height}px` }">
    <svg
      v-if="width > 0"
      :width="width"
      :height="height"
      class="block touch-pan-y overflow-visible"
      role="img"
      @pointermove="select"
      @pointerdown="select"
      @pointerleave="clear"
    >
      <defs>
        <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" class="bar-chart__stop-top" />
          <stop offset="100%" class="bar-chart__stop-bottom" />
        </linearGradient>
      </defs>

      <!-- Grid + leading y axis -->
      <g class="bar-chart__axis">
        <template v-for="(t, i) in ticks" :key="t">
          <line :x1="left" :x2="width" :y1="y(t)" :y2="y(t)" class="bar-chart__grid" />
          <text :x="left - AXIS_GAP" :y="y(t)" text-anchor="end" dominant-baseline="middle">{{ tickLabels[i] }}</text>
        </template>
        <text
          v-for="l in xLabels"
          :key="l.key"
          :x="l.x"
          :y="height - 6"
          text-anchor="middle"
        >{{ l.text }}</text>
      </g>

      <!-- Bars -->
      <g class="bar-chart__bars" :class="{ 'is-mounted': mounted }">
        <rect
          v-for="b in bars"
          :key="b.key"
          :x="b.x"
          :y="b.y"
          :width="barW"
          :height="b.h"
          :rx="Math.min(3, barW / 2, b.h / 2)"
          :fill="`url(#${gradientId})`"
          :opacity="b.dim ? 0.45 : 1"
          class="bar-chart__bar"
        />
      </g>

      <!-- Average -->
      <g v-if="showAverage && avg > 0" class="bar-chart__average">
        <line :x1="left" :x2="width" :y1="avgY" :y2="avgY" stroke-width="1.5" stroke-dasharray="4 4" />
        <text :x="width" :y="avgY - 4" text-anchor="end">Ø {{ formatValue(avg) }}</text>
      </g>

      <!-- Selection rule -->
      <line
        v-if="selectedPoint"
        :x1="selectedX"
        :x2="selectedX"
        :y1="TOP"
        :y2="TOP + plotH"
        class="bar-chart__rule"
      />
    </svg>

    <div
      v-if="selectedPoint"
      ref="tooltip"
      class="bar-chart__tooltip pointer-events-none absolute z-10 min-w-[140px] rounded-lg border bg-popover/85 px-2.5 py-2 text-popover-foreground shadow-md backdrop-blur-md"
      :style="{ left: `${tooltipLeft}px`, bottom: `${height - TOP + 4}px` }"
    >
      <p class="text-xs font-semibold">{{ tooltipFmt.format(selectedPoint.date) }}</p>
      <div v-for="row in tooltipRows" :key="row.label" class="mt-1 flex justify-between gap-3 text-[11px] leading-tight">
        <span class="text-muted-foreground">{{ row.label }}</span>
        <span class="tabular-nums">{{ row.value }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bar-chart--blue { --bar: var(--chart-blue); }
.bar-chart--purple { --bar: var(--chart-purple); }

/* SwiftUI's `Color.gradient`: a touch lighter at the top. */
.bar-chart__stop-top { stop-color: color-mix(in oklab, var(--bar) 78%, white); }
.bar-chart__stop-bottom { stop-color: var(--bar); }

.bar-chart__axis text {
  font-size: 11px;
  fill: var(--muted-foreground);
  font-variant-numeric: tabular-nums;
}
.bar-chart__grid {
  stroke: var(--border);
  stroke-width: 1;
}

.bar-chart__bar {
  transform-box: fill-box;
  transform-origin: bottom;
  transform: scaleY(0);
  transition: transform 0.5s cubic-bezier(0.2, 0.8, 0.2, 1), y 0.3s ease, height 0.3s ease;
}
.bar-chart__bars.is-mounted .bar-chart__bar { transform: scaleY(1); }

.bar-chart__average line { stroke: var(--chart-average); }
.bar-chart__average text {
  font-size: 10px;
  font-weight: 600;
  fill: var(--chart-average);
  paint-order: stroke;
  stroke: var(--card);
  stroke-width: 3px;
}

.bar-chart__rule {
  stroke: color-mix(in oklab, var(--muted-foreground) 45%, transparent);
  stroke-width: 1.5;
}

@media (prefers-reduced-motion: reduce) {
  .bar-chart__bar { transition: none; transform: none; }
}
</style>
