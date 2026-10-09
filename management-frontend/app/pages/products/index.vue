<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatCurrency } from '@/lib/utils'

const { t, locale } = useI18n()
const { organization, role } = useOrganization()
const { products, categories, loading, fetchProducts, deleteProduct, createCategory, updateCategory, deleteCategory } = useProducts()
const { taxClasses, fetchTaxClasses, formatTaxClassLabel, categoriesWithoutTax } = useTaxSettings()
const { fetchBarcodes } = useWarehouse()
const {
  products: importProducts,
  parsing: importParsing,
  importing: importRunning,
  parseError: importParseError,
  importResult,
  parseFile: importParseFile,
  executeImport: importExecute,
  toggleAll: importToggleAll,
  reset: importReset,
  selectedCount: importSelectedCount,
  allSelected: importAllSelected,
} = useImportProducts()

const isAdmin = computed(() => role.value === 'admin')

import { fuzzyFilter } from '@/lib/fuzzySearch'
import { marginNet, type PurchaseSummary } from '~/lib/purchaseComparison'
const { fetchSummaries } = usePurchasePrices()
const ekSummaries = ref<Record<string, PurchaseSummary>>({})

async function loadEkSummaries() {
  const ids = products.value.map(p => p.id)
  ekSummaries.value = await fetchSummaries(ids)
}

const productSearch = ref('')
const { sortKey: prodSortKey, sortDir: prodSortDir, toggleSort: toggleProdSort, sortIcon: prodSortIcon } = useTableSort<'name' | 'category' | 'price' | 'ek'>('name')

const sortedProducts = computed(() => {
  const filtered = fuzzyFilter(products.value, productSearch.value, [
    p => p.name,
    p => p.category_name,
  ])
  const dir = prodSortDir.value === 'asc' ? 1 : -1
  return [...filtered].sort((a, b) => {
    if (prodSortKey.value === 'name') return dir * (a.name ?? '').localeCompare(b.name ?? '')
    if (prodSortKey.value === 'category') return dir * (a.category_name ?? '').localeCompare(b.category_name ?? '')
    if (prodSortKey.value === 'ek') {
      const ag = ekSummaries.value[a.id]?.newest_gross ?? -1
      const bg = ekSummaries.value[b.id]?.newest_gross ?? -1
      return dir * (ag - bg)
    }
    return dir * ((a.sellprice ?? 0) - (b.sellprice ?? 0))
  })
})

usePullToRefresh(() => Promise.all([fetchProducts(), fetchBarcodes()]).then(() => {}))

onMounted(async () => {
  await Promise.all([fetchProducts(), fetchBarcodes(), fetchTaxClasses()]).then(loadEkSummaries)
})

// Product modal state
const showProductModal = ref(false)
const editingProduct = ref<any>(null)

function openAddProduct() {
  editingProduct.value = null
  showProductModal.value = true
}

function openEditProduct(product: any) {
  editingProduct.value = product
  showProductModal.value = true
}

function onProductSaved(_id: string) {
  fetchProducts().then(loadEkSummaries)
}

async function handleDeleteProduct(id: string) {
  try {
    await deleteProduct(id)
  } catch (err: unknown) {
    // silent
  }
}

// Category modal state
const {
  open: showCategoryModal,
  form: categoryForm,
  loading: categoryLoading,
  error: categoryError,
  openModal: openCategoryModal,
  submit: submitCategoryForm,
} = useModalForm({ name: '', tax_class_id: '', min_age: '' })

const categoryName = computed({
  get: () => categoryForm.value.name,
  set: (v: string) => { categoryForm.value.name = v },
})

const categoryTaxClassId = computed({
  get: () => categoryForm.value.tax_class_id,
  set: (v: string) => { categoryForm.value.tax_class_id = v },
})

const categoryMinAge = computed({
  get: () => categoryForm.value.min_age,
  set: (v: string) => { categoryForm.value.min_age = v },
})
const AGE_OPTIONS = [16, 18]

const editingCategory = ref<{ id: string } | null>(null)

function openAddCategory() {
  editingCategory.value = null
  openCategoryModal({ name: '', tax_class_id: '', min_age: '' })
}

