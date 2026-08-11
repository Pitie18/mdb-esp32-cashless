<script setup lang="ts">
import { IconArrowLeft, IconPrinter, IconAlertTriangle, IconLoader2, IconRotate, IconDeviceFloppy } from '@tabler/icons-vue'
import { watchDebounced } from '@vueuse/core'
import StickerSheet from '@/components/print/StickerSheet.vue'
import { useMachinePrint } from '@/composables/useMachinePrint'
import { PRINT_MOTIFS, defaultLayout, isStickerFormat, motifById } from '@/lib/printMotifs'
import type { MotifId } from '@/lib/printMotifs'
import { FORMAT_MM, SLOT_SOURCES, distributeStickers } from '@/lib/printSheet'
import type { PosterLayout, PosterT, PrintBlock, PrintFormat, PrintSheet, SlotSource } from '@/lib/printSheet'

definePageMeta({ middleware: 'auth', layout: false })

const { t, locale, locales } = useI18n()
const route = useRoute()
const machineId = route.params.id as string

const print = useMachinePrint()

const motifId = ref<MotifId>('klar')
const format = ref<PrintFormat>('a4')
const customText = ref('')
const sheetLocale = ref<string>(locale.value)
const selectedIds = ref<string[]>([machineId])

const motif = computed(() => motifById(motifId.value) ?? PRINT_MOTIFS[0]!)
const isSticker = computed(() => isStickerFormat(format.value))

/** The working copy the preview renders; saved rows only seed it. */
const layout = ref<PosterLayout>(defaultLayout(PRINT_MOTIFS[0]!))

/**
 * Poster strings resolve against the *sheet* language, not the UI language:
 * an operator running the app in English still prints a German sign.
 */
const posterT: PosterT = (key, named) =>
  (t as unknown as (k: string, n: Record<string, unknown>, o: { locale: string }) => string)(
    key,
    named ?? {},
    { locale: sheetLocale.value },
  )

const localeOptions = computed(() =>
  (locales.value as { code: string; name?: string }[]).map(l => ({
    code: l.code,
    name: l.name ?? l.code,
  })),
)

/** Saved layout for this motif, or the motif's defaults. */
function seedLayout() {
  const stored = print.storedLayout(motif.value.id, machineId)
  const base = defaultLayout(motif.value)
  layout.value = stored
    ? {
        ...base,
        ...stored,
        // Slots the motif gained since the layout was saved must not vanish.
        slots: { ...base.slots, ...(stored.slots ?? {}) },
        custom: { ...base.custom, ...(stored.custom ?? {}) },
        texts: { ...(stored.texts ?? {}) },
        blocks: stored.blocks ?? base.blocks,
      }
    : base
}

watch(motif, (m) => {
  if (!m.formats.includes(format.value)) format.value = m.formats[0]!
  seedLayout()
})

function setSlotSource(slotId: string, source: SlotSource) {
  layout.value = {
    ...layout.value,
    slots: { ...layout.value.slots, [slotId]: { source } },
  }
}

function toggleBlock(block: PrintBlock) {
  const current = layout.value.blocks ?? []
  layout.value = {
    ...layout.value,
    blocks: current.includes(block) ? current.filter(b => b !== block) : [...current, block],
  }
}

function setText(key: string, value: string) {
  const texts = { ...(layout.value.texts ?? {}) }
  if (value.trim()) texts[key] = value
  else delete texts[key]
  layout.value = { ...layout.value, texts }
}

function resetText(key: string) {
  setText(key, '')
}

function toggleMachine(id: string) {
  if (id === machineId) return
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(m => m !== id)
    : [...selectedIds.value, id]
}

/** Sources whose data is not configured, so the picker can say so up front. */
const unavailableSources = computed(() => {
  const co = print.company.value
  const machine = print.machines.value.find(m => m.id === machineId)
  const out = new Set<SlotSource>()
  if (!(machine?.contact_phone?.trim() || co?.contact_phone?.trim())) out.add('tel')
  if (!(machine?.whatsapp_phone?.trim() || co?.whatsapp_phone?.trim())) out.add('whatsapp')
  return out
})

const sheets = ref<PrintSheet[]>([])
const building = ref(false)

async function rebuild() {
  if (!print.company.value) return
  building.value = true
  try {
    sheets.value = await print.buildSheets({
      machineIds: selectedIds.value,
      slotDeclarations: motif.value.slots,
      layout: layout.value,
      format: format.value,
      t: posterT,
      whatsappTemplate: posterT('print.whatsappTemplate'),
      customText: customText.value,
      fallbackMachineName: posterT('print.fallbackMachineName'),
    })
  } finally {
    building.value = false
  }
}

watch([selectedIds, layout, format, sheetLocale, () => print.company.value], rebuild, { deep: true })
watchDebounced(customText, rebuild, { debounce: 300 })

