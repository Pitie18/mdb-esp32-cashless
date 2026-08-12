<script setup lang="ts">
import { IconBuildingStore, IconCalendar, IconCategory, IconCheck } from '@tabler/icons-vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { RangePreset } from '~/lib/analytics'

const { t } = useI18n()
const {
  preset, customFrom, customTo, machineIds, categoryIds,
  machines, categories, rangeLabel, loadAll,
} = useAnalytics()

const presets: RangePreset[] = ['days7', 'days30', 'days90', 'thisMonth', 'lastMonth']

const machineLabel = computed(() => {
  if (!machineIds.value.length) return t('analytics.allMachines')
  if (machineIds.value.length === 1) {
    return machines.value.find(x => x.id === machineIds.value[0])?.name || t('analytics.allMachines')
  }
  return t('analytics.nMachines', machineIds.value.length)
})

const categoryLabel = computed(() => {
  if (!categoryIds.value.length) return t('analytics.allCategories')
  if (categoryIds.value.length === 1) {
    return categories.value.find(x => x.id === categoryIds.value[0])?.name
      || t('analytics.allCategories')
  }
  return t('analytics.nCategories', categoryIds.value.length)
})

function toggle(list: string[], id: string) {
  const i = list.indexOf(id)
  if (i >= 0) list.splice(i, 1)
  else list.push(id)
}

function applyPreset(p: RangePreset) {
  preset.value = p
  loadAll()
}

function applyCustom() {
  if (!customFrom.value || !customTo.value) return
  preset.value = 'custom'
  loadAll()
}

/** Multi-selects reload on close, so picking four machines costs one round
 *  trip rather than four. */
function onMenuToggle(open: boolean) {
  if (!open) loadAll()
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconCalendar class="size-4" />
          {{ rangeLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="w-64">
        <DropdownMenuItem v-for="p in presets" :key="p" @click="applyPreset(p)">
          {{ t(`analytics.${p}`) }}
          <IconCheck v-if="preset === p" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <div class="space-y-2 p-2">
          <p class="text-muted-foreground text-xs">{{ t('analytics.custom') }}</p>
          <input
            v-model="customFrom" type="date" :aria-label="t('analytics.from')"
            class="w-full rounded border px-2 py-1 text-sm"
          >
          <input
            v-model="customTo" type="date" :aria-label="t('analytics.to')"
            class="w-full rounded border px-2 py-1 text-sm"
          >
          <Button size="sm" class="w-full" @click="applyCustom">{{ t('analytics.apply') }}</Button>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>

    <DropdownMenu @update:open="onMenuToggle">
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconBuildingStore class="size-4" />
          {{ machineLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="max-h-80 w-64 overflow-y-auto">
        <DropdownMenuItem @select.prevent="machineIds = []">
          {{ t('analytics.allMachines') }}
          <IconCheck v-if="!machineIds.length" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          v-for="m in machines" :key="m.id"
          @select.prevent="toggle(machineIds, m.id)"
        >
          {{ m.name }}
          <IconCheck v-if="machineIds.includes(m.id)" class="ml-auto size-4" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>

    <DropdownMenu @update:open="onMenuToggle">
      <DropdownMenuTrigger as-child>
        <Button variant="outline" size="sm" class="gap-2">
          <IconCategory class="size-4" />
          {{ categoryLabel }}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" class="max-h-80 w-64 overflow-y-auto">
        <DropdownMenuItem @select.prevent="categoryIds = []">
          {{ t('analytics.allCategories') }}
          <IconCheck v-if="!categoryIds.length" class="ml-auto size-4" />
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          v-for="c in categories" :key="c.id"
          @select.prevent="toggle(categoryIds, c.id)"
        >
          {{ c.name }}
          <IconCheck v-if="categoryIds.includes(c.id)" class="ml-auto size-4" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
</template>