function openEditCategory(cat: { id: string; name: string; tax_class_id: string | null; min_age: number | null }) {
  editingCategory.value = { id: cat.id }
  openCategoryModal({ name: cat.name, tax_class_id: cat.tax_class_id ?? '', min_age: cat.min_age != null ? String(cat.min_age) : '' })
}

async function submitCategory() {
  if (!categoryForm.value.name.trim()) {
    categoryError.value = t('products.nameRequired')
    return
  }
  await submitCategoryForm(async () => {
    if (editingCategory.value) {
      await updateCategory(editingCategory.value.id, {
        name: categoryForm.value.name.trim(),
        tax_class_id: categoryForm.value.tax_class_id || null,
        min_age: categoryForm.value.min_age ? Number(categoryForm.value.min_age) : null,
      })
    } else {
      await createCategory({
        name: categoryForm.value.name.trim(),
        company: organization.value!.id,
        tax_class_id: categoryForm.value.tax_class_id || null,
        min_age: categoryForm.value.min_age ? Number(categoryForm.value.min_age) : null,
      })
    }
  })
}

async function handleDeleteCategory(id: string) {
  try {
    await deleteCategory(id)
  } catch (err: unknown) {
    // silent
  }
}

// Fullscreen image preview
const fullscreenImage = ref<{ url: string; name: string } | null>(null)

// ── Import modal ────────────────────────────────────────────────────────────
const showImportModal = ref(false)
const importStep = ref<1 | 2 | 3>(1)

function openImportModal() {
  importStep.value = 1
  importReset()
  showImportModal.value = true
}

function closeImportModal() {
  showImportModal.value = false
  if (importStep.value === 3 && importResult.value && importResult.value.created > 0) {
    fetchProducts()
  }
}

async function onImportFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await importParseFile(file)
  if (importProducts.value.length > 0) {
    importStep.value = 2
  }
}

async function onImportFileDrop(event: DragEvent) {
  event.preventDefault()
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  await importParseFile(file)
  if (importProducts.value.length > 0) {
    importStep.value = 2
  }
}

// Bulk price helpers
const bulkPrice = ref<number | null>(null)
const emptyPriceCount = computed(() => importProducts.value.filter(p => p.selected && !p.sellprice).length)

function applyBulkPrice() {
  if (!bulkPrice.value) return
  for (const p of importProducts.value) {
    if (p.selected && !p.sellprice) {
      p.sellprice = bulkPrice.value
    }
  }
}

