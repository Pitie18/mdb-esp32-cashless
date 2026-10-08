<div align="center">

<img src="management-frontend/public/icons/icon-512.png" width="96" height="96" alt="VMflow logo" />

# VMflow

### Open-Source Vending Machine IoT Platform

**Turn any vending machine into a connected, cashless, remotely managed device.**

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![ESP-IDF](https://img.shields.io/badge/ESP--IDF-v5.x-red?logo=espressif)](https://docs.espressif.com/projects/esp-idf/)
[![Nuxt](https://img.shields.io/badge/Nuxt-4-00DC82?logo=nuxtdotjs&logoColor=white)](https://nuxt.com)
[![Supabase](https://img.shields.io/badge/Supabase-Self--Hosted-3FCF8E?logo=supabase&logoColor=white)](https://supabase.com)
[![App Store](https://img.shields.io/badge/App_Store-VMflow-0D96F6?logo=appstore&logoColor=white)](https://apps.apple.com/app/id6761818917)
[![Android](https://img.shields.io/badge/Android-Jetpack_Compose-3DDC84?logo=android&logoColor=white)](android/)
[![Hardware: CERN-OHL-S v2](https://img.shields.io/badge/Hardware-CERN--OHL--S_v2-blue.svg)](kicad/mdb_slave_esp32s3-wroom-1u/LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#-contributing)

**[🌐 Live Dashboard](https://app.kerl-handel.de)** · [📲 iOS App](https://apps.apple.com/app/id6761818917) · [⚡ Web Installer](https://app.kerl-handel.de/install) · [📖 Local Dev](DEV.md) · [🚀 Production](PROD.md) · [🏗 Architecture](ARCHITECTURE.md)

</div>

---

VMflow is a complete, **self-hostable** platform for retrofitting ordinary vending machines with modern IoT capabilities. A custom **ESP32-S3** board plugs into any machine's **MDB (Multi-Drop Bus)** and instantly unlocks cashless payments, real-time sales tracking, remote credit delivery, over-the-air firmware updates, warehouse-driven refills, foot-traffic analytics, and AI-powered insights — all controlled from a modern web dashboard and native iOS and Android apps.

No vendor lock-in, no cloud subscription, no per-device fees. You own the hardware, the firmware, the backend, and the data.

---

## ✨ What You Can Do

<table>
<tr>
<td width="50%" valign="top">

### 🏪 Fleet & Machine Management
Monitor every machine in real time — online/offline status, today/this-week/this-month revenue, sales counts, and which machines need refilling. Drill into any machine for a 30-day revenue chart and an itemized, time-stamped sales history.

</td>
<td width="50%" valign="top">

### 💳 Cashless Payments (MDB)
A standards-compliant MDB **cashless peripheral**. Deliver credit to a machine remotely over MQTT or BLE, sit alongside existing coin/bill acceptors, and approve vends in real time. All payloads are XOR-encrypted with replay protection.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 📦 Warehouse & Inventory
Barcode-driven stock intake, **FIFO batch tracking** with expiry dates, per-warehouse minimum-stock alerts, stock-value reporting, and physical position layouts. Stock auto-decrements on every sale.

</td>
<td width="50%" valign="top">

### 🔁 Guided Refill Tours
A step-by-step refill wizard generates per-machine packing lists from live warehouse stock, walks the operator through the route, deducts inventory FIFO, and logs the whole tour for history and audit.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 🤖 AI Insights
Per-machine and fleet-wide recommendations powered by the **Claude API** — spot dead slots, surface best-sellers, and get concrete product-swap suggestions, in your own language. Bring your own API key.

</td>
<td width="50%" valign="top">

### 🧾 Sales Reconciliation
Upload a **Nayax** sales export and reconcile it against recorded sales with a time-tolerant matcher. Bulk-import anything missing, remove ghost entries, and export a CSV diff — fully audited.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 📊 Telemetry & Foot Traffic
Read **EVA-DTS DEX/DDCMP** data straight off the machine, count nearby foot traffic with the **PAX Counter** (anonymized BLE/WiFi presence), and keep a full MDB bus diagnostics + state history per device.

</td>
<td width="50%" valign="top">

### 🔄 OTA & Firmware
Build firmware in **GitHub Actions**, import a release with one click, and deploy **over-the-air** to any device over MQTT. Full/app-only images, version notes, and per-version rollout control.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 📡 Resilient Connectivity
WiFi out of the box, with optional **Cellular / LTE** (SIM7080G Cat-M / NB-IoT) for machines without a network. A multi-layer recovery ladder keeps devices online unattended in the field.

</td>
<td width="50%" valign="top">

### 🏢 Multi-Tenancy & Security
Organization-based access with **admin/viewer** roles, email invitations, and **row-level security** on every table. Self-hosted auth, storage, and database — your data never leaves your server.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 📈 Fleet Analytics
Cross-fleet analytics on web and iOS: filter by period (incl. year presets and all-time), machine, category or product; KPIs, trend chart, peak-hours heatmap, payment-channel split, and a sortable breakdown you can drill into.

</td>
<td width="50%" valign="top">

### 🏷 Purchase Prices & Deals
Track supplier purchase prices (net/gross) and margins per product. The **Deals** page surfaces retailer offers matching your catalogue, grouped by validity, and compares each one against your usual purchase price — implausible offers are filtered out. New deal sources plug in via [extension points](docs/extension-points/deal-source.md).

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 💶 Cash Book
A GoBD-style cash book (Kassenbuch) for coin and banknote cash: withdrawals from machines during refill tours, expenses with categories and receipts, reversals, theoretical vs. counted cash, and PDF export.

</td>
<td width="50%" valign="top">

### 🪪 Prepaid RFID Cards
Plug a serial RFID reader (F02DC and compatibles) into the board and let customers pay with prepaid cards. Balances, top-ups, corrections, and a full ledger are managed on the **Card accounts** page. See [the RFID guide](docs/integrations/rfid-card-reader.md).

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 🖨 Printable Machine Signs
Generate contact posters and stickers per machine — A4/A5/A6 posters, 2-up A5 / 4-up A6 / 8-up A7 sheets with cut lines, and sticker sheets — with operator-configurable QR codes (machine page, phone, WhatsApp, fault form, own link). VMflow flags signs whose contact data has changed.

</td>
<td width="50%" valign="top">

### 🌍 Public Machine Pages & API
Each machine gets a public page (reached via its QR code) where customers can report a fault, wish for a product, subscribe to restock alerts, or pay online via **Stripe**. Integrators get a key-authenticated **REST API (`/api/v1`)** with an OpenAPI spec and an **MCP server** for AI agents.

</td>
</tr>
</table>

---

## 🖥 Management Dashboard

A modern, responsive web app (Nuxt 4 PWA, installable, dark mode, English, German, French & Dutch) that gives operators full control over their fleet.

> **[👉 See it live at app.kerl-handel.de](https://app.kerl-handel.de)**

<table>
<tr>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/machines.png" alt="Fleet overview" />

**Fleet overview** — live status, per-machine revenue, and stock urgency at a glance

</td>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/machine-detail.png" alt="Machine detail" />

**Machine detail** — 30-day revenue chart and itemized sales history with product images

</td>
</tr>
<tr>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/analysis.png" alt="Product analysis" />

**Product analysis** — springboard layout colour-coded by performance, with one-click swaps

</td>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/warehouse.png" alt="Warehouse" />

**Warehouse** — stock levels, FIFO batches, expiry warnings, and live stock value

</td>
</tr>
<tr>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/refill.png" alt="Guided refill tour" />

**Guided refill tour** — per-machine packing lists driven by live warehouse availability

</td>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/products.png" alt="Product catalog" />

**Product catalog** — images, categories, pricing, and bulk Nayax import

</td>
</tr>
<tr>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/dashboard.png" alt="Dashboard" />

**Dashboard** — revenue KPIs, 7-day trend, and best-selling products

</td>
<td width="50%" align="center" valign="top">

<img src="docs/screenshots/web/firmware.png" alt="Firmware & OTA" />

**Firmware & OTA** — import from GitHub releases and deploy over-the-air

</td>
</tr>
</table>

---

## 📱 iOS App

A native **SwiftUI** app for operators — full fleet management from your pocket. **Available on the App Store** for iPhone and iPad (iOS/iPadOS 17+), in English, German, French and Dutch.

<a href="https://apps.apple.com/app/id6761818917"><img src="https://img.shields.io/badge/Download_on_the-App_Store-000000?style=for-the-badge&logo=apple&logoColor=white" alt="Download on the App Store" /></a>

The app connects to your own VMflow server — sign in with your organization's account; new team members join by invitation.

- **Dashboard** — revenue KPIs, 30-day sales chart, and a live activity feed (sales, refills, intakes, tours, cash movements)
- **Machines** — sorted by stock urgency, with warehouse-availability labels, analysis tab, send credit, device health, and machine settings
- **Trays & Stock** — per-machine slot configuration and quick stock adjustments; stock judged per product across slots
- **Refill Wizard** — warehouse-aware packing, guided refill tour, and a review step with replacement suggestions
- **Warehouse** — barcode intake (with Open Food Facts name lookup), FIFO batches, expiry dates, purchase prices
- **Analytics, Deals & Cash Book** — fleet analytics, retailer offers with purchase-price comparison, cash expenses
- **Push notifications** — low-stock and sale alerts via APNs

Built with SwiftUI, Swift Concurrency, the Supabase Swift SDK, and Swift Charts; released through fastlane + GitHub Actions. See [`ios/README.md`](ios/README.md) for setup.

<table>
<tr>
<td width="20%" align="center"><img src="ios/fastlane/screenshots/en-US/iPhone%2017%20Pro%20Max-01Dashboard.png" alt="Dashboard" /><br/><b>Dashboard</b></td>
<td width="20%" align="center"><img src="ios/fastlane/screenshots/en-US/iPhone%2017%20Pro%20Max-02Machines.png" alt="Machines" /><br/><b>Machines</b></td>
<td width="20%" align="center"><img src="ios/fastlane/screenshots/en-US/iPhone%2017%20Pro%20Max-03MachineDetail.png" alt="Machine detail" /><br/><b>Machine detail</b></td>
<td width="20%" align="center"><img src="ios/fastlane/screenshots/en-US/iPhone%2017%20Pro%20Max-04Refill.png" alt="Refill wizard" /><br/><b>Refill Wizard</b></td>
<td width="20%" align="center"><img src="ios/fastlane/screenshots/en-US/iPhone%2017%20Pro%20Max-05Warehouse.png" alt="Warehouse" /><br/><b>Warehouse</b></td>
</tr>
</table>

---

## 🤖 Android App

A native **Kotlin + Jetpack Compose (Material 3)** app for operators, at feature parity with the iOS app for day-to-day field work. Requires Android 8.0+ (API 26).

- **Dashboard** — revenue KPIs, 30-day chart, cash book, and an infinite-scroll activity feed
- **Machines** — list-detail layout on tablets, machine analysis grid, send credit, device health, machine settings
- **Refill Wizard** — stock-aware packing, FIFO deduction, atomic refill with retry/skip, resumable tours, review step with replacement picker
- **Warehouse** — stock overview, barcode-driven intake, batch drill-down and adjustments
- **Deals** — retailer offers grouped by validity, with purchase-price comparison
- **Server selection** — pick your backend on the login screen or scan the dashboard's QR code

A fastlane + GitHub Actions pipeline for Google Play is included. See [`android/README.md`](android/README.md) for setup and release.

---

## 🔌 Hardware

The custom PCB connects directly to the vending machine's MDB bus via the standard connector. It's powered from the machine's own supply, needs no external power, and talks to the backend over WiFi (or optional Cellular/LTE) + MQTT.

<table>
<tr>
<td width="33%" align="center" valign="top">

![PCB v3](mdb-slave-esp32s3/mdb-slave-esp32s3_pcb_v3.jpg)

**PCB v3** — latest revision

</td>
<td width="33%" align="center" valign="top">

![PCB in 3D-printed holder](3d-printing/mdb-slave/5404579798257963703_121.jpg)

**Installed** — with 3D-printed mount

</td>
<td width="33%" align="center" valign="top">

![3D mount CAD](3d-printing/mdb-slave/MDB-Slave-Holder.png)

**Mounting bracket** — printable STL included

</td>
</tr>
</table>

**Key specs**

- **MCU:** ESP32-S3 (dual-core, WiFi + BLE 5)
- **MDB interface:** UART 9600 baud, 9-bit mode, optocoupler isolated
- **Power:** drawn from the vending-machine bus (onboard buck converter)
- **Connectivity:** WiFi, optional Cellular/LTE (SIM7080G Cat-M / NB-IoT)
- **Connectors:** MDB, USB-C (programming & debug), DEX telemetry port, 2×11 extension header (J4) for add-on modules (original board)
- **Payments add-ons:** serial RFID card reader for prepaid cards ([guide](docs/integrations/rfid-card-reader.md))
- **PCB design:** KiCad — sources in [`kicad/`](kicad/)
- **Enclosure:** 3D-printable bracket — STL/STEP in [`3d-printing/`](3d-printing/)

### Board variants

All boards run the **same firmware** ([`mdb-slave-esp32s3/`](mdb-slave-esp32s3/)): the WROOM-1U board is detected at boot via a GPIO3 pull-down strap, and a cellular modem is detected by probing it.

| Board | KiCad sources | Highlights |
|---|---|---|
| **MDB ESP32-S3** (original) | [`kicad/mdb-slave-esp32s3/`](kicad/mdb-slave-esp32s3/) | ESP32-S3, WiFi, MDB + DEX + pulse I/O, J4 extension header |
| **MDB ESP32-S3 + SIM7080G** | [`kicad/mdb-slave-esp32s3-sim7080g/`](kicad/mdb-slave-esp32s3-sim7080g/) | Adds an onboard SIM7080G modem (GPS / LTE-M / NB-IoT) — see its README for pin mapping and firmware status |
| **MDB ESP32-S3-WROOM-1U** | [`kicad/mdb_slave_esp32s3-wroom-1u/`](kicad/mdb_slave_esp32s3-wroom-1u/) | 4-layer board (rev 1.4.1) built around the ESP32-S3-WROOM-1U-N16R2 module (16 MB flash, 2 MB PSRAM, external u.FL antenna for metal cabinets), WiFi-only. Two isolated 15 A SPDT relays, three custom digital inputs, two 1-Wire buses, I2C, NTC input, RGB status LED, buzzer, adjustable 3.8–32 V buck. Ships with fabrication gerbers (JLCPCB/PCBWay) and an [interactive BOM](kicad/mdb_slave_esp32s3-wroom-1u/bom/ibom.html). Licensed under **CERN-OHL-S v2**. |

**3D-printed holders** — three variants in [`3d-printing/`](3d-printing/): the original [`mdb-slave`](3d-printing/mdb-slave/) bracket, a [`mdb-slave-more-stable`](3d-printing/mdb-slave-more-stable/) version with extra support at the MDB connector, and [`mdb-slave-sim`](3d-printing/mdb-slave-sim/) with holes for two SMA antenna connectors (cellular board).

> 🛒 **Order the PCB:** [PCBWay shared project](https://www.pcbway.com/project/shareproject/mdb_esp32_cashless_bc6bf8d8.html) · [PCBWay project store](https://www.pcbway.com/project/member/?bmbno=1B3B95CB-4E28-4D)

---

## 🏗 How It Works

```
┌───────────────────────────────────────────────────────────────────┐
│   Management UI (Nuxt 4)  ·  iOS (SwiftUI)  ·  Android (Compose)  │
│  Dashboard · Machines · Warehouse · Refill · Analytics · Firmware │
└──────────────────────────────┬────────────────────────────────────┘
                               │ HTTPS
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Supabase (self-hosted)                          │
│   ┌──────────┐  ┌──────────┐  ┌────────────────┐  ┌───────────────┐  │
│   │  Kong    │  │  Auth    │  │ Edge Functions  │  │   Storage     │  │
│   │ API GW   │  │ (GoTrue) │  │     (Deno)      │  │ firmware/imgs │  │
│   └──────────┘  └──────────┘  └────────┬────────┘  └───────────────┘  │
│   ┌─────────────────────────────────────────────────────────────┐    │
│   │            PostgreSQL  (RLS + multi-tenancy)                 │    │
│   └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
┌──────────────────┐  ┌──────────────┐  ┌──────────────────────┐
│  MQTT Forwarder  │  │ MQTT Broker  │  │   GitHub Actions     │
│     (Deno)       │  │ (Mosquitto)  │  │   CI/CD → Releases   │
│  MQTT → Webhook  │  │    :1883     │  │   (firmware builds)  │
└──────────────────┘  └──────┬───────┘  └──────────────────────┘
                             │  MQTT over WiFi / Cellular
              ┌──────────────┴──────────────┐
              ▼                             ▼
   ┌──────────────────────┐     ┌──────────────────────┐
   │   ESP32-S3 (Slave)   │     │   ESP32-S3 (Slave)   │
   │   MDB Cashless       │ ··· │   MDB Cashless       │
   │  ┌────────────────┐  │     │  ┌────────────────┐  │
   │  │ Vending Machine│  │     │  │ Vending Machine│  │
   │  └────────────────┘  │     │  └────────────────┘  │
   └──────────────────────┘     └──────────────────────┘
```

Devices publish sales, status, telemetry, and diagnostics to per-tenant MQTT topics (`/{company}/{device}/{event}`). A Deno forwarder bridges MQTT to a Supabase Edge Function that decrypts, validates, and writes to PostgreSQL. Sensitive payloads (credit, sales, config) are **XOR-encrypted** with an 18-byte passkey and an ±8-second timestamp window to prevent replay. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full picture.

---

## 🚀 Getting Started

### 1. Flash the firmware

The easiest way — no build tools required:

👉 **[app.kerl-handel.de/install](https://app.kerl-handel.de/install)** — flash directly from your browser via Web Serial

Or build from source with ESP-IDF v5.x:

```bash
cd mdb-slave-esp32s3
. $IDF_PATH/export.sh
idf.py build flash monitor
```

### 2. Deploy the platform

Pick the setup that fits your use case:

| | **Local Development** | **Production** |
|---|---|---|
| **Guide** | 📖 [**DEV.md**](DEV.md) | 🚀 [**PROD.md**](PROD.md) |
| **Purpose** | Develop & test locally | Deploy for real devices |
| **Backend** | Supabase CLI (`supabase start`) | Docker Compose (`docker compose up`) |
| **Frontend** | `npm run dev` (hot reload) | Built & served via Docker |
| **Setup** | `supabase start` + `npm run dev` | `bash setup.sh` (one command) |

### 3. Provision a device

Once the platform is running:

1. Power on the ESP32 board → it creates a WiFi hotspot
2. Connect to the hotspot and open the captive portal → enter WiFi credentials (or APN/SIM PIN on a cellular board) + server URL
3. In the dashboard, go to **Devices** → register the device (optionally give it a name) and generate a provisioning code
4. Enter the code on the device's captive portal
5. The device reboots, connects to MQTT, and appears in your dashboard

The captive portal also lets a cellular board switch its uplink to WiFi (and back), and offers a **reset device** button.

---

## 🔗 REST API

### Public API v1 & MCP server

For integrations, VMflow exposes a company-scoped, rate-limited **REST API at `/api/v1`**, authenticated with an API key created on the **API keys** page (`X-API-Key: vmf_…`). The OpenAPI 3.0 spec is served at `GET /api/v1/openapi.yaml`.

The optional `mcp-bridge` service wraps that spec as an **MCP server** (Streamable HTTP at `/mcp`), so AI agents and MCP clients (Claude Desktop, Cursor, OpenClaw, …) discover every operation as a tool. See [docs/integrations/openclaw-mcp-bridge.md](docs/integrations/openclaw-mcp-bridge.md).

```bash
curl 'https://your-server/api/v1/openapi.yaml' -H "X-API-Key: vmf_your_key"
```

### Supabase REST

The underlying Supabase REST API is also available. Authenticate with a JWT bearer token.

<details>
<summary><strong>Get a bearer token</strong></summary>

```bash
curl -X POST 'https://your-server:8000/auth/v1/token?grant_type=password' \
  -H "apikey: YOUR_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{ "email": "you@example.com", "password": "your_password" }'
```
</details>

<details>
<summary><strong>Send credit to a machine</strong></summary>

```bash
curl -X POST 'https://your-server:8000/functions/v1/send-credit' \
  -H "apikey: YOUR_ANON_KEY" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "subdomain": 51, "amount": 1.50 }'
```
</details>

<details>
<summary><strong>View sales</strong></summary>

```bash
curl -X GET 'https://your-server:8000/rest/v1/sales' \
  -H "apikey: YOUR_ANON_KEY" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```
</details>

<details>
<summary><strong>View devices</strong></summary>

```bash
curl -X GET 'https://your-server:8000/rest/v1/embeddeds' \
  -H "apikey: YOUR_ANON_KEY" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```
</details>

---

## 🗺 PAX Counter — Foot-Traffic Heatmap

Each device scans for nearby BLE/WiFi devices and reports anonymized presence counts, visualized as heatmaps for location-performance analysis.

![PAX Counter heatmap](pax-counter-heatmap.png)

---

## 📂 Project Structure

```
mdb-esp32-cashless/
├── mdb-slave-esp32s3/       # ESP32 firmware — MDB cashless peripheral
├── mdb-master-esp32s3/      # ESP32 firmware — VMC simulator (for testing)
├── management-frontend/     # Nuxt 4 management dashboard (PWA)
├── ios/                     # Native iOS app (SwiftUI) + fastlane
├── android/                 # Native Android app (Kotlin, Jetpack Compose) + fastlane
├── Docker/                  # Self-hosted backend (docker-compose)
│   ├── supabase/            # Edge functions, migrations, config
│   ├── mqtt/                # Mosquitto broker + Deno forwarder
│   └── setup.sh             # One-command production setup
├── kicad/                   # PCB design files (KiCad)
│   ├── mdb-slave-esp32s3/            # Original board
│   ├── mdb-slave-esp32s3-sim7080g/   # Cellular board (SIM7080G)
│   └── mdb_slave_esp32s3-wroom-1u/   # WROOM-1U board (gerbers, iBOM)
├── 3d-printing/             # Mounting brackets (STL/STEP/F3D), 3 variants
├── docs/                    # Screenshots, integrations, extension points
├── scripts/                 # Demo seed, prod→test data sync, git hooks
├── n8n-workflows-store/     # Example n8n workflows (e.g. send credit via MQTT)
├── brand/                   # Logo and brand assets
├── .github/workflows/       # CI/CD — automated firmware builds
├── DEV.md                   # Local development guide
├── PROD.md                  # Production deployment guide
├── ARCHITECTURE.md          # System architecture details
├── EXTENSIONS.md            # Extension points (provider pattern)
└── BRANDING.md              # Branding guidelines
```

---

## 🧰 Tech Stack

| Layer | Technology |
|-------|-----------|
| **Firmware** | ESP-IDF v5.x, FreeRTOS, NimBLE, MQTT, SIM7080G (cellular) |
| **Backend** | Supabase (PostgreSQL, GoTrue, PostgREST, Kong, Edge Functions) |
| **Edge Functions** | Deno (TypeScript) |
| **Frontend** | Nuxt 4, TypeScript, shadcn-vue, TailwindCSS 4, PWA, i18n (en/de/fr/nl) |
| **MQTT** | Eclipse Mosquitto + custom Deno forwarder |
| **AI** | Claude API (machine & fleet insights) |
| **PCB** | KiCad (2- and 4-layer boards) |
| **Payments** | MDB cashless, prepaid RFID cards, Stripe (public machine page) |
| **CI/CD** | GitHub Actions (ESP-IDF builds → GitHub Releases, iOS & Android releases via fastlane) |
| **iOS App** | SwiftUI, Swift Concurrency, Supabase Swift SDK, Swift Charts |
| **Android App** | Kotlin, Jetpack Compose, Material 3, Supabase Kotlin |

---

## 🤝 Contributing

Contributions are welcome — firmware, dashboard features, PCB revisions, docs, or bug fixes.

1. **Fork** the repository
2. Set up your [local development environment](DEV.md)
3. Create a feature branch (`git checkout -b feature/my-feature`)
4. Make your changes and ensure the build passes
5. Open a Pull Request

---

## 🙏 Acknowledgments

VMflow began as a fork of **[nodestark/mdb-esp32-cashless](https://github.com/nodestark/mdb-esp32-cashless)**.

Huge thanks to **[@nodestark](https://github.com/nodestark)** for the original open-source MDB cashless implementation for the ESP32 — the protocol foundation that everything here is built on. This project would not exist without that work. 🙌

---

## 📄 License

Licensed under the **MIT License** — see [LICENSE](LICENSE).

Original work Copyright © 2025 Nodestark. VMflow additions © 2025–2026 the VMflow contributors.
