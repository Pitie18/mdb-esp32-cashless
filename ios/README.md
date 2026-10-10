# VMflow iOS App

Native iOS companion app for vending machine operators. Built with SwiftUI, targeting iOS 17+.

## Features

- **Dashboard** — KPI cards (revenue, sales, machine status, stock alerts), 30-day sales chart, recent sales feed
- **Machine Management** — Machines sorted by stock urgency with detailed stats cards:
  - Sales stats: today, yesterday, this week, last week (revenue + count)
  - Summary badges: Empty, Low, Swap Needed, No Stock counts
  - Product deficit rows with warehouse availability labels (In Stock / Swap / No Stock)
  - Discontinued product (DC) badges
  - Overall stock percentage bar
- **Tray Configuration** — Full CRUD for machine tray slots:
  - Add single tray or batch-add sequential slots
  - Edit slot number, product, capacity, stock, thresholds
  - Quick stock adjustments (+/- buttons)
  - Stock bar with min stock (amber) and fill-when-below (blue) threshold markers
  - Fuzzy product search picker
- **Refill Wizard** — Multi-step guided refill tour optimized for one-handed field use:
  - **Packing step** — Product-centric view: products grouped across machines, per-machine checkboxes, quantity steppers
  - Warehouse stock awareness: tracks available stock, shows partial/out-of-stock states
  - **Machine selection** — Pick which machines to refill, ordered by proximity or urgency
  - **Refill step** — Per-machine tray adjustments, fill-to-capacity, warehouse stock deduction
  - **Review step** — Summary of all changes before committing; a chosen replacement product is queued into the machine's slot change request (`save_slot_change_request`), never written to the tray directly
  - **Change notes ("Umbelegen")** — open slot change requests (planned on the web) show per machine while packing; accepted slots leave the normal refill and their units are packed and deducted with it. Their units show in the normal packing list (violet). At the machine each slot is counted (taken out / filled in / price set) and marked rebuilt or not, and `apply_slot_change` runs before `refill_machine_trays`; the leftovers are counted at the warehouse on the summary step and booked back or written off in one go (`return_slot_change_leftovers`). Tour maths in `Models/SlotChange.swift` (port of the web's `lib/slotChange.ts`), host tests in `HostTests/SlotChange/run.sh` (any Swift toolchain, no Xcode). Requests are read and queued through `Services/SlotChangeService.swift`; the machine Analysis tab's replace action queues into the same request (slots with a pending change are marked) instead of switching the tray
  - **Summary** — Tour stats with items refilled, machines completed, slots rebuilt
- **Push Notifications** — APNs support for low-stock alerts and other events

## Setup

### Prerequisites

- Xcode 15+ (iOS 17 SDK)
- A running VMflow backend (Supabase + MQTT via Docker or Supabase CLI)

### Steps

1. Open `ios/VMflow/` in Xcode (File > Open > select the folder containing `Package.swift`)
2. The Supabase Swift SDK is included as a Swift Package dependency:
   - `https://github.com/supabase/supabase-swift` (from: 2.0.0)
3. Configure your Supabase credentials in `Resources/Info.plist`:
   - `SUPABASE_URL` — Your Supabase API URL (e.g., `http://10.0.1.181:8000` for Docker, `http://10.0.1.181:54321` for CLI)
   - `SUPABASE_ANON_KEY` — Your Supabase anonymous key
4. Build and run on a simulator or device

> **Important**: Use your Mac's LAN IP (not `localhost`) so physical devices and simulators can reach the backend.

### Environment Variables (Info.plist)

| Key | Description | Example |
|-----|-------------|---------|
| `SUPABASE_URL` | Supabase API gateway URL | `http://10.0.1.181:8000` |
| `SUPABASE_ANON_KEY` | Supabase anonymous (public) key | `eyJ...` |

### Push Notifications (APNs)

To enable push notifications, configure these in the backend `Docker/.env`:

| Key | Description |
|-----|-------------|
| `APNS_KEY_ID` | Your APNs key ID (from Apple Developer portal) |
| `APNS_TEAM_ID` | Your Apple Developer Team ID |
| `APNS_TOPIC` | App bundle identifier (e.g., `xyz.vmflow.app`) |
| `APNS_KEY_P8` | APNs private key (PEM format, multi-line in quotes) |
| `APNS_PRODUCTION` | `true` for TestFlight/App Store, `false` for debug builds |

## Architecture

- **MVVM** pattern with SwiftUI + Combine
- **Swift Concurrency** (async/await) for all network calls
- **Supabase Swift SDK** for auth, database queries, and storage
- **Swift Charts** framework for revenue visualization

### Structure

```
ios/VMflow/
  VMflowApp.swift              — App entry point, auth routing
  Models/
    VendingMachine.swift       — Machine, MachineStats, TrayDeficit, WarehouseAvailability
    Tray.swift                 — Tray model with stock health
    Product.swift              — Product catalog model
    Sale.swift                 — Sales data model
    Warehouse.swift            — Warehouse and stock batch models
  Services/
    SupabaseService.swift      — Singleton Supabase client
    AuthService.swift          — Auth state management, organization membership
  ViewModels/
    DashboardViewModel         — Dashboard KPIs and chart data
    MachineListViewModel       — Machine list with stats, warehouse stock awareness
    MachineDetailViewModel     — Single machine detail, sales history
    TrayViewModel              — Tray CRUD operations
    RefillWizardViewModel      — Multi-step refill flow with packing state
  Views/
    Auth/                      — Login, Register
    Dashboard/                 — Dashboard, KPI cards
    Machines/                  — Machine list, detail, MachineCard with stats grid
    Trays/                     — Tray list, TrayEditSheet, TrayRow with quick actions
    Refill/                    — Wizard: PackingStepView, RefillStepView, ReviewStepView, SummaryView
    Components/                — StatusBadge, StockBar (with threshold markers),
                                 ProductImage, KPICard, StockHealthIndicator,
                                 BottomTabBar, FlowLayout
  Resources/
    Info.plist                 — App config with Supabase credentials
```

## Data Flow

1. `AuthService` manages session state and organization membership
2. On successful login, the app fetches the user's organization via `get-my-organization` edge function
3. ViewModels query Supabase tables directly using the Swift SDK (`from().select().execute()`)
4. Product images load from Supabase Storage (`product-images` bucket) via `AsyncImage`
5. All prices are in EUR (not cents), matching the backend schema
6. Warehouse stock is fetched from `warehouse_stock_batches` to classify product availability
7. Sales are grouped into time periods (today, yesterday, this week, last week) using Monday-based week boundaries

## Parity with Web Dashboard

The iOS app mirrors key features from the Nuxt web dashboard:

| Feature | Web | iOS |
|---------|-----|-----|
| Machine cards with sales stats | 4 time periods | 4 time periods (2x2 grid) |
| Stock urgency badges | Empty, Low, Swap, No Stock | Same badges |
| Warehouse availability labels | In Stock / Swap / No Stock | Same labels + dimming |
| Stock bar threshold markers | min stock + fill-when-below | Amber + blue markers |
| Discontinued product badge | DC tag | DC capsule |
| Product-centric packing | Combined mode | Same layout |
| Fuzzy product search | Combobox | Picker with fuzzy match |