onMounted(async () => {
  await print.load()
  seedLayout()
  if (!selectedIds.value.includes(machineId)) selectedIds.value.unshift(machineId)
  await rebuild()
})

// ── Saving ──────────────────────────────────────────────────────────────────
const saveScope = ref<'company' | 'machine'>('company')
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedAt = ref(false)

const scopeInUse = computed(() => print.layoutScope(motif.value.id, machineId))

async function save() {
  saving.value = true
  saveError.value = null
  try {
    await print.saveLayout(motif.value.id, layout.value, saveScope.value, machineId)
    savedAt.value = true
    setTimeout(() => { savedAt.value = false }, 2500)
  } catch (e) {
    saveError.value = (e as Error)?.message ?? 'save-failed'
  } finally {
    saving.value = false
  }
}

async function resetToDefaults() {
  layout.value = defaultLayout(motif.value)
  await rebuild()
}

// ── Rendering ───────────────────────────────────────────────────────────────
const pages = computed<PrintSheet[][]>(() =>
  isSticker.value ? distributeStickers(sheets.value) : sheets.value.map(s => [s]),
)

const sheetMm = computed(() => FORMAT_MM[format.value])

const sheetStyle = computed(() => ({
  width: `${sheetMm.value.w}mm`,
  height: `${sheetMm.value.h}mm`,
  // One layout scales across A4/A5/A6: motifs size everything in em.
  fontSize: `${(4 * sheetMm.value.w) / 210}mm`,
}))

const PX_PER_MM = 96 / 25.4
const PREVIEW_WIDTH_PX = 460

const previewScale = computed(() => PREVIEW_WIDTH_PX / (sheetMm.value.w * PX_PER_MM))

const previewStyle = computed(() => ({
  width: `${PREVIEW_WIDTH_PX}px`,
  height: `${sheetMm.value.h * PX_PER_MM * previewScale.value}px`,
  '--preview-scale': String(previewScale.value),
}))

const pageSizeCss = computed(() => {
  if (format.value === 'a5') return 'A5 portrait'
  if (format.value === 'a6') return 'A6 portrait'
  return 'A4 portrait'
})

useHead(() => ({
  title: t('print.pageTitle'),
  style: [{ innerHTML: `@page { size: ${pageSizeCss.value}; margin: 0; }` }],
}))

const missingFields = computed(() => {
  const set = new Set<string>()
  for (const sheet of sheets.value) for (const field of sheet.missing) set.add(field)
  return [...set]
})

const usesCustomLink = computed(() =>
  motif.value.slots.some(s => (layout.value.slots?.[s.id]?.source ?? s.defaultSource) === 'custom'),
)

async function doPrint() {
  await print.logPrinted(sheets.value, {
    motif: motifId.value,
    format: format.value,
    layout: layout.value,
    slotDeclarations: motif.value.slots,
    sheetLanguage: sheetLocale.value,
  })
  await nextTick()
  window.print()
}
</script>

