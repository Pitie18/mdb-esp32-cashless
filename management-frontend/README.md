# VMflow Management Frontend

Nuxt 4 management dashboard for the VMflow vending machine platform. Built with TypeScript, shadcn-nuxt, TailwindCSS 4, and Supabase.

## Features

- **Dashboard** — KPI cards, 30-day sales chart, activity feed, machine overview
- **Machine Management** — Live status, revenue stats, tray/stock config, product-centric performance analysis (iOS-style layout grid + replacement suggestions), slot re-assignment planner ("Umbelegen": drag & drop, saved as a change request for the next refill tour), AI-powered insights
- **Refill Wizard** — Multi-step guided refill tours with warehouse stock tracking; open slot change requests show as change notes the refiller accepts and confirms at the machine
- **Products** — CRUD with image upload/search, categories, Nayax Excel import, discontinued flag
- **Warehouse** — FIFO stock batches, barcode scanning, position management, min-stock alerts
- **Devices** — Provisioning with QR codes, firmware OTA (upload + GitHub import)
- **PWA** — Installable, push notifications, pull-to-refresh, offline-capable
- **i18n** — English and German

## Setup

```bash
npm install
```

### Environment

Create `.env`:

```env
SUPABASE_URL=http://127.0.0.1:54321   # port 54321 = API, NOT 54323 (Studio)
SUPABASE_KEY=<anon key from supabase start>
```

### Development

```bash
npm run dev      # http://localhost:3000
```

Login with seed user: `test@test.com` / `password123`

### Production Build

```bash
npm run build
npm run preview
```

## Tech Stack

- **Framework**: Nuxt 4 (Vue 3, TypeScript)
- **UI**: shadcn-nuxt (reka-ui), TailwindCSS 4
- **Backend**: Supabase (Auth, Database, Storage, Realtime, Edge Functions)
- **Modules**: `@nuxtjs/supabase`, `@nuxtjs/i18n`, `@vueuse/core`
- **Testing**: Vitest

See [DEV.md](../DEV.md) and [PROD.md](../PROD.md) for full development and production guides.