async function runImport() {
  await importExecute()
  importStep.value = 3
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-6 p-4 md:p-6">
        <h1 class="text-2xl font-semibold">{{ t('products.title') }}</h1>

        <div v-if="loading" class="text-muted-foreground">{{ t('common.loading') }}</div>

        <!-- Tax class warning banner -->
        <div
          v-if="!loading && categoriesWithoutTax > 0"
          class="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200"
        >
          {{ t('products.taxClassWarningBanner', { count: categoriesWithoutTax }) }}
        </div>

        <Tabs v-if="!loading" default-value="products">
          <TabsList>
            <TabsTrigger value="products">{{ t('products.productsTab') }}</TabsTrigger>
            <TabsTrigger value="categories">{{ t('products.categoriesTab') }}</TabsTrigger>
          </TabsList>

          <!-- Products tab -->
          <TabsContent value="products" class="mt-4">
            <div class="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <h2 class="text-base font-medium">{{ t('products.allProducts') }}</h2>
              <div v-if="isAdmin" class="flex gap-2">
                <button
                  class="inline-flex h-9 items-center justify-center rounded-md border px-4 text-sm font-medium transition-colors hover:bg-muted"
                  @click="openImportModal"
                >
                  {{ t('common.import') }}
                </button>
                <button
                  class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90"
                  @click="openAddProduct"
                >
                  {{ t('products.addProduct') }}
                </button>
              </div>
            </div>

            <SearchInput v-model="productSearch" :placeholder="t('common.search') + '...'" class="max-w-xs" />

            <div v-if="sortedProducts.length === 0" class="text-sm text-muted-foreground">{{ productSearch ? t('common.noResults') : t('products.noProducts') }}</div>
            <div v-else class="overflow-x-auto rounded-md border">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="w-[72px] min-w-[72px] px-4 py-3 font-medium"></th>
                    <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleProdSort('name')">
                      <SortHeader :icon="prodSortIcon('name')">{{ t('common.name') }}</SortHeader>
                    </th>
                    <th class="hidden sm:table-cell px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleProdSort('category')">
                      <SortHeader :icon="prodSortIcon('category')">{{ t('products.category') }}</SortHeader>
                    </th>
                    <th class="px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleProdSort('price')">
                      <SortHeader :icon="prodSortIcon('price')" align="right">{{ t('products.price') }}</SortHeader>
                    </th>
                    <th class="hidden md:table-cell px-4 py-3 font-medium cursor-pointer select-none hover:text-foreground" @click="toggleProdSort('ek')">
                      <SortHeader :icon="prodSortIcon('ek')" align="right">{{ t('purchasePrices.ekColumn') }}</SortHeader>
                    </th>
                    <th v-if="isAdmin" class="hidden sm:table-cell px-4 py-3 font-medium">{{ t('common.actions') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="product in sortedProducts"
                    :key="product.id"
                    class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                    :class="{ 'cursor-pointer': isAdmin }"
                    @click="isAdmin && openEditProduct(product)"
                  >
                    <td class="px-4 py-2">
                      <button
                        v-if="product.image_url"
                        type="button"
                        class="block"
                        @click.stop="fullscreenImage = { url: product.image_url, name: product.name }"
                      >
                        <img
                          :src="product.image_url"
                          :alt="product.name"
                          class="h-10 w-10 rounded-md object-cover"
                        />
                      </button>
                      <div
                        v-else
                        class="flex h-10 w-10 items-center justify-center rounded-md bg-muted"
                      >
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-muted-foreground/50" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>
                      </div>
                    </td>
                    <td class="px-4 py-3 font-medium max-w-[150px] truncate">
                      <NuxtLink
                        :to="`/products/${product.id}`"
                        class="text-left hover:underline"
                        @click.stop
                      >
                        {{ product.name }}
                      </NuxtLink>
                    </td>
                    <td class="hidden sm:table-cell px-4 py-3 text-muted-foreground">{{ product.category_name ?? '—' }}</td>
                    <td class="px-4 py-3">{{ formatCurrency(product.sellprice, locale) }}</td>
                    <td class="hidden md:table-cell px-4 py-3 text-right text-xs">
                      <template v-if="ekSummaries[product.id]?.newest_gross != null">
                        {{ formatCurrency(ekSummaries[product.id]!.newest_gross!, locale) }}
                        <span v-if="marginNet(product.sellprice, ekSummaries[product.id]!.newest_net, ekSummaries[product.id]!.effective_tax_rate)" class="block text-muted-foreground">
                          {{ marginNet(product.sellprice, ekSummaries[product.id]!.newest_net, ekSummaries[product.id]!.effective_tax_rate)!.spannePct.toFixed(0) }}%
                        </span>
                      </template>
                      <span v-else class="text-muted-foreground">—</span>
                    </td>
                    <td v-if="isAdmin" class="hidden sm:table-cell px-4 py-3">
                      <div class="flex items-center gap-2">
                        <button
                          class="text-xs text-primary hover:underline"
                          @click.stop="openEditProduct(product)"
                        >
                          {{ t('common.edit') }}
                        </button>
                        <button
                          class="text-xs text-destructive hover:underline"
                          @click.stop="handleDeleteProduct(product.id)"
                        >
                          {{ t('common.delete') }}
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </TabsContent>

          <!-- Categories tab -->
          <TabsContent value="categories" class="mt-4">
            <div class="mb-4 flex items-center justify-between">
              <h2 class="text-base font-medium">{{ t('products.allCategories') }}</h2>
              <button
                v-if="isAdmin"
                class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90"
                @click="openAddCategory"
              >
                {{ t('products.addCategory') }}
              </button>
            </div>

            <div v-if="categories.length === 0" class="text-sm text-muted-foreground">{{ t('products.noCategories') }}</div>
            <div v-else class="overflow-x-auto rounded-md border">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="px-4 py-3 font-medium">{{ t('common.name') }}</th>
                    <th class="px-4 py-3 font-medium">{{ t('products.taxClass') }}</th>
                    <th class="px-4 py-3 font-medium">{{ t('products.minAge') }}</th>
                    <th v-if="isAdmin" class="px-4 py-3 font-medium">{{ t('common.actions') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="cat in categories"
                    :key="cat.id"
                    class="border-b last:border-0 hover:bg-muted/30 transition-colors"
                  >
                    <td class="px-4 py-3 font-medium">{{ cat.name }}</td>
                    <td class="px-4 py-3">
                      <span v-if="cat.tax_class_id" class="text-sm">
                        {{ taxClasses.find(tc => tc.id === cat.tax_class_id) ? formatTaxClassLabel(taxClasses.find(tc => tc.id === cat.tax_class_id)!) : '—' }}
                      </span>
                      <span v-else class="text-xs text-amber-600 dark:text-amber-400">
                        {{ t('products.noTaxClassWarning') }}
                      </span>
                    </td>
                    <td class="px-4 py-3">
                      <span v-if="cat.min_age" class="rounded bg-red-500/15 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:text-red-400">{{ t('refillRebuild.ageFrom', { n: cat.min_age }) }}</span>
                      <span v-else class="text-muted-foreground">—</span>
                    </td>
                    <td v-if="isAdmin" class="px-4 py-3">
                      <div class="flex items-center gap-2">
                        <button
                          class="text-xs text-primary hover:underline"
                          @click="openEditCategory(cat)"
                        >
                          {{ t('common.edit') }}
                        </button>
                        <button
                          class="text-xs text-destructive hover:underline"
                          @click="handleDeleteCategory(cat.id)"
                        >
                          {{ t('common.delete') }}
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </TabsContent>
        </Tabs>
      </div>

      <!-- Fullscreen image overlay -->
      <div
        v-if="fullscreenImage"
        class="fixed inset-0 z-[80] flex items-center justify-center bg-black/80"
        @click="fullscreenImage = null"
      >
        <button
          type="button"
          class="absolute right-4 top-4 flex size-8 items-center justify-center rounded-full bg-white/10 text-white hover:bg-white/20"
          @click="fullscreenImage = null"
        >
          &times;
        </button>
        <img
          :src="fullscreenImage.url"
          :alt="fullscreenImage.name"
          class="max-h-[85vh] max-w-[90vw] rounded-lg object-contain"
        />
      </div>

      <!-- Product modal -->
      <ProductFormModal
        v-model:open="showProductModal"
        :product-id="editingProduct?.id ?? null"
        @saved="onProductSaved"
      />

      <!-- Category modal -->
      <AppModal
        v-model:open="showCategoryModal"
        :title="editingCategory ? t('common.edit') + ' ' + t('products.category') : t('products.addCategory')"
        size="sm"
      >
          <form class="space-y-4" @submit.prevent="submitCategory">
            <div class="space-y-1">
              <label class="text-sm font-medium" for="category-name">{{ t('common.name') }}</label>
              <input
                id="category-name"
                v-model="categoryName"
                type="text"
                required
                :placeholder="t('products.categoryName')"
                class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              />
            </div>
            <div class="space-y-1">
              <label class="text-sm font-medium" for="category-tax-class">{{ t('products.taxClass') }}</label>
              <select
                id="category-tax-class"
                v-model="categoryTaxClassId"
                class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="">— {{ t('products.taxClass') }} —</option>
                <option v-for="tc in taxClasses" :key="tc.id" :value="tc.id">
                  {{ formatTaxClassLabel(tc) }}
                </option>
              </select>
            </div>
            <div class="space-y-1">
              <label class="text-sm font-medium" for="category-min-age">{{ t('products.minAge') }}</label>
              <select
                id="category-min-age"
                v-model="categoryMinAge"
                class="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="">{{ t('refillRebuild.noAgeLimit') }}</option>
                <option v-for="a in AGE_OPTIONS" :key="a" :value="String(a)">{{ t('refillRebuild.ageFrom', { n: a }) }}</option>
              </select>
              <p class="text-xs text-muted-foreground">{{ t('products.minAgeHint') }}</p>
            </div>
            <FormError :message="categoryError" />
            <div class="flex gap-2">
              <button
                type="button"
                class="inline-flex h-9 flex-1 items-center justify-center rounded-md border px-4 text-sm font-medium shadow-sm transition-colors hover:bg-muted"
                @click="showCategoryModal = false"
              >
                {{ t('common.cancel') }}
              </button>
              <button
                type="submit"
                :disabled="categoryLoading"
                class="inline-flex h-9 flex-1 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90 disabled:opacity-50"
              >
                <span v-if="categoryLoading">{{ t('common.saving') }}</span>
                <span v-else>{{ t('common.save') }}</span>
              </button>
            </div>
          </form>
      </AppModal>

      <!-- Import modal -->
      <AppModal
        v-model:open="showImportModal"
        :title="importStep === 1 ? t('products.importProducts') : importStep === 2 ? t('products.reviewProducts') : t('products.importComplete')"
        size="xl"
        @update:open="!$event && closeImportModal()"
      >
        <div class="flex flex-col max-h-[70vh]">

          <!-- Step 1: File upload -->
          <template v-if="importStep === 1">
            <p class="mb-5 text-sm text-muted-foreground">
              {{ t('products.importDescription') }}
            </p>

            <label
              class="flex h-40 w-full cursor-pointer items-center justify-center rounded-lg border-2 border-dashed border-muted-foreground/25 text-muted-foreground transition-colors hover:border-primary/50 hover:bg-primary/5"
              @dragover.prevent
              @drop="onImportFileDrop"
            >
              <div class="text-center">
                <svg xmlns="http://www.w3.org/2000/svg" class="mx-auto mb-2 h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/></svg>
                <span v-if="importParsing" class="text-sm">{{ t('products.parsingFile') }}</span>
                <template v-else>
                  <span class="text-sm font-medium">{{ t('products.dropFile') }}</span>
                  <span class="mt-1 block text-xs">{{ t('products.supportsNayax') }}</span>
                </template>
              </div>
              <input
                type="file"
                accept=".xlsx,.xls"
                class="hidden"
                @change="onImportFileSelected"
              />
            </label>

            <FormError :message="importParseError" class="mt-3" />

            <div class="mt-5 flex justify-end">
              <button
                class="inline-flex h-9 items-center justify-center rounded-md border px-4 text-sm font-medium hover:bg-muted"
                @click="closeImportModal"
              >
                {{ t('common.cancel') }}
              </button>
            </div>
          </template>

          <!-- Step 2: Preview table -->
          <template v-else-if="importStep === 2">
            <div class="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p class="text-sm text-muted-foreground">
                  {{ t('products.selectedCount', { selected: importSelectedCount, total: importProducts.length }) }}
                  <template v-if="emptyPriceCount > 0">
                    · <span class="text-yellow-600">{{ t('products.withoutPrice', { count: emptyPriceCount }) }}</span>
                  </template>
                </p>
              </div>
              <div v-if="emptyPriceCount > 0" class="flex items-center gap-2">
                <input
                  v-model.number="bulkPrice"
                  type="number"
                  step="0.01"
                  min="0"
                  :placeholder="t('products.price')"
                  class="h-8 w-24 rounded-md border border-input bg-background px-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                />
                <button
                  class="inline-flex h-8 items-center justify-center rounded-md border px-3 text-xs font-medium hover:bg-muted"
                  :disabled="!bulkPrice"
                  @click="applyBulkPrice"
                >
                  {{ t('products.fillEmptyPrices') }}
                </button>
              </div>
            </div>

            <div class="flex-1 overflow-auto rounded-md border min-h-0">
              <table class="w-full text-sm">
                <thead class="sticky top-0 bg-card z-10">
                  <tr class="border-b bg-muted/50 text-left">
                    <th class="w-10 px-3 py-2">
                      <input
                        type="checkbox"
                        :checked="importAllSelected"
                        class="rounded"
                        @change="importToggleAll(($event.target as HTMLInputElement).checked)"
                      />
                    </th>
                    <th class="px-3 py-2 font-medium">{{ t('common.name') }}</th>
                    <th class="px-3 py-2 font-medium">{{ t('products.category') }}</th>
                    <th class="px-3 py-2 font-medium">{{ t('products.price') }}</th>
                    <th class="w-16 px-3 py-2 font-medium text-center">{{ t('products.image') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(p, i) in importProducts"
                    :key="i"
                    class="border-b last:border-0 transition-colors"
                    :class="p.selected ? 'hover:bg-muted/30' : 'opacity-40'"
                  >
                    <td class="px-3 py-2">
                      <input
                        v-model="p.selected"
                        type="checkbox"
                        class="rounded"
                      />
                    </td>
                    <td class="px-3 py-2 font-medium">{{ p.name }}</td>
                    <td class="px-3 py-2 text-muted-foreground">{{ p.category_name ?? '—' }}</td>
                    <td class="px-3 py-1">
                      <input
                        v-model.number="p.sellprice"
                        type="number"
                        step="0.01"
                        min="0"
                        placeholder="—"
                        class="h-7 w-20 rounded border border-input bg-background px-2 text-sm tabular-nums transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                        :class="!p.sellprice ? 'border-yellow-500/50' : ''"
                      />
                    </td>
                    <td class="px-3 py-2 text-center">
                      <span v-if="p.image_url" class="inline-flex h-5 w-5 items-center justify-center rounded-full bg-green-500/10 text-green-600">
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                      </span>
                      <span v-else class="text-xs text-muted-foreground">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="mt-4 flex gap-2">
              <button
                class="inline-flex h-9 items-center justify-center rounded-md border px-4 text-sm font-medium hover:bg-muted"
                @click="closeImportModal"
              >
                {{ t('common.cancel') }}
              </button>
              <button
                class="inline-flex h-9 items-center justify-center rounded-md border px-4 text-sm font-medium hover:bg-muted"
                @click="importStep = 1; importReset()"
              >
                {{ t('common.back') }}
              </button>
              <div class="flex-1" />
              <button
                :disabled="importSelectedCount === 0 || importRunning"
                class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90 disabled:opacity-50"
                @click="runImport"
              >
                <template v-if="importRunning">
                  <svg class="mr-2 h-4 w-4 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/></svg>
                  {{ t('products.importing') }}
                </template>
                <template v-else>
                  {{ t('common.import') }} {{ importSelectedCount }} {{ t('products.productsTab').toLowerCase() }}
                </template>
              </button>
            </div>
          </template>

          <!-- Step 3: Results -->
          <template v-else>
            <div class="space-y-3">
              <div class="flex items-center gap-3 rounded-lg border p-4">
                <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-500/10">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-green-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                </div>
                <div>
                  <p class="font-medium">{{ t('products.productsCreated', { count: importResult?.created ?? 0 }) }}</p>
                  <p v-if="(importResult?.skipped ?? 0) > 0" class="text-sm text-muted-foreground">
                    {{ t('products.skipped', { count: importResult?.skipped }) }}
                  </p>
                </div>
              </div>

              <div v-if="(importResult?.image_errors ?? 0) > 0" class="flex items-center gap-3 rounded-lg border border-yellow-500/30 p-4">
                <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-yellow-500/10">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-yellow-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" x2="12" y1="9" y2="13"/><line x1="12" x2="12.01" y1="17" y2="17"/></svg>
                </div>
                <div>
                  <p class="font-medium">{{ t('products.imageErrors', { count: importResult?.image_errors }) }}</p>
                  <p class="text-sm text-muted-foreground">{{ t('products.productsWithoutImages') }}</p>
                </div>
              </div>

              <details v-if="importResult?.errors?.length" class="rounded-lg border p-4">
                <summary class="cursor-pointer text-sm font-medium text-muted-foreground">
                  {{ t('products.showErrorDetails', { count: importResult.errors.length }) }}
                </summary>
                <ul class="mt-2 max-h-40 space-y-1 overflow-auto text-xs text-muted-foreground">
                  <li v-for="(err, i) in importResult.errors" :key="i">{{ err }}</li>
                </ul>
              </details>
            </div>

            <div class="mt-5 flex justify-end">
              <button
                class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90"
                @click="closeImportModal"
              >
                {{ t('common.done') }}
              </button>
            </div>
          </template>
        </div>
      </AppModal>
</template>
