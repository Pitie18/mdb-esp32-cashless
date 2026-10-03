<script setup lang="ts">
import { getProductImageUrl } from '@/composables/useProducts'

definePageMeta({ layout: false })

const { t } = useI18n()
const route = useRoute()
const machineId = route.params.machine_id as string

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const isValidUuid = UUID_RE.test(machineId)

interface Product {
  id: string
  name: string
  slot: number
  price: number | null
  stock: number
  capacity: number
  image_path: string | null
  available: boolean
}

interface Category {
  name: string
  products: Product[]
}

interface Imprint {
  legal_name: string | null
  contact_email: string | null
  contact_phone: string | null
  website: string | null
  address_street: string | null
  address_house_number: string | null
  address_postal_code: string | null
  address_city: string | null
  country_code: string | null
}

interface MachineData {
  machine: { name: string; location_lat: number | null; location_lon: number | null }
  machine_id: string
  company_id: string
  company_name: string | null
  imprint: Imprint | null
  status: string | null
  status_at: string | null
  categories: Category[]
  payment_enabled: boolean
}

const { data, error, refresh } = isValidUuid
  ? await useFetch<MachineData>('/functions/v1/public-machine-data', {
      params: { id: machineId },
    })
  : { data: ref(null), error: ref(new Error('Invalid machine ID')), refresh: () => Promise.resolve() }

// --- Notify modal state ---
const notifyProductId = ref<string | null>(null)
const notifyProductName = ref('')
const notifyEmail = ref('')
const notifyLoading = ref(false)
const notifyDone = reactive(new Set<string>())
const notifyError = ref(false)

function openNotify(product: Product) {
  notifyProductId.value = product.id
  notifyProductName.value = product.name
  notifyEmail.value = ''
  notifyError.value = false
}

function closeNotify() {
  notifyProductId.value = null
}

async function subscribeRestock() {
  if (!notifyProductId.value || !notifyEmail.value) return
  notifyLoading.value = true
  notifyError.value = false
  try {
    await $fetch('/functions/v1/subscribe-restock', {
      method: 'POST',
      body: {
        machine_id: data.value!.machine_id,
        product_id: notifyProductId.value,
        email: notifyEmail.value,
      },
    })
    notifyDone.add(notifyProductId.value)
    closeNotify()
  } catch {
    notifyError.value = true
  } finally {
    notifyLoading.value = false
  }
}

// --- Wish modal state ---
const wishOpen = ref(false)
const wishText = ref('')
const wishEmail = ref('')
const wishLoading = ref(false)
const wishDone = ref(false)
const wishError = ref(false)

async function submitWish() {
  if (!wishText.value.trim()) return
  wishLoading.value = true
  wishError.value = false
  try {
    await $fetch('/functions/v1/submit-product-wish', {
      method: 'POST',
      body: {
        machine_id: data.value!.machine_id,
        wish_text: wishText.value.trim(),
        email: wishEmail.value || undefined,
      },
    })
    wishDone.value = true
    wishText.value = ''
    wishEmail.value = ''
    setTimeout(() => {
      wishDone.value = false
      wishOpen.value = false
    }, 2500)
  } catch {
    wishError.value = true
  } finally {
    wishLoading.value = false
  }
}

// --- Feedback modal (problem report + general feedback, unified) ---
// type="problem" → "Problem melden", type="feedback" → "Feedback geben"
type FeedbackType = 'problem' | 'feedback'
const feedbackType = ref<FeedbackType | null>(null)
const feedbackMessage = ref('')
const feedbackEmail = ref('')
const feedbackLoading = ref(false)
const feedbackDone = ref(false)
const feedbackError = ref(false)

function openFeedback(type: FeedbackType) {
  feedbackType.value = type
  feedbackMessage.value = ''
  feedbackEmail.value = ''
  feedbackDone.value = false
  feedbackError.value = false
}

function closeFeedback() {
  feedbackType.value = null
}

// Deep link from the printed "report a fault" sticker: /m/{id}?feedback=problem
// opens the form straight away instead of dropping the customer on the product
// list with a problem they still have to hunt a button for.
onMounted(() => {
  const requested = route.query.feedback
  if (requested === 'problem' || requested === 'feedback') {
    openFeedback(requested)
  }
})