<template>
  <div class="print-page flex min-h-screen items-start gap-8 bg-background p-6 text-foreground">
    <aside class="no-print sticky top-6 flex max-h-[calc(100vh-3rem)] w-[330px] flex-none flex-col gap-5 overflow-y-auto pr-1">
      <NuxtLink
        :to="`/machines/${machineId}`"
        class="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
      >
        <IconArrowLeft :size="16" />
        {{ t('print.backToMachine') }}
      </NuxtLink>

      <div>
        <h1 class="text-lg font-semibold">{{ t('print.pageTitle') }}</h1>
        <p class="mt-1 text-sm text-muted-foreground">{{ t('print.pageLead') }}</p>
      </div>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.motif') }}
        </h2>
        <div class="flex flex-col gap-1.5">
          <button
            v-for="m in PRINT_MOTIFS"
            :key="m.id"
            type="button"
            class="flex flex-col gap-0.5 rounded-lg border bg-card px-3 py-2 text-left transition-colors hover:bg-muted"
            :class="m.id === motifId ? 'border-primary ring-1 ring-primary' : 'border-input'"
            @click="motifId = m.id"
          >
            <span class="text-sm font-medium">{{ t(m.labelKey) }}</span>
            <span class="text-xs leading-snug text-muted-foreground">{{ t(m.descriptionKey) }}</span>
          </button>
        </div>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.format') }}
        </h2>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="f in motif.formats"
            :key="f"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="f === format ? 'border-primary bg-primary/10 text-primary' : 'border-input bg-card hover:bg-muted'"
            @click="format = f"
          >
            {{ t(`print.formats.${f}`) }}
          </button>
        </div>
      </section>

      <!-- One picker per QR slot the motif declares, with its label and hint. -->
      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.qrCodes') }}
        </h2>
        <div v-for="slot in motif.slots" :key="slot.id" class="mb-3">
          <label class="text-xs text-muted-foreground">{{ t(slot.labelKey) }}</label>
          <select
            class="mt-1 h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            :value="layout.slots?.[slot.id]?.source ?? slot.defaultSource"
            @change="setSlotSource(slot.id, ($event.target as HTMLSelectElement).value as SlotSource)"
          >
            <option
              v-for="source in SLOT_SOURCES"
              :key="source"
              :value="source"
              :disabled="source === 'none' && !slot.optional"
            >
              {{ t(`print.sources.${source}`) }}{{ unavailableSources.has(source) ? ` — ${t('print.sourceUnavailable')}` : '' }}
            </option>
          </select>

          <!-- Label and hint follow the chosen source until overridden here. -->
          <div class="mt-1.5 grid grid-cols-1 gap-1.5">
            <div class="flex items-center gap-1">
              <input
                class="h-8 w-full rounded-md border border-input bg-background px-2 text-xs placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                :placeholder="sheets[0]?.slots?.[slot.id]?.title ?? t('print.texts.slotTitle')"
                :value="layout.texts?.[`slot.${slot.id}.title`] ?? ''"
                @input="setText(`slot.${slot.id}.title`, ($event.target as HTMLInputElement).value)"
              >
              <button
                v-if="layout.texts?.[`slot.${slot.id}.title`]"
                type="button"
                class="text-muted-foreground hover:text-foreground"
                :title="t('print.resetText')"
                @click="resetText(`slot.${slot.id}.title`)"
              >
                <IconRotate :size="14" />
              </button>
            </div>
            <div class="flex items-center gap-1">
              <input
                class="h-8 w-full rounded-md border border-input bg-background px-2 text-xs placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                :placeholder="sheets[0]?.slots?.[slot.id]?.hint ?? t('print.texts.slotHint')"
                :value="layout.texts?.[`slot.${slot.id}.hint`] ?? ''"
                @input="setText(`slot.${slot.id}.hint`, ($event.target as HTMLInputElement).value)"
              >
              <button
                v-if="layout.texts?.[`slot.${slot.id}.hint`]"
                type="button"
                class="text-muted-foreground hover:text-foreground"
                :title="t('print.resetText')"
                @click="resetText(`slot.${slot.id}.hint`)"
              >
                <IconRotate :size="14" />
              </button>
            </div>
          </div>
        </div>
      </section>

      <section v-if="usesCustomLink">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.customLink') }}
        </h2>
        <input
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          :placeholder="t('print.customLinkPlaceholder')"
          :value="layout.custom?.url ?? ''"
          @input="layout = { ...layout, custom: { ...layout.custom, url: ($event.target as HTMLInputElement).value } }"
        >
        <p class="mt-1 text-xs text-muted-foreground">{{ t('print.customLinkHint') }}</p>
      </section>

      <section v-if="motif.texts.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.headlines') }}
        </h2>
        <div v-for="field in motif.texts" :key="field.id" class="mb-2">
          <label class="text-xs text-muted-foreground">{{ t(field.labelKey) }}</label>
          <div class="mt-1 flex items-center gap-1">
            <input
              class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              :placeholder="posterT(field.defaultKey)"
              :value="layout.texts?.[field.id] ?? ''"
              @input="setText(field.id, ($event.target as HTMLInputElement).value)"
            >
            <button
              v-if="layout.texts?.[field.id]"
              type="button"
              class="text-muted-foreground hover:text-foreground"
              :title="t('print.resetText')"
              @click="resetText(field.id)"
            >
              <IconRotate :size="14" />
            </button>
          </div>
        </div>
      </section>

      <section v-if="motif.blocks.length">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.blocks') }}
        </h2>
        <label v-for="b in motif.blocks" :key="b" class="flex items-center gap-2 py-0.5 text-sm">
          <input
            type="checkbox"
            :checked="(layout.blocks ?? []).includes(b)"
            @change="toggleBlock(b)"
          >
          <span>{{ t(`print.blockNames.${b}`) }}</span>
        </label>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.sheetLanguage') }}
        </h2>
        <select
          v-model="sheetLocale"
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          <option v-for="l in localeOptions" :key="l.code" :value="l.code">{{ l.name }}</option>
        </select>
      </section>

      <section>
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.customText') }}
        </h2>
        <input
          v-model="customText"
          :placeholder="t('print.customTextPlaceholder')"
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
        <p class="mt-1 text-xs text-muted-foreground">{{ t('print.customTextHint') }}</p>
      </section>

      <!-- Saving turns this configuration into what colleagues get next time. -->
      <section v-if="print.canSave.value" class="rounded-lg border border-input bg-card p-3">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.saveSection') }}
        </h2>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="scope in (['company', 'machine'] as const)"
            :key="scope"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="scope === saveScope ? 'border-primary bg-primary/10 text-primary' : 'border-input hover:bg-muted'"
            @click="saveScope = scope"
          >
            {{ t(`print.saveScope.${scope}`) }}
          </button>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <button
            type="button"
            class="inline-flex h-9 flex-1 items-center justify-center gap-2 rounded-md border border-input text-sm transition-colors hover:bg-muted"
            :disabled="saving"
            @click="save"
          >
            <IconDeviceFloppy :size="16" />
            {{ savedAt ? t('print.saved') : t('print.save') }}
          </button>
          <button
            type="button"
            class="inline-flex h-9 items-center justify-center gap-1.5 rounded-md border border-input px-3 text-sm transition-colors hover:bg-muted"
            @click="resetToDefaults"
          >
            <IconRotate :size="16" />
            {{ t('print.resetAll') }}
          </button>
        </div>
        <p v-if="saveError" class="mt-1 text-xs text-destructive">{{ saveError }}</p>
        <p class="mt-1 text-xs text-muted-foreground">{{ t(`print.saveState.${scopeInUse}`) }}</p>
      </section>

      <section v-if="print.machines.value.length > 1">
        <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {{ t('print.moreMachines') }}
        </h2>
        <p class="mb-1 text-xs text-muted-foreground">{{ t('print.moreMachinesHint') }}</p>
        <label
          v-for="m in print.machines.value"
          :key="m.id"
          class="flex items-center gap-2 py-0.5 text-sm"
          :class="{ 'text-muted-foreground': m.id === machineId }"
        >
          <input
            type="checkbox"
            :checked="selectedIds.includes(m.id)"
            :disabled="m.id === machineId"
            @change="toggleMachine(m.id)"
          >
          <span>{{ m.name || t('print.fallbackMachineName') }}</span>
        </label>
      </section>

      <button
        type="button"
        class="inline-flex h-10 items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
        :disabled="!sheets.length"
        @click="doPrint"
      >
        <IconPrinter :size="18" />
        {{ t('print.print', pages.length) }}
      </button>
      <p class="text-xs leading-snug text-muted-foreground">{{ t('print.browserHint') }}</p>
    </aside>

    <main class="flex flex-1 flex-col items-center gap-5">
      <div
        v-if="!print.originIsPublic.value"
        class="no-print flex w-full max-w-[460px] gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2.5 text-sm leading-normal text-destructive"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.originWarnTitle') }}</strong>
          <p>{{ t('print.originWarnBody', { origin: print.publicOrigin.value || '—' }) }}</p>
        </div>
      </div>

      <div
        v-if="missingFields.length"
        class="no-print flex w-full max-w-[460px] gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm leading-normal text-amber-700 dark:text-amber-400"
      >
        <IconAlertTriangle :size="18" class="mt-0.5 flex-none" />
        <div>
          <strong class="block">{{ t('print.missingTitle') }}</strong>
          <p>{{ missingFields.map(f => t(`print.missingFields.${f}`)).join(' · ') }}</p>
          <NuxtLink to="/settings" class="mt-1 inline-block underline">{{ t('print.missingLink') }}</NuxtLink>
        </div>
      </div>

      <div v-if="building && !sheets.length" class="no-print p-12 text-muted-foreground">
        <IconLoader2 :size="20" class="spin" />
      </div>

      <div v-for="(page, i) in pages" :key="i" class="preview" :style="previewStyle">
        <div class="sheet" :style="sheetStyle">
          <StickerSheet
            v-if="isSticker"
            :sheets="page"
            :motif="motif.component"
            :t="posterT"
          />
          <component
            :is="motif.component"
            v-else-if="page[0]"
            :sheet="page[0]"
            :t="posterT"
          />
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.preview { position: relative; overflow: hidden; }

/* The sheet is paper: it stays white/dark by its motif, never by the app theme. */
.sheet {
  box-sizing: border-box;
  background: #fff;
  color: #14110f;
  overflow: hidden;
  print-color-adjust: exact;
  -webkit-print-color-adjust: exact;
}

@media screen {
  .sheet {
    transform: scale(var(--preview-scale));
    transform-origin: top left;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.18);
  }
}

@media print {
  .no-print { display: none !important; }
  .print-page { display: block; padding: 0; background: #fff; min-height: 0; }
  .preview {
    width: auto !important;
    height: auto !important;
    overflow: visible;
  }
  .sheet {
    transform: none;
    box-shadow: none;
    page-break-after: always;
    break-after: page;
  }
  /* Without this the last sheet pushes out a trailing blank page. */
  .preview:last-child .sheet {
    page-break-after: auto;
    break-after: auto;
  }
}
</style>