async function submitFeedback() {
  if (!feedbackType.value || !feedbackMessage.value.trim()) return
  feedbackLoading.value = true
  feedbackError.value = false
  try {
    await $fetch('/functions/v1/submit-machine-feedback', {
      method: 'POST',
      body: {
        machine_id: data.value!.machine_id,
        type: feedbackType.value,
        message: feedbackMessage.value.trim(),
        email: feedbackEmail.value || undefined,
      },
    })
    feedbackDone.value = true
    setTimeout(() => {
      closeFeedback()
    }, 2000)
  } catch {
    feedbackError.value = true
  } finally {
    feedbackLoading.value = false
  }
}

// --- Imprint modal (Betreiberinformationen, read-only for customers) ---
const imprintOpen = ref(false)

// True only if at least one imprint field is populated — drives visibility of
// the "Betreiberinformationen" button so we don't show an empty modal.
const hasImprint = computed(() => {
  const i = data.value?.imprint
  if (!i) return false
  return !!(
    i.legal_name ||
    i.contact_email ||
    i.contact_phone ||
    i.website ||
    i.address_street ||
    i.address_postal_code ||
    i.address_city
  )
})

const imprintAddressLine = computed(() => {
  const i = data.value?.imprint
  if (!i) return ''
  const street = [i.address_street, i.address_house_number].filter(Boolean).join(' ')
  const city = [i.address_postal_code, i.address_city].filter(Boolean).join(' ')
  return [street, city].filter(Boolean).join(', ')
})

// --- Payment state ---
const selectedProduct = ref<Product | null>(null)
const paymentLoading = ref(false)
const paymentError = ref('')
const paymentSuccess = ref(false)
const confirmLoading = ref(false)
const paymentElementReady = ref(false)

let stripeInstance: any = null
let elementsInstance: any = null
let currentPaymentIntentId = ''

async function loadStripeJs(): Promise<any> {
  if ((window as any).Stripe) return (window as any).Stripe
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://js.stripe.com/v3/'
    script.onload = () => resolve((window as any).Stripe)
    script.onerror = () => reject(new Error('Failed to load Stripe.js'))
    document.head.appendChild(script)
  })
}

async function openPayment(product: Product) {
  selectedProduct.value = product
  paymentError.value = ''
  paymentSuccess.value = false
  paymentLoading.value = true
  paymentElementReady.value = false

  try {
    // 1. Create PaymentIntent
    const pi = await $fetch<{
      clientSecret: string
      paymentIntentId: string
      publishableKey: string
      amount: number
      currency: string
    }>('/functions/v1/create-payment-intent', {
      method: 'POST',
      body: {
        machine_id: machineId,
        product_id: product.id,
        slot: product.slot,
      },
    })

    currentPaymentIntentId = pi.paymentIntentId

    // 2. Load Stripe.js + init
    const StripeConstructor = await loadStripeJs()
    stripeInstance = StripeConstructor(pi.publishableKey)
    elementsInstance = stripeInstance.elements({
      clientSecret: pi.clientSecret,
      appearance: {
        theme: document.documentElement.classList.contains('dark') ? 'night' : 'stripe',
        variables: { borderRadius: '8px' },
      },
    })

    // 3. Mount Payment Element after DOM is ready
    await nextTick()
    const paymentElement = elementsInstance.create('payment')
    paymentElement.mount('#payment-element')
    paymentElement.on('ready', () => {
      paymentElementReady.value = true
    })
  } catch (err: any) {
    paymentError.value = err?.data?.error || err?.message || t('publicStorefront.paymentFailed')
  } finally {
    paymentLoading.value = false
  }
}

function closePayment() {
  selectedProduct.value = null
  paymentError.value = ''
  paymentSuccess.value = false
  paymentElementReady.value = false
  stripeInstance = null
  elementsInstance = null
  currentPaymentIntentId = ''
}

async function submitPayment() {
  if (!stripeInstance || !elementsInstance) return
  paymentError.value = ''
  confirmLoading.value = true

  try {
    // Confirm payment with Stripe
    const { error: stripeError, paymentIntent } = await stripeInstance.confirmPayment({
      elements: elementsInstance,
      confirmParams: { return_url: window.location.href },
      redirect: 'if_required',
    })

    if (stripeError) {
      paymentError.value = stripeError.message || t('publicStorefront.paymentFailed')
      return
    }

    if (paymentIntent?.status === 'succeeded') {
      // Deliver credit via confirm-payment edge function
      try {
        await $fetch('/functions/v1/confirm-payment', {
          method: 'POST',
          body: {
            payment_intent_id: paymentIntent.id,
            machine_id: machineId,
          },
        })
        paymentSuccess.value = true
      } catch {
        // Payment succeeded but credit delivery failed — webhook will handle it
        paymentSuccess.value = true
        paymentError.value = t('publicStorefront.creditDeliveryFailed')
      }
    }
  } catch (err: any) {
    paymentError.value = err?.message || t('publicStorefront.paymentFailed')
  } finally {
    confirmLoading.value = false
  }
}

// --- Helpers ---
function stockPercent(stock: number, capacity: number) {
  return capacity > 0 ? (stock / capacity) * 100 : 0
}

function stockBarColor(percent: number) {
  if (percent > 50) return 'bg-emerald-500'
  if (percent > 25) return 'bg-amber-500'
  return 'bg-red-500'
}

function formatPrice(price: number | null) {
  if (price === null || price === undefined) return '–'
  return price.toLocaleString('de-DE', { style: 'currency', currency: 'EUR' })
}

function mapsUrl(lat: number, lon: number) {
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lon}`
}

const isOnline = computed(() => data.value?.status === 'online')
</script>

<template>
  <div class="min-h-dvh bg-background text-foreground">
    <Head>
      <title>{{ data?.machine?.name || t('publicStorefront.loading') }}</title>
      <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    </Head>

    <!-- Loading -->
    <div v-if="!data && !error" class="flex min-h-dvh items-center justify-center">
      <div class="text-center">
        <div class="mx-auto mb-4 size-8 animate-spin rounded-full border-2 border-muted-foreground border-t-primary" />
        <p class="text-sm text-muted-foreground">{{ t('publicStorefront.loading') }}</p>
      </div>
    </div>

    <!-- Error / Not found -->
    <div v-else-if="error || !data" class="flex min-h-dvh items-center justify-center p-6">
      <div class="text-center">
        <div class="mx-auto mb-4 flex size-16 items-center justify-center rounded-full bg-muted">
          <svg class="size-8 text-muted-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.182 16.318A4.486 4.486 0 0012.016 15a4.486 4.486 0 00-3.198 1.318M21 12a9 9 0 11-18 0 9 9 0 0118 0zM9.75 9.75c0 .414-.168.75-.375.75S9 10.164 9 9.75 9.168 9 9.375 9s.375.336.375.75zm-.375 0h.008v.015h-.008V9.75zm5.625 0c0 .414-.168.75-.375.75s-.375-.336-.375-.75.168-.75.375-.75.375.336.375.75zm-.375 0h.008v.015h-.008V9.75z" />
          </svg>
        </div>
        <h2 class="mb-2 text-lg font-semibold">{{ t('publicStorefront.machineNotFound') }}</h2>
        <p class="mb-6 text-sm text-muted-foreground">{{ t('publicStorefront.machineNotFoundDesc') }}</p>
        <button
          class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          @click="refresh()"
        >
          {{ t('publicStorefront.retry') }}
        </button>
      </div>
    </div>

    <!-- Main content -->
    <div v-else class="mx-auto max-w-2xl px-4 py-6 pb-20">
      <!-- Back link to operator page -->
      <NuxtLink
        v-if="data.company_id && data.company_name"
        :to="`/m/o/${data.company_id}`"
        class="mb-4 inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        <svg class="size-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
        </svg>
        {{ t('publicStorefront.backToOperator', { operator: data.company_name }) }}
      </NuxtLink>

      <!-- Machine header -->
      <header class="mb-5">
        <h1 class="text-2xl font-bold tracking-tight">{{ data.machine.name }}</h1>
        <div class="mt-2 flex flex-wrap items-center gap-2 text-xs">
          <span
            class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 font-medium"
            :class="isOnline
              ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
              : 'bg-muted text-muted-foreground'"
          >
            <span class="size-1.5 rounded-full" :class="isOnline ? 'bg-emerald-500' : 'bg-muted-foreground'" />
            {{ isOnline ? t('publicStorefront.online') : t('publicStorefront.offline') }}
          </span>
          <span
            v-if="imprintAddressLine"
            class="inline-flex items-center gap-1.5 text-muted-foreground"
          >
            <svg class="size-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
            </svg>
            {{ imprintAddressLine }}
          </span>
        </div>
      </header>

      <!-- Action bar — Route + Problem + Feedback + Operator info + Wish -->
      <div class="mb-6 flex flex-wrap gap-2">
        <a
          v-if="data.machine.location_lat && data.machine.location_lon"
          :href="mapsUrl(data.machine.location_lat, data.machine.location_lon)"
          target="_blank"
          rel="noopener"
          class="inline-flex items-center gap-1.5 rounded-full bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground shadow-sm hover:bg-primary/90"
        >
          <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {{ t('publicStorefront.showRoute') }}
        </a>

        <button
          class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-2 text-sm font-medium text-card-foreground hover:bg-accent"
          @click="openFeedback('problem')"
        >
          <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
          </svg>
          {{ t('publicStorefront.reportProblem') }}
        </button>

        <button
          class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-2 text-sm font-medium text-card-foreground hover:bg-accent"
          @click="openFeedback('feedback')"
        >
          <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
          </svg>
          {{ t('publicStorefront.giveFeedback') }}
        </button>

        <button
          v-if="hasImprint"
          class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-2 text-sm font-medium text-card-foreground hover:bg-accent"
          @click="imprintOpen = true"
        >
          <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
            <path stroke-linecap="round" stroke-linejoin="round" d="M11.25 11.25l.041-.02a.75.75 0 011.063.852l-.708 2.836a.75.75 0 001.063.853l.041-.021M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9-3.75h.008v.008H12V8.25z" />
          </svg>
          {{ t('publicStorefront.operatorInfo') }}
        </button>

        <button
          class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-2 text-sm font-medium text-card-foreground hover:bg-accent"
          @click="wishOpen = true; wishDone = false; wishError = false"
        >
          <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 18v-5.25m0 0a6.01 6.01 0 001.5-.189m-1.5.189a6.01 6.01 0 01-1.5-.189m3.75 7.478a12.06 12.06 0 01-4.5 0m3.75 2.383a14.406 14.406 0 01-3 0M14.25 18v-.192c0-.983.658-1.823 1.508-2.316a7.5 7.5 0 10-7.517 0c.85.493 1.509 1.333 1.509 2.316V18" />
          </svg>
          {{ t('publicStorefront.productWish') }}
        </button>
      </div>

      <!-- No products -->
      <div v-if="data.categories.length === 0" class="py-12 text-center">
        <p class="text-muted-foreground">{{ t('publicStorefront.noProducts') }}</p>
      </div>

      <!-- Category sections -->
      <section v-for="category in data.categories" :key="category.name" class="mb-8">
        <h2 class="mb-3 text-lg font-semibold">
          {{ category.name }}
          <span class="text-sm font-normal text-muted-foreground">({{ category.products.length }})</span>
        </h2>
        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div
            v-for="product in category.products"
            :key="product.id"
            class="rounded-xl border border-border bg-card p-4"
          >
            <!-- Product image + info -->
            <div class="mb-3 flex items-start gap-3">
              <img
                v-if="product.image_path"
                :src="getProductImageUrl(product.image_path)"
                :alt="product.name"
                class="size-12 shrink-0 rounded-lg object-cover"
              />
              <div
                v-else
                class="flex size-12 shrink-0 items-center justify-center rounded-lg bg-muted"
              >
                <svg class="size-6 text-muted-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M20.25 7.5l-.625 10.632a2.25 2.25 0 01-2.247 2.118H6.622a2.25 2.25 0 01-2.247-2.118L3.75 7.5M10 11.25h4M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125z" />
                </svg>
              </div>
              <div class="min-w-0 flex-1">
                <div class="flex items-start justify-between gap-2">
                  <h3 class="text-sm font-semibold leading-tight">{{ product.name }}</h3>
                  <span
                    class="shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
                    :class="product.available
                      ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                      : 'bg-red-500/15 text-red-600 dark:text-red-400'"
                  >
                    {{ product.available ? t('publicStorefront.available') : t('publicStorefront.soldOut') }}
                  </span>
                </div>
                <p class="mt-0.5 text-xs text-muted-foreground">
                  {{ t('publicStorefront.slot', { slot: product.slot }) }}
                </p>
              </div>
            </div>

            <!-- Price + stock -->
            <div class="mb-2 flex items-end justify-between">
              <span class="text-xs text-muted-foreground">
                {{ t('publicStorefront.stock') }}: {{ product.stock }} / {{ product.capacity }}
              </span>
              <span class="text-lg font-bold tabular-nums">{{ formatPrice(product.price) }}</span>
            </div>

            <!-- Stock bar -->
            <div class="h-1.5 overflow-hidden rounded-full bg-muted">
              <div
                class="h-full rounded-full transition-all duration-300"
                :class="stockBarColor(stockPercent(product.stock, product.capacity))"
                :style="{ width: `${Math.min(stockPercent(product.stock, product.capacity), 100)}%` }"
              />
            </div>

            <!-- Buy button (only when payment enabled + in stock) -->
            <button
              v-if="data.payment_enabled && product.available && product.price"
              class="mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg bg-primary py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              @click="openPayment(product)"
            >
              <svg class="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 8.25h19.5M2.25 9h19.5m-16.5 5.25h6m-6 2.25h3m-3.75 3h15a2.25 2.25 0 002.25-2.25V6.75A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25v10.5A2.25 2.25 0 004.5 19.5z" />
              </svg>
              {{ t('publicStorefront.buy') }} · {{ formatPrice(product.price) }}
            </button>

            <!-- Notify button for sold-out products -->
            <button
              v-if="!product.available && !notifyDone.has(product.id)"
              class="mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg border border-border py-2 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground"
              @click="openNotify(product)"
            >
              <svg class="size-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
              </svg>
              {{ t('publicStorefront.notifyMe') }}
            </button>
            <p
              v-if="!product.available && notifyDone.has(product.id)"
              class="mt-3 text-center text-xs text-emerald-600 dark:text-emerald-400"
            >
              {{ t('publicStorefront.notifySuccess') }}
            </p>
          </div>
        </div>
      </section>

      <!-- Footer -->
      <footer class="mt-12 border-t border-border pt-4 text-center text-xs text-muted-foreground">
        {{ t('publicStorefront.poweredBy') }} VMflow
      </footer>
    </div>

    <!-- Payment modal -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="selectedProduct"
          class="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
        >
          <div class="fixed inset-0 bg-black/50" @click="!confirmLoading && closePayment()" />
          <div class="relative w-full max-w-md rounded-t-2xl border border-border bg-card p-6 sm:rounded-2xl">
            <!-- Success state -->
            <div v-if="paymentSuccess" class="py-6 text-center">
              <div class="mx-auto mb-3 flex size-14 items-center justify-center rounded-full bg-emerald-500/15">
                <svg class="size-7 text-emerald-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                </svg>
              </div>
              <h3 class="mb-1 text-base font-semibold text-card-foreground">
                {{ t('publicStorefront.paymentSuccess') }}
              </h3>
              <p class="mb-1 text-sm text-muted-foreground">
                {{ t('publicStorefront.creditDelivered') }}
              </p>
              <p class="mb-4 text-xs text-muted-foreground">
                {{ t('publicStorefront.selectOnMachine') }}
              </p>
              <p v-if="paymentError" class="mb-4 text-xs text-amber-500">
                {{ paymentError }}
              </p>
              <button
                class="rounded-lg bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                @click="closePayment()"
              >
                {{ t('publicStorefront.dismiss') }}
              </button>
            </div>

            <!-- Payment form -->
            <div v-else>
              <div class="mb-4 flex items-center justify-between">
                <div>
                  <h3 class="text-base font-semibold text-card-foreground">{{ selectedProduct.name }}</h3>
                  <p class="text-sm text-muted-foreground">{{ formatPrice(selectedProduct.price) }}</p>
                </div>
                <button
                  v-if="!confirmLoading"
                  class="text-muted-foreground hover:text-foreground"
                  @click="closePayment()"
                >
                  <svg class="size-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>

              <!-- Loading PI creation -->
              <div v-if="paymentLoading" class="flex items-center justify-center py-8">
                <div class="text-center">
                  <div class="mx-auto mb-3 size-6 animate-spin rounded-full border-2 border-muted-foreground border-t-primary" />
                  <p class="text-sm text-muted-foreground">{{ t('publicStorefront.preparingPayment') }}</p>
                </div>
              </div>

              <!-- Stripe Payment Element -->
              <div v-show="!paymentLoading && !paymentError" class="space-y-4">
                <div id="payment-element" class="min-h-[120px]" />
                <button
                  :disabled="confirmLoading || !paymentElementReady"
                  class="w-full rounded-lg bg-primary py-3 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                  @click="submitPayment()"
                >
                  <span v-if="confirmLoading">
                    <span class="inline-block size-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent align-middle" />
                  </span>
                  <span v-else>{{ t('publicStorefront.pay') }} · {{ formatPrice(selectedProduct.price) }}</span>
                </button>
              </div>

              <!-- Error -->
              <div v-if="paymentError && !paymentLoading" class="py-4 text-center">
                <p class="mb-3 text-sm text-red-500">{{ paymentError }}</p>
                <button
                  class="rounded-lg border border-border px-4 py-2 text-sm font-medium text-card-foreground hover:bg-accent"
                  @click="closePayment()"
                >
                  {{ t('publicStorefront.dismiss') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Notify modal overlay -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="notifyProductId"
          class="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
        >
          <div class="fixed inset-0 bg-black/50" @click="closeNotify()" />
          <div class="relative w-full max-w-md rounded-t-2xl border border-border bg-card p-6 sm:rounded-2xl">
            <h3 class="mb-1 text-sm font-semibold text-card-foreground">
              {{ t('publicStorefront.notifyTitle', { product: notifyProductName }) }}
            </h3>
            <div class="mt-4 flex gap-2">
              <input
                v-model="notifyEmail"
                type="email"
                :placeholder="t('publicStorefront.notifyEmailPlaceholder')"
                class="flex-1 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                @keyup.enter="subscribeRestock()"
              />
              <button
                :disabled="notifyLoading || !notifyEmail"
                class="shrink-0 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                @click="subscribeRestock()"
              >
                {{ notifyLoading ? '...' : t('publicStorefront.notifySubmit') }}
              </button>
            </div>
            <p v-if="notifyError" class="mt-2 text-xs text-red-500">
              {{ t('publicStorefront.notifyError') }}
            </p>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Wish modal overlay -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="wishOpen"
          class="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
        >
          <div class="fixed inset-0 bg-black/50" @click="wishOpen = false" />
          <div class="relative w-full max-w-md rounded-t-2xl border border-border bg-card p-6 sm:rounded-2xl">
            <h3 class="mb-4 text-sm font-semibold text-card-foreground">
              {{ t('publicStorefront.wishTitle') }}
            </h3>

            <div v-if="wishDone" class="py-4 text-center">
              <div class="mx-auto mb-2 flex size-10 items-center justify-center rounded-full bg-emerald-500/15">
                <svg class="size-5 text-emerald-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                </svg>
              </div>
              <p class="text-sm text-emerald-600 dark:text-emerald-400">{{ t('publicStorefront.wishSuccess') }}</p>
            </div>

            <div v-else class="space-y-3">
              <input
                v-model="wishText"
                type="text"
                maxlength="500"
                :placeholder="t('publicStorefront.wishProductPlaceholder')"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <input
                v-model="wishEmail"
                type="email"
                :placeholder="t('publicStorefront.wishEmailPlaceholder')"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <button
                :disabled="wishLoading || !wishText.trim()"
                class="w-full rounded-lg bg-primary py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                @click="submitWish()"
              >
                {{ wishLoading ? '...' : t('publicStorefront.wishSubmit') }}
              </button>
              <p v-if="wishError" class="text-xs text-red-500">
                {{ t('publicStorefront.wishError') }}
              </p>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Feedback modal (Problem melden + Feedback geben, unified) -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="feedbackType"
          class="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
        >
          <div class="fixed inset-0 bg-black/50" @click="closeFeedback()" />
          <div class="relative w-full max-w-md rounded-t-2xl border border-border bg-card p-6 sm:rounded-2xl">
            <!-- Header -->
            <div class="mb-4 flex items-start justify-between">
              <div>
                <h3 class="text-base font-semibold text-card-foreground">
                  {{ feedbackType === 'problem'
                    ? t('publicStorefront.reportProblemTitle')
                    : t('publicStorefront.giveFeedbackTitle') }}
                </h3>
                <p class="mt-0.5 text-xs text-muted-foreground">
                  {{ feedbackType === 'problem'
                    ? t('publicStorefront.reportProblemDesc')
                    : t('publicStorefront.giveFeedbackDesc') }}
                </p>
              </div>
              <button
                v-if="!feedbackLoading"
                class="text-muted-foreground hover:text-foreground"
                @click="closeFeedback()"
              >
                <svg class="size-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <!-- Success state -->
            <div v-if="feedbackDone" class="py-4 text-center">
              <div class="mx-auto mb-2 flex size-10 items-center justify-center rounded-full bg-emerald-500/15">
                <svg class="size-5 text-emerald-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                </svg>
              </div>
              <p class="text-sm text-emerald-600 dark:text-emerald-400">
                {{ t('publicStorefront.feedbackThanks') }}
              </p>
            </div>

            <!-- Form -->
            <div v-else class="space-y-3">
              <textarea
                v-model="feedbackMessage"
                rows="4"
                maxlength="2000"
                :placeholder="feedbackType === 'problem'
                  ? t('publicStorefront.reportProblemPlaceholder')
                  : t('publicStorefront.giveFeedbackPlaceholder')"
                class="w-full resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <input
                v-model="feedbackEmail"
                type="email"
                :placeholder="t('publicStorefront.feedbackEmailPlaceholder')"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <button
                :disabled="feedbackLoading || !feedbackMessage.trim()"
                class="w-full rounded-lg bg-primary py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                @click="submitFeedback()"
              >
                {{ feedbackLoading ? '...' : t('publicStorefront.feedbackSubmit') }}
              </button>
              <p v-if="feedbackError" class="text-xs text-red-500">
                {{ t('publicStorefront.feedbackError') }}
              </p>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Operator info modal (Betreiberinformationen / Impressum, read-only) -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="imprintOpen && data?.imprint"
          class="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
        >
          <div class="fixed inset-0 bg-black/50" @click="imprintOpen = false" />
          <div class="relative w-full max-w-md rounded-t-2xl border border-border bg-card p-6 sm:rounded-2xl">
            <div class="mb-4 flex items-start justify-between">
              <h3 class="text-base font-semibold text-card-foreground">
                {{ t('publicStorefront.operatorInfoTitle') }}
              </h3>
              <button
                class="text-muted-foreground hover:text-foreground"
                @click="imprintOpen = false"
              >
                <svg class="size-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <dl class="space-y-3 text-sm">
              <div v-if="data.imprint.legal_name || data.company_name">
                <dt class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                  {{ t('publicStorefront.operator') }}
                </dt>
                <dd class="mt-0.5 font-medium text-card-foreground">
                  {{ data.imprint.legal_name || data.company_name }}
                </dd>
              </div>

              <div v-if="imprintAddressLine">
                <dt class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                  {{ t('publicStorefront.address') }}
                </dt>
                <dd class="mt-0.5 text-card-foreground">
                  <div v-if="data.imprint.address_street">
                    {{ data.imprint.address_street }}{{ data.imprint.address_house_number ? ' ' + data.imprint.address_house_number : '' }}
                  </div>
                  <div v-if="data.imprint.address_postal_code || data.imprint.address_city">
                    {{ [data.imprint.address_postal_code, data.imprint.address_city].filter(Boolean).join(' ') }}
                  </div>
                </dd>
              </div>

              <div v-if="data.imprint.contact_email">
                <dt class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                  {{ t('publicStorefront.email') }}
                </dt>
                <dd class="mt-0.5">
                  <a
                    :href="`mailto:${data.imprint.contact_email}`"
                    class="text-primary hover:underline"
                  >
                    {{ data.imprint.contact_email }}
                  </a>
                </dd>
              </div>

              <div v-if="data.imprint.contact_phone">
                <dt class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                  {{ t('publicStorefront.phone') }}
                </dt>
                <dd class="mt-0.5">
                  <a
                    :href="`tel:${data.imprint.contact_phone.replace(/[^+\d]/g, '')}`"
                    class="text-primary hover:underline"
                  >
                    {{ data.imprint.contact_phone }}
                  </a>
                </dd>
              </div>

              <div v-if="data.imprint.website">
                <dt class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                  {{ t('publicStorefront.website') }}
                </dt>
                <dd class="mt-0.5 break-all">
                  <a
                    :href="data.imprint.website"
                    target="_blank"
                    rel="noopener"
                    class="text-primary hover:underline"
                  >
                    {{ data.imprint.website }}
                  </a>
                </dd>
              </div>
            </dl>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
