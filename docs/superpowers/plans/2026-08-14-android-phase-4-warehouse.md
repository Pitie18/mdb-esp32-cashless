# Android Phase 4: Lager-Schreibpfade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein eigenständiges Lager-Modul für Android bauen — Bestandsübersicht, Wareneingang (inkl. Barcode-Scan), FIFO-Chargenkorrektur — das den Android-Funktionsstand mit iOS' `WarehouseViewModel`/`WarehouseView` deckt und der `deduct_warehouse_stock_fifo`-RPC-Aufruf als Repository-Funktion bereitstellt.

**Architecture:** Wie bisherige Phasen. Reine Logik (Mengen-Ausdrücke, Ablaufdatum-Einstufung, Produkt-Zusammenfassung) in testbare Kotlin-Objekte, Netzwerk in ein erweitertes `WarehouseRepository`, Oberfläche in Compose nach Material 3. Neuer Top-Level-Tab "Lager" — die Navigationsleiste hat dafür bereits Platz reserviert (`TopLevelDestination.kt` Kommentar: *"the warehouse joins in a later package"*).

**Tech Stack:** Kotlin 2.4.10 · AGP 9.3.1 · Gradle 9.5 · Compose BOM 2026.06.01 · compileSdk 36 · ML Kit Barcode Scanning (bereits Abhängigkeit, aktuell nur für QR genutzt)

## Ausgangslage

| | iOS | Android heute |
|---|---:|---:|
| `WarehouseViewModel` | 672 LOC | 0 |
| `WarehouseView` + Sheets | ~1250 LOC über 3 Dateien | 0 |
| `WarehouseRepository` | — (iOS macht alles inline im ViewModel) | 54 LOC, **nur lesend** (3 Fetch-Funktionen) |
| Lager-Screen/Tab | eigener Tab | **existiert nicht** |

**Wichtige Randbedingung, die dieser Plan bewusst NICHT anfasst:** Es gibt bereits einen eigenständigen, funktionierenden Android-„Befüllen"-Tab (`ui/refill/RefillViewModel.kt` + `PackingStep.kt`/`RefillStep.kt`/`RefillSummaryStep.kt`, ~1090 LOC, von einer anderen, parallelen Session gebaut — nicht Teil der ursprünglichen Phase-1-bis-5-Reihenfolge aus `HANDOFF-android-parity.md`). Er ist deutlich einfacher als iOS' lagerbewusster `RefillWizardViewModel` (kein Packen-aus-dem-Lager, kein Warenbestand während des Packens, kein Resume-State, keine FIFO-Abbuchung) und hat **keinerlei** Berührung mit Lager-Tabellen (verifiziert: `grep -n "Warehouse\|warehouse" ui/refill/*.kt data/RefillRepository.kt` → keine Treffer). Diesen bestehenden Flow an die neue Lager-FIFO-Logik anzubinden ist eine eigene, größere Aufgabe (Packmodi, Lagerbestand-Sichtbarkeit beim Packen, Resume-State) und **nicht** Teil dieses Plans — dieser Plan liefert nur die `deductWarehouseStockFifo`-Repository-Funktion als fertigen Baustein dafür.

**Umfangsentscheidung (vom Nutzer bestätigt):** iOS-Parität. Also: Wareneingang mit Barcode-Scan, FIFO-Chargenkorrektur (Beschädigung/Ablauf/Korrektur/Rückgabe), Barcode-Lookup, Lager+Bestand anzeigen, Lieferanten-Autocomplete/-Anlage, plus die `deduct_warehouse_stock_fifo`-RPC als Repository-Funktion. Lager-CRUD, Mindestbestand-Bearbeitung, Positions-/Gruppen-Editor und volle Transaktionshistorie bleiben — wie bei iOS — nur lesbar bzw. fehlen; das ist eine bewusste Lücke, keine vergessene.

**Chargen-Merge-Verhalten (vom Nutzer bestätigt):** Wie iOS — jeder Wareneingang erzeugt immer eine neue Chargenzeile, auch bei exakter Übereinstimmung von Chargennummer + Ablaufdatum. Keine Merge-Logik.

## Referenz-Implementierung

| iOS-Datei | Was daraus portiert wird |
|---|---|
| [`ViewModels/WarehouseViewModel.swift`](../../../ios/VMflow/ViewModels/WarehouseViewModel.swift) | Sämtliche Lager-Logik: Laden, Filter, Wareneingang, Chargenkorrektur, Lieferanten, Barcode-Lookup |
| [`Views/Warehouse/WarehouseView.swift`](../../../ios/VMflow/Views/Warehouse/WarehouseView.swift) | Tab-Struktur (Bestand/Wareneingang), Mengen-Ausdrucksauswertung (`evaluateExpression`, Z. 526-555), Formularfluss |
| [`Views/Warehouse/BatchAdjustSheet.swift`](../../../ios/VMflow/Views/Warehouse/BatchAdjustSheet.swift), [`ProductBatchesView.swift`](../../../ios/VMflow/Views/Warehouse/ProductBatchesView.swift) | Chargen-Drilldown + Korrektur-Sheet |
| [`Models/Warehouse.swift`](../../../ios/VMflow/Models/Warehouse.swift) | Alle Modell-Shapes (siehe Task 2) |
| [`Views/Components/BarcodeScannerView.swift`](../../../ios/VMflow/Views/Components/BarcodeScannerView.swift) | Nur als Referenz für den Barcode-Scan-UX-Fluss — Android nutzt die bereits vorhandene `QrScannerSheet.kt` (ML Kit), siehe Task 8 |

**Explizit außerhalb dieses Plans** (beides existiert in iOS, aber in anderen Screens als `WarehouseView`, die dieser Plan nicht anfasst):
- Mindestbestand-Anzeige (`Views/Products/ProductDetailSheet.swift:1188-1193`) — sitzt im Produkt-Detail-Sheet, nicht im Lager-Modul. Android hat noch kein Produkt-Detail-Sheet-Äquivalent in diesem Umfang; gehört in eine spätere Produkt-Parität-Aufgabe.
- Barcode-CRUD (`ViewModels/ProductsViewModel.swift:441-497`, `addBarcode`/`deleteBarcode`) — sitzt im Produkt-Bearbeiten-Sheet, nicht im Lager-Modul. Dieser Plan liefert nur den **Lookup** (`lookupBarcode`, Task 4/10 — lesend, wird beim Scannen im Wareneingang gebraucht), keine Verwaltung der Barcode-Zuordnungen selbst.

**Umsetzer lesen `WarehouseViewModel.swift` vollständig, bevor sie beginnen** — es ist die dichteste, wichtigste Referenzdatei in diesem Plan.

## Global Constraints

- Keine Backend-Änderungen. Alle Tabellen/RPCs existieren bereits (`warehouses`, `warehouse_stock_batches`, `warehouse_transactions`, `suppliers`, `product_barcodes`, `deduct_warehouse_stock_fifo`).
- `sales.item_price` und alle Preisfelder sind **EUR**, nie Cent (falls Kaufpreise angezeigt werden — in diesem Plan nicht der Fall, nur zur Erinnerung).
- Alle neuen Nutzertexte als Ressourcen in `values/strings.xml` **und** `values-de/strings.xml`; Mengen über `<plurals>`.
- Material 3, `stringResource`, `contentDescription` an Icon-Buttons, keine festen Zeilenhöhen.
- `SupabaseService.client` nur über den Getter, nie in einem `val` festgehalten — der Server-Picker tauscht den Client aus.
- `transaction_type` für Wareneingang ist **`"intake"`** (iOS-Wert) — nicht `"incoming"` (PWA-Wert). Für Chargenkorrekturen ausschließlich einer der vier Werte aus `AdjustReason` (siehe Task 2) — `intake`/`incoming` sind für Korrekturen ausdrücklich gesperrt.
- Jeder Wareneingang erzeugt **immer eine neue Chargenzeile** (kein Merge — siehe Umfangsentscheidung oben).
- Nur JUnit 4 im Testpfad. Keine neuen Abhängigkeiten ohne Prüfung des `minCompileSdk` gegen 36 — Barcode-Scanning nutzt die bereits vorhandene ML-Kit-Abhängigkeit, keine neue.
- Commits: `git add <pfade>`, dann `git commit` **ohne** Pathspec. Niemals `--amend`, `reset`, `rebase`.
- **`RefillViewModel.kt`/`RefillRepository.kt`/`PackingStep.kt`/`RefillStep.kt`/`RefillSummaryStep.kt` werden von diesem Plan nicht angefasst** (siehe Ausgangslage oben).

## Testumgebung

Physisches Gerät: Samsung Galaxy S10, `adb -s <seriennummer oder mdns-alias aus 'adb devices -l'>`, Android 16 / API 36. Lokaler Supabase-Stack auf dem Mac. Vor dem ersten Geräte-Test: Docker-Status prüfen (`docker info`), bei Bedarf `open -a Docker` und warten, dann `supabase status --workdir Docker` und `cd Docker/supabase && nohup supabase functions serve --env-file .env &` falls die Edge-Runtime gestoppt ist (`curl http://127.0.0.1:54321/functions/v1/get-my-organization` → 401 = gesund, 000/503 = fehlt).

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest \
  -PSUPABASE_URL=http://<LAN-IP des Macs>:54321 \
  -PSUPABASE_ANON_KEY=<anon key aus 'supabase status'>
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Bildschirm-Koordinaten:** `adb shell input tap/swipe` erwartet Gerätepixel, nicht die skalierten Koordinaten eines verkleinert angezeigten Screenshots. Bei Unsicherheit `adb shell uiautomator dump` + `bounds="[x1,y1][x2,y2]"` nutzen. Ein Swipe nahe am rechten Bildschirmrand kann als System-Zurück-Geste interpretiert werden.

**Umsetzer installieren nicht selbst** — Screenshots und Gerätetest macht der Orchestrator, wie in den vorigen Phasen.

---

### Task 1: Reine Logik — Mengen-Ausdruck, Ablaufdatum-Einstufung, Bestandszusammenfassung

Der Kern der Phase, komplett ohne Netzwerk.

**Vorher lesen:** `WarehouseView.swift` Z. 526-555 (`evaluateExpression`), `Models/Warehouse.swift` Z. 69-84 (`expirationStatus(for:)`), `WarehouseViewModel.swift` Z. 141-216 (`loadProductSummaries`' In-Memory-Aggregation).

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/WarehouseIntakeLogic.kt`
- Create: `android/app/src/test/java/xyz/vmflow/data/WarehouseIntakeLogicTest.kt`

**Interfaces:**
- Produces:
  - `object WarehouseIntakeLogic`
  - `fun evaluateQuantityExpression(text: String): Int?`
  - `enum class ExpirationStatus { OK, WARNING, CRITICAL }`
  - `fun expirationStatus(dateIso: String?, today: LocalDate): ExpirationStatus`
  - `data class ProductSummaryInput(val productId: String, val name: String?, val imagePath: String?, val discontinued: Boolean)`
  - `data class BatchSummaryInput(val productId: String, val quantity: Int, val expirationDate: String?)`
  - `fun buildProductSummaries(products: List<ProductSummaryInput>, batches: List<BatchSummaryInput>, today: LocalDate): List<WarehouseProductSummary>` — `WarehouseProductSummary` ist das Task-2-Modell (siehe unten); dieses Modell wird in Task 2 definiert, Task 1 hängt also lose von Task 2 ab — beide zusammen in einer Umsetzer-Sitzung erledigen oder Task 1 zuerst mit einem lokalen Platzhalter-Typ schreiben und in Task 2 auf den echten `models.WarehouseProductSummary` umstellen.

**Regeln, wörtlich aus dem Swift:**

```
evaluateQuantityExpression(text)
  - Normalisieren: "×" und "x" durch "*" ersetzen, Leerzeichen entfernen
  - leer -> null
  - reine Zahl -> die Zahl
  - sonst: nur Ziffern und + - * / . erlaubt (jedes andere Zeichen -> null)
  - darf nicht mit einem Operator enden -> null
  - Ergebnis mit STANDARD-Operatorrangfolge auswerten (* und / vor + und -,
    NICHT strikt links-nach-rechts trotz des irreführenden iOS-Kommentars —
    NSExpression respektiert echte Rangfolge; das ist das Verhalten, das
    Android nachbilden muss)
  - Ergebnis <= 0 -> null, sonst das (auf Int gerundete) Ergebnis

expirationStatus(dateIso, today)
  - dateIso null -> OK
  - dateIso nicht als yyyy-MM-dd parsbar -> OK
  - Tage bis dateIso (kann negativ sein bei bereits abgelaufen) < 7 -> CRITICAL
  - Tage bis dateIso <= 30 -> WARNING
  - sonst -> OK

buildProductSummaries(products, batches, today)
  - JEDES Produkt bekommt einen Eintrag, auch mit 0 Bestand (kein Filtern hier —
    Filtern passiert später in der UI/ViewModel-Schicht, Task 9)
  - Batches nach productId gruppieren: totalQuantity = Summe, batchCount = Anzahl,
    earliestExpiration = das kleinste (früheste) nicht-null expirationDate
    (String-Vergleich reicht, da yyyy-MM-dd lexikographisch sortiert)
  - expirationStatus wird aus earliestExpiration berechnet
  - Name-Fallback: null/leer -> "Unknown" (nicht lokalisiert — matcht iOS'
    eigenes Verhalten wörtlich, siehe `WarehouseViewModel.swift:202`)
```

- [ ] **Step 1: Fehlschlagende Tests schreiben**

Mindestens diese Fälle:

*evaluateQuantityExpression* — "12" → 12; "2*12" → 24; "100+50" → 150; "10-2" → 8; "48/6" → 8; "2×3" → 6; "2x3" → 6; "  2 * 3  " → 6 (Leerzeichen ignoriert); "2+3*4" → 14 (Rangfolge: `*` vor `+`, NICHT 20); "" → null; "abc" → null; "2+" → null (endet auf Operator); "2++2" → null; "0" → null (Ergebnis muss > 0 sein); "-5" → null; "2*-3" → null oder ein von dir begründetes Verhalten — dokumentiere im Test, was NSExpression hier tatsächlich tut, falls unklar behandle es konservativ als null.

*expirationStatus* — `null` → OK; ein Datum 40 Tage in der Zukunft → OK; genau 30 Tage → WARNING; 31 Tage → OK (Grenze ist `<=`); genau 7 Tage → OK (Grenze ist `< 7`, also bei 7 noch WARNING falls `<=30`, NICHT critical — teste diese Grenze explizit); 6 Tage → CRITICAL; 0 Tage (heute) → CRITICAL; -5 Tage (abgelaufen) → CRITICAL; unparsbarer String → OK.

*buildProductSummaries* — ein Produkt ohne Batches → totalQuantity 0, batchCount 0, earliestExpiration null, expirationStatus OK; zwei Batches desselben Produkts werden zu einem Eintrag summiert; das früheste Ablaufdatum von mehreren Batches wird korrekt gewählt (teste mit den Daten NICHT in chronologischer Eingabereihenfolge); ein Produkt mit `name = null` bekommt "Unknown"; `discontinued` wird 1:1 durchgereicht; die Ausgabe-Reihenfolge entspricht der Eingabe-Reihenfolge der `products`-Liste (kein eigenes Sortieren hier — das macht die UI-Schicht).

- [ ] **Step 2: Fehlschlag bestätigen, implementieren, grün.**

- [ ] **Step 3: Gesamtlauf und Commit**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Commit-Nachricht: erklären, warum `evaluateQuantityExpression` echte Operatorrangfolge braucht (NSExpression-Parität) obwohl der iOS-Quellcode-Kommentar "links-nach-rechts" behauptet — das ist ein dokumentierter Fund, kein Kopierfehler.

---

### Task 2: Datenmodelle

**Vorher lesen:** `Models/Warehouse.swift` vollständig.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/models/Models.kt`

**Interfaces:**
- Consumes: nichts Neues.
- Produces (alle als `@Serializable data class` bzw. `enum class`, Namenskonvention wie der Rest der Datei — `SerialName` für snake_case-Spalten):
  - `Warehouse` **erweitern** um `@SerialName("company_id") val companyId: String` (fehlt aktuell — nötig für jeden Insert-Pfad, da `company_id` NOT NULL auf `warehouses`, `warehouse_stock_batches`, `warehouse_transactions`, `suppliers` ist).
  - `WarehouseStockBatch` **erweitern** um `@SerialName("supplier_id") val supplierId: String? = null`.
  - `Supplier(val id: String, val name: String)`
  - `WarehouseTransactionInsert` — Encodable-Payload für den Insert, Felder wie iOS' `InsertWarehouseTransaction` (Z. 122-153 in `Models/Warehouse.swift`): `warehouseId, productId, transactionType, quantityChange, userId, batchId, notes, companyId, quantityBefore, quantityAfter, batchNumber, expirationDate, supplierId` — alle nullable außer den ersten fünf Pflichtfeldern; `@SerialName` exakt wie iOS' `CodingKeys`.
  - `WarehouseStockBatchInsert` — Encodable-Payload wie iOS' `InsertStockBatch` (Z. 101-119): `warehouseId, productId, quantity, batchNumber, expirationDate, companyId, supplierId`.
  - `IntakeEntry(val id: String, val productId: String, val productName: String, val imagePath: String?, val quantity: Int, val supplierName: String?, val createdAt: String)` — UI-Modell, nicht direkt Supabase-serialisiert (wird aus einer Decode-Zwischenform in der Repository-Schicht gebaut, siehe Task 3).
  - `WarehouseProductSummary(val productId: String, val productName: String, val imagePath: String?, val totalQuantity: Int, val batchCount: Int, val earliestExpiration: String?, val discontinued: Boolean, val expirationStatus: WarehouseIntakeLogic.ExpirationStatus)` — reines UI-Modell (kein `@Serializable`), Ergebnis von Task 1's `buildProductSummaries`.
    - `val isLow: Boolean get() = totalQuantity > 0 && totalQuantity < 10`
    - `val isOutOfStock: Boolean get() = totalQuantity == 0`
  - `enum class AdjustReason(val raw: String) { REFILL_RETURN("adjustment_refill_return"), CORRECTION("adjustment_correction"), DAMAGE("adjustment_damage"), EXPIRED("adjustment_expired") }`

- [ ] **Step 1: Modelle hinzufügen/erweitern, kompilieren lassen.** Keine eigene Test-Datei nötig — reine Datenklassen ohne Verhalten; Task 1's Test deckt die einzige Logik ab (`buildProductSummaries`), die diese Typen nutzt. Falls Task 1 einen lokalen Platzhalter-Typ verwendet hatte, jetzt auf `WarehouseProductSummary` umstellen und Task-1-Tests erneut grün laufen lassen.

- [ ] **Step 2: Commit.**

---

### Task 3: Repository — Lesepfade (Bestandsübersicht)

**Vorher lesen:** `WarehouseViewModel.swift` Z. 105-263 (`loadWarehouses`, `loadProductSummaries`, `loadAssignedProductIds`, `loadProducts`).

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/WarehouseRepository.kt`

**Interfaces:**
- Consumes: `WarehouseIntakeLogic.buildProductSummaries` (Task 1), `models.Warehouse/WarehouseProductSummary` (Task 2), `TrayRepository.fetchProducts()` (bereits vorhanden, `data/TrayRepository.kt:86` — **wiederverwenden statt duplizieren**, iOS' eigener `.or("discontinued.is.null,discontinued.eq.false")`-Filter ist funktional äquivalent zum vorhandenen `eq("discontinued", false)`, da das Android-`Product`-Modell `discontinued` beim Decodieren bereits auf `false` defaultet).
- Produces:
  - `suspend fun fetchWarehouses(): Result<List<Warehouse>>` — **bestehende Funktion erweitern**: `company_id` mit ins Select aufnehmen (`"id, name, address, notes, company_id"`), sonst unverändert.
  - `suspend fun fetchProductSummaries(warehouseId: String): Result<List<WarehouseProductSummary>>` — holt `products` (via `TrayRepository.fetchProducts()`) und `warehouse_stock_batches` (Spalten `product_id, quantity, expiration_date`, gefiltert `warehouse_id = warehouseId AND quantity > 0`) parallel oder sequenziell, übergibt beides an `WarehouseIntakeLogic.buildProductSummaries` mit `today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date`.
  - `suspend fun fetchAssignedProductIds(): Result<Set<String>>` — `machine_trays.select("product_id").filter { isNotNull("product_id") }`, dedupliziert zu einem Set.

- [ ] **Step 1: Implementieren** (kein TDD hier — reine Netzwerk-Glue-Funktionen, matcht das bestehende Muster in `TrayRepository`/`MachineRepository`, die auch keine eigenen Tests haben).
- [ ] **Step 2: Build grün.**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

- [ ] **Step 3: Commit.**

---

### Task 4: Repository — Schreibpfade (Wareneingang, Lieferanten, Barcode)

**Vorher lesen:** `WarehouseViewModel.swift` Z. 332-530 (`loadSuppliersForIntake`, `prefillSupplier`, `resolveOrCreateSupplier`, `lookupBarcode`, `bookIntake`).

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/WarehouseRepository.kt`

**Interfaces:**
- Consumes: `models.Supplier/WarehouseStockBatchInsert/WarehouseTransactionInsert` (Task 2).
- Produces:
  - `suspend fun fetchSuppliers(): Result<List<Supplier>>` — `suppliers.select("id, name").order("name")`.
  - `suspend fun resolveOrCreateSupplier(name: String, companyId: String, existing: List<Supplier>): Result<Supplier>` — Groß-/Kleinschreibung-unabhängiger Namensabgleich gegen `existing` zuerst (kein Netzwerk nötig, wenn Treffer); sonst `INSERT INTO suppliers (name, company_id)` mit `.select("id, name")`; bei einem Unique-Constraint-Fehler (Race mit einem anderen Client, der denselben Namen gerade anlegt) einmal `fetchSuppliers()` neu laden und erneut nach Namen matchen, statt hart zu scheitern — matcht iOS' Fehlerbehandlung in `resolveOrCreateSupplier` (Z. 396-401).
  - `suspend fun lookupBarcode(barcode: String): Result<String?>` — `product_barcodes.select("product_id").eq("barcode", barcode).limit(1)`, gibt die `product_id` der ersten Zeile zurück oder `null` bei keinem Treffer (kein `Result.failure` bei "nicht gefunden" — das ist ein normaler, erwarteter Fall, kein Fehler).
  - `suspend fun bookIntake(warehouseId: String, companyId: String, productId: String, quantity: Int, batchNumber: String?, expirationDate: String?, supplierId: String?): Result<Unit>` — 1) INSERT in `warehouse_stock_batches` mit `.select("id")` um die generierte `id` zurückzubekommen, 2) `SupabaseService.client.auth.currentUserOrNull()?.id` für `user_id` (bereits genutztes Muster, siehe `AuthRepository.currentUserId` in `data/AuthRepository.kt`), 3) INSERT in `warehouse_transactions` mit `transaction_type = "intake"`, `quantity_change = quantity`, `quantity_before/after = null`, `notes = batchNumber?.let { if (it.isBlank()) null else "Batch: $it" }`, `batch_id` = die in Schritt 1 erzeugte id. **Keine Merge-Logik** (siehe Global Constraints).
  - `suspend fun fetchBatchesForProduct(warehouseId: String, productId: String): Result<List<WarehouseStockBatch>>` — `warehouse_stock_batches`, gefiltert `warehouse_id`, `product_id`, `quantity > 0`, sortiert `expiration_date ASC`.
  - `suspend fun adjustBatch(warehouseId: String, companyId: String, batchId: String, quantityChange: Int, reason: AdjustReason, notes: String?): Result<Unit>` — 1) aktuelle Charge lesen (`product_id, quantity, batch_number, expiration_date, supplier_id`, `.single()`-Äquivalent: `.decodeSingle<...>()`), 2) `quantityAfter = max(0, quantityBefore + quantityChange)`, UPDATE der Charge, 3) INSERT `warehouse_transactions` mit `transaction_type = reason.raw`, `quantity_before`/`quantity_after` diesmal befüllt (anders als bei `bookIntake`).
  - `suspend fun deductWarehouseStockFifo(warehouseId: String, productId: String, quantity: Int, userId: String?, referenceId: String?, notes: String, metadata: Map<String, String?> = emptyMap()): Result<Unit>` — RPC-Aufruf `deduct_warehouse_stock_fifo` mit den 7 benannten Parametern exakt wie in `RefillWizardViewModel.swift:1740-1761` (siehe Recherche-Notiz unten). **Wird von diesem Plan nicht aufgerufen** (siehe Ausgangslage) — reine Bereitstellung für eine spätere Anbindung an `RefillViewModel`.

Exakter RPC-Aufruf (Parameter-Namen sind bindend, nicht umbenennen):
```
p_warehouse_id: String (UUID)
p_product_id:   String (UUID)
p_quantity:     Int
p_user_id:      String? (UUID)
p_reference_id: String?
p_notes:        String
p_metadata:     JSON-Objekt, z. B. {"tour_id": "..."} — nullable Werte im Objekt sind ok
```

- [ ] **Step 1: Implementieren.**
- [ ] **Step 2: Build grün, Commit.**

---

### Task 5: WarehouseViewModel

**Vorher lesen:** `WarehouseViewModel.swift` vollständig (nochmal, jetzt mit Fokus auf `@Published`-Zustand, `filteredSummaries`, `loadAll()`, `selectWarehouse(_:)`).

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/WarehouseViewModel.kt`

**Interfaces:**
- Consumes: alle Task-3/4-Repository-Funktionen, `models.WarehouseProductSummary/Warehouse/Supplier/IntakeEntry/AdjustReason`, `WarehouseIntakeLogic.evaluateQuantityExpression`.
- Produces:
  - `data class WarehouseUiState(val isLoading: Boolean = true, val warehouses: List<Warehouse> = emptyList(), val selectedWarehouseId: String? = null, val productSummaries: List<WarehouseProductSummary> = emptyList(), val recentIntakes: List<IntakeEntry> = emptyList(), val drilldownBatches: List<WarehouseStockBatch> = emptyList(), val isLoadingBatches: Boolean = false, val isAdjustingBatch: Boolean = false, val products: List<Product> = emptyList(), val suppliers: List<Supplier> = emptyList(), val assignedProductIds: Set<String> = emptySet(), val searchText: String = "", val includeOutOfStock: Boolean = false, val includeArchived: Boolean = false, val expirationFilter: ExpirationFilter = ExpirationFilter.ALL, val isBookingIntake: Boolean = false, val error: String? = null)`
  - `enum class ExpirationFilter { ALL, EXPIRING_SOON, CRITICAL }`
  - `val WarehouseUiState.filteredSummaries: List<WarehouseProductSummary>` (Extension-Property oder Methode auf dem State) — portiert `filteredSummaries` (Swift Z. 78-103) 1:1: Suchtext (case-insensitive Namens-Substring), `includeArchived` blendet `discontinued` aus wenn false, `includeOutOfStock` blendet `isOutOfStock` aus außer das Produkt ist in `assignedProductIds` (außer wenn `includeOutOfStock` true), `expirationFilter` filtert nach `WarehouseIntakeLogic.ExpirationStatus`, am Ende alphabetisch nach `productName` sortiert (case-insensitive) — **Bestand beeinflusst die Sortierung nicht**, matcht iOS' expliziten Kommentar dazu (Z. 74-77).
  - `class WarehouseViewModel : ViewModel()` mit `uiState: StateFlow<WarehouseUiState>` und Funktionen: `loadAll()`, `selectWarehouse(id: String)`, `updateSearch(text: String)`, `toggleIncludeOutOfStock()`, `toggleIncludeArchived()`, `setExpirationFilter(filter: ExpirationFilter)`, `loadBatchesForProduct(productId: String)`, `bookIntake(productId: String, quantityText: String, batchNumber: String?, expirationDateIso: String?, supplierName: String?)`, `adjustBatch(batchId: String, productId: String, quantityChange: Int, reason: AdjustReason, notes: String?)`, `lookupBarcode(barcode: String, onFound: (productId: String) -> Unit, onNotFound: () -> Unit)`.

`loadAll()` orchestriert (mirrort iOS' `loadAll()`, Z. 406-421): zuerst `fetchWarehouses()` (setzt `selectedWarehouseId` auf das erste Lager falls noch keins gewählt), dann parallel `fetchProductSummaries`, `fetchRecentIntakes` (Task 3 hat diese Funktion noch nicht — **in Task 3 ergänzen**, siehe Korrektur unten), `fetchAssignedProductIds`, `fetchSuppliers`. `TrayRepository.fetchProducts()` wird einmalig für das Wareneingang-Produkt-Picker-Feld geladen.

**Korrektur zu Task 3:** `fetchRecentIntakes(warehouseId: String): Result<List<IntakeEntry>>` fehlt oben in Task 3 — dort ergänzen (nicht hier neu einführen): `warehouse_transactions.select("id, product_id, quantity_change, created_at, notes, products(name, image_path), suppliers(name)")`, gefiltert `warehouse_id = warehouseId AND transaction_type = "intake"`, sortiert `created_at DESC`, `limit(10)`.

`bookIntake` in diesem ViewModel ruft zuerst `WarehouseIntakeLogic.evaluateQuantityExpression(quantityText)` auf — bei `null` sofort abbrechen (Formular bleibt offen, kein Repository-Call). Bei einem `supplierName`, der nicht leer ist und keiner vorhandenen `Supplier.name` entspricht, zuerst `resolveOrCreateSupplier` aufrufen, dann erst `WarehouseRepository.bookIntake` mit der resultierenden `supplierId`. Nach Erfolg: Formularfelder zurücksetzen (im Composable-State, nicht hier — siehe Task 8) und `fetchProductSummaries` + `fetchRecentIntakes` neu laden.

- [ ] **Step 1: Implementieren.** Keine eigene Unit-Test-Datei nötig für die reine Orchestrierung (matcht `MachinesViewModel`/`MachineDetailViewModel`, die ebenfalls ungetestet sind — die testbare Logik sitzt in Task 1). Falls beim Schreiben eine nicht-triviale, eigenständige Entscheidung auffällt (z. B. die genaue `filteredSummaries`-Filterkombination), erwäge sie nach `WarehouseIntakeLogic` zu verschieben und dort zu testen — Ermessensentscheidung des Umsetzers, wie in Phase 3 gehandhabt.
- [ ] **Step 2: Build grün, Commit.**

---

### Task 6: Navigation — neuer "Lager"-Tab

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/navigation/TopLevelDestination.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/Routes.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/Navigation.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Produces: `Routes.WAREHOUSE = "warehouse"`, `TopLevelDestination.WAREHOUSE` (Icon-Vorschlag: `Icons.Filled.Warehouse`/`Icons.Outlined.Warehouse` falls in `material-icons-extended` vorhanden, sonst `Icons.Filled.Inventory`/`Outlined.Inventory` — **prüfen, welches Icon bereits durch die vorhandene `Icons.Filled.Inventory2`-Nutzung auf dem `REFILL`-Tab NICHT kollidiert**, ein anderes Icon als Refill wählen), `R.string.nav_warehouse` ("Lager"/"Warehouse" — Sprache je Datei).

**Wichtig:** `TopLevelDestination.kt`s eigener Kommentar sagt "Material allows at most five entries" — mit Dashboard, Machines, Refill, Warehouse sind es 4, also noch innerhalb des Limits. Deklarationsreihenfolge ist Anzeigereihenfolge — Warehouse **nach** `REFILL` anhängen (verifizierte iOS-Reihenfolge aus `ios/VMflow/Navigation/CompactTabView.swift:13-63`: Dashboard, Machines, Refill, Warehouse, More — Warehouse ist der vorletzte Tab, nicht der dritte). iOS nutzt `shippingbox.fill` als Symbol; Android-Äquivalent `Icons.Filled.Warehouse`/`Outlined.Warehouse` falls in den Compose-Material-Icons verfügbar, sonst `Icons.Filled.Inventory`/`Outlined.Inventory` (bewusst **nicht** `Inventory2`, das ist bereits für `REFILL` vergeben).

- [ ] **Step 1: Route + Destination + Strings hinzufügen, `composable(Routes.WAREHOUSE) { WarehouseScreen() }` in `Navigation.kt` registrieren** (der eigentliche `WarehouseScreen`-Composable kommt in Task 7 — für diesen Task reicht ein Platzhalter-Aufruf, der in Task 7 durch die echte Implementierung ersetzt wird, oder beide Tasks in einer Sitzung erledigen).
- [ ] **Step 2: Build grün.** Auf dem Gerät verifizieren, dass der neue Tab erscheint und (auch mit Platzhalter-Inhalt) anwählbar ist, bevor mit Task 7 fortgefahren wird.
- [ ] **Step 3: Commit.**

---

### Task 7: WarehouseScreen — Gerüst (Lager-Auswahl, Tab-Umschalter)

**Vorher lesen:** `WarehouseView.swift` Z. 1-105.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/WarehouseScreen.kt`

**Interfaces:**
- Consumes: `WarehouseViewModel` (Task 5).
- Produces: `@Composable fun WarehouseScreen(viewModel: WarehouseViewModel = viewModel())`.

Struktur (matcht `MachineDetailScreen.kt`s bestehendes Muster für Tab-Leisten): Lager-Auswahl nur sichtbar wenn `warehouses.size > 1` (ein `Dropdown`/`ExposedDropdownMenuBox`, kein Picker-Sheet — Android-idiomatisch statt 1:1 iOS-Picker), darunter ein zweigeteilter `TabRow` ("Bestand"/"Wareneingang", Strings neu anlegen), darunter je nach Tab `WarehouseStockTab` (Task 9) oder `WarehouseIntakeTab` (Task 10). Leerer Zustand (keine Lager) und Ladezustand analog zu `MachineDetailScreen`s bestehenden Mustern.

- [ ] **Step 1: Implementieren, den Task-6-Platzhalter ersetzen.**
- [ ] **Step 2: Auf dem Gerät verifizieren:** Tab erscheint, Lager lädt, Umschalten zwischen Bestand/Wareneingang funktioniert (auch wenn die Tab-Inhalte noch leer/rudimentär sind — die kommen in Task 9/10).
- [ ] **Step 3: Commit.**

---

### Task 8: Barcode-Scanner verallgemeinern

**Vorher lesen:** `QrScannerSheet.kt` vollständig — der Docstring dort sagt bereits ausdrücklich, dass diese Komponente für den Lager-Barcode-Fall wiederverwendet werden soll.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/components/QrScannerSheet.kt`

**Interfaces:**
- Produces: `QrScannerSheet` bekommt einen neuen Parameter `formats: Set<Int> = setOf(Barcode.FORMAT_QR_CODE)` (Default erhält das bestehende Verhalten für den Server-Provisioning-Aufrufer unverändert). Der Analyzer-Filter (aktuell `code.format == Barcode.FORMAT_QR_CODE`, Zeile ~145) wird zu `code.format in formats`. Titel/Hint-Strings (`qr_scan`, `qr_scan_hint`) bleiben als Default-Parameter überschreibbar, damit der Wareneingang-Aufrufer eigenen Text zeigen kann ("Barcode scannen" statt "QR-Code scannen") — als weitere optionale `title`/`hint`-String-Parameter mit den bisherigen Strings als Default.
- Für den Lager-Aufruf: `formats = setOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39)` (matcht die in der Recherche genannten iOS-AVFoundation-Formate, ohne QR — Wareneingang scannt Produkt-Strichcodes, keine QR-Codes).

- [ ] **Step 1: Umbauen, bestehenden Aufrufer (`AddEditServerSheet.kt`, Server-QR-Scan) auf unverändertes Verhalten prüfen — sein Aufruf darf keine neuen Parameter brauchen (Defaults greifen).**
- [ ] **Step 2: Build grün. Auf dem Gerät verifizieren, dass der bestehende Server-QR-Scan-Flow (Login/Server-Einrichtung) weiterhin unverändert funktioniert** — das ist der eine Regressionsrisiko-Punkt in diesem Task.
- [ ] **Step 3: Commit.**

---

### Task 9: Bestand-Tab (Stock)

**Vorher lesen:** `WarehouseView.swift`, den `stockOverviewTab`-Abschnitt (nach `evaluateExpression` suchen, dann rückwärts zum `stockOverviewTab`-Property scrollen) und `stockFilterMenu`.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/WarehouseStockTab.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `WarehouseUiState.filteredSummaries` (Task 5), `ProductImage` (bestehende Komponente, `ui/components/ProductImage.kt`), `WarehouseIntakeLogic.ExpirationStatus`.
- Produces: `@Composable fun WarehouseStockTab(uiState: WarehouseUiState, onSearchChange: (String) -> Unit, onToggleOutOfStock: () -> Unit, onToggleArchived: () -> Unit, onExpirationFilterChange: (ExpirationFilter) -> Unit, onProductClick: (productId: String) -> Unit)`.

Inhalt: Suchfeld (`OutlinedTextField`), Filter-Menü (Overflow-Icon oder `FilterChip`-Reihe für die drei Umschalter + den `ExpirationFilter`-Dreifachzustand), `LazyColumn` mit einer Zeile je `WarehouseProductSummary`: Produktbild, Name, `discontinued` → "DC"-Badge (matcht das in Task 28/`MachineDeficits` bereits etablierte Muster), Gesamtmenge (rot wenn `isOutOfStock`, matcht iOS), Chargen-Anzahl, Ablaufdatum-Badge farbcodiert nach `expirationStatus` (rot=CRITICAL, orange=WARNING, kein Badge=OK — **Farben aus `MaterialTheme.colorScheme` NICHT direkt ableiten, feste Farbtöne mit eigenen Hell/Dunkel-Werten nutzen**, siehe die in Phase 3 gefundene Falle, `ui/theme/Color.kt` hat bereits passende `StockRed`/`StockOrange`). Tap auf eine Zeile ruft `onProductClick` (öffnet den Chargen-Drilldown, Task 11).

- [ ] **Step 1: Implementieren.**
- [ ] **Step 2: Auf dem Gerät verifizieren** mit echten Lagerdaten (Docker-Stack, siehe Testumgebung oben): Liste zeigt Produkte, Suche filtert, Filter-Umschalter wirken, Ablauf-Badges stimmen mit echten `expiration_date`-Werten in der Dev-DB überein.
- [ ] **Step 3: Commit.**

---

### Task 10: Wareneingang-Tab (Incoming) inkl. Barcode-Scan-Button und Lieferanten-Autocomplete

**Vorher lesen:** `WarehouseView.swift`, den `incomingTab`-Abschnitt vollständig, plus `submitIntake()` (Z. 559 ff.).

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/WarehouseIntakeTab.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `WarehouseViewModel.bookIntake` (Task 5), `QrScannerSheet` mit Barcode-Formaten (Task 8), `WarehouseIntakeLogic.evaluateQuantityExpression` (für Live-Vorschau des ausgewerteten Werts während der Eingabe, matcht iOS' `onChange`-Handler Z. 440).
- Produces: `@Composable fun WarehouseIntakeTab(uiState: WarehouseUiState, onSubmit: (productId: String, quantityText: String, batchNumber: String?, expirationIso: String?, supplierName: String?) -> Unit, onScanRequested: () -> Unit, ...)`.

Formularfelder: Produkt-Auswahl (Suchfeld + Liste aus `uiState.products`, kein Dropdown — die Produktliste kann lang sein), Mengen-Textfeld mit Live-Auswertung via `WarehouseIntakeLogic.evaluateQuantityExpression` (zeigt das ausgewertete Ergebnis unter dem Feld, z. B. "= 24" bei Eingabe "2*12"; Submit-Button deaktiviert wenn `evaluateQuantityExpression(text) == null`), Chargennummer (optional, Freitext), Ablaufdatum: **Material3 `DatePickerDialog`/`rememberDatePickerState` nutzen** (Android-idiomatisch, nicht iOS' maskiertes Textfeld nachbauen — funktional gleichwertig: liefert ein valides ISO-Datum oder nichts, mit sinnvoller Untergrenze "nicht in der Vergangenheit"), Lieferant (Autocomplete-Textfeld gegen `uiState.suppliers`, Freitext-Eingabe erlaubt für Neuanlage — matcht `resolveOrCreateSupplier`), Barcode-Scan-Button öffnet `QrScannerSheet` mit den Barcode-Formaten aus Task 8; bei Treffer `viewModel.lookupBarcode(code, onFound = { productId -> Produkt-Auswahl vorbefüllen }, onNotFound = { Fehlermeldung "Kein Produkt für diesen Barcode gefunden" })`.

Darunter: die letzten 10 Wareneingänge (`uiState.recentIntakes`) als einfache Liste (Produktbild, Name, Menge, Lieferant, Zeit) — kein eigener Task, da klein genug für denselben Task.

- [ ] **Step 1: Implementieren.**
- [ ] **Step 2: Auf dem Gerät verifizieren:** kompletten Wareneingang-Flow durchspielen (Produkt wählen ODER scannen, Menge inkl. Ausdruck wie "2*6" eingeben, optional Charge/Ablaufdatum/Lieferant, absenden), danach per SQL/Studio prüfen dass `warehouse_stock_batches` und `warehouse_transactions` korrekt befüllt wurden (insbesondere `transaction_type = "intake"`, `quantity_before/after` beide `null`). Scan-Flow mit einem echten Barcode auf einer Produktverpackung oder einem gedruckten Test-EAN-13 testen.
- [ ] **Step 3: Commit.**

---

### Task 11: Chargen-Drilldown + Korrektur-Sheet

**Vorher lesen:** `ProductBatchesView.swift` und `BatchAdjustSheet.swift` vollständig.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/BatchDrilldownSheet.kt`
- Create: `android/app/src/main/java/xyz/vmflow/ui/warehouse/BatchAdjustSheet.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `WarehouseViewModel.loadBatchesForProduct`/`adjustBatch` (Task 5), `models.AdjustReason`.
- Produces: `@Composable fun BatchDrilldownSheet(productName: String, batches: List<WarehouseStockBatch>, isLoading: Boolean, onAdjust: (batchId: String) -> Unit, onDismiss: () -> Unit)`, `@Composable fun BatchAdjustSheet(batch: WarehouseStockBatch, onConfirm: (quantityChange: Int, reason: AdjustReason, notes: String?) -> Unit, onDismiss: () -> Unit)`.

`BatchAdjustSheet`: signiertes Mengenfeld (+/- Vorzeichen wählbar oder ein Vorzeichen-Toggle + positive Zahl — Ermessensentscheidung des Umsetzers, Hauptsache das Ergebnis ist ein korrektes `quantityChange: Int`), `AdjustReason`-Auswahl als vier Radio-Optionen/Chips (deutsche/englische Labels neu in strings.xml), optionales Notizfeld. Bestätigungsdialog **nicht** nötig (iOS hat keinen — direktes Absenden aus dem Sheet, matcht den bestehenden `MachineAnalysisView`-Präzedenzfall nicht zwingend, da Chargenkorrektur häufiger und reversibler ist als ein Produkttausch — Ermessensentscheidung, aber Standard: kein zusätzlicher Dialog, das Sheet selbst ist die Bestätigung).

- [ ] **Step 1: Implementieren, aus `WarehouseStockTab`s `onProductClick` verdrahten (Sheet-Aufruf ergänzen, den Task 9 an dieser Stelle als TODO/offen gelassen hat).**
- [ ] **Step 2: Auf dem Gerät verifizieren:** Chargen eines Produkts mit mehreren Batches anzeigen (nach `expiration_date ASC` sortiert), eine Korrektur mit jedem der vier Gründe durchspielen, per SQL prüfen dass `quantity_before`/`quantity_after` korrekt gesetzt sind und die Menge nie negativ wird (Testfall: Korrektur mit einem Delta größer als der aktuelle Bestand → Menge landet bei 0, nicht negativ).
- [ ] **Step 3: Commit.**

---

### Task 12: Texte, Lokalisierungs-Sweep, Abschluss

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml` (finaler Sweep über alle in Tasks 6-11 hinzugefügten Strings)

- [ ] **Step 1:** Wie in Task 30 der vorigen Phase — alle in dieser Phase neu hinzugekommenen Kotlin-Dateien auf hardcodierte, nutzersichtbare Strings prüfen (`grep -rn 'Text("' android/app/src/main/java/xyz/vmflow/ui/warehouse/` als Startpunkt, plus jede `contentDescription = "..."` mit Literal statt `stringResource`), und `values/strings.xml`/`values-de/strings.xml` auf Schlüssel-Parität prüfen (jeder in dieser Phase hinzugefügte Schlüssel existiert in beiden Dateien mit identischer Platzhalter-Form).
- [ ] **Step 2: Gesamter Build + Testlauf, dann eine vollständige On-Device-Verifikation des kompletten Lager-Moduls** (Bestand-Tab mit Filtern, Wareneingang inkl. Scan, Chargenkorrektur — alle drei aus Tasks 9-11 nochmal im Zusammenspiel, nicht nur isoliert wie in den einzelnen Task-Verifikationen).
- [ ] **Step 3: Commit.**

---

## Abnahme der Phase

Build grün, alle Tests grün, und am S10 angemeldet geprüft: neuer "Lager"-Tab erreichbar und in der Navigationsleiste korrekt hervorgehoben; Bestand-Tab zeigt echte Lagerdaten mit funktionierender Suche/Filtern/Ablauf-Badges; Wareneingang funktioniert vollständig inkl. Mengen-Ausdruck, optionaler Charge/Ablaufdatum/Lieferant, und Barcode-Scan (sowohl Treffer als auch Kein-Treffer-Fall); Chargenkorrektur mit allen vier Gründen funktioniert und verhindert negative Bestände; `deductWarehouseStockFifo` ist als Repository-Funktion vorhanden und durch einen manuellen RPC-Test (z. B. via Supabase Studio oder ein Test-Aufruf aus einer Scratch-Umgebung) verifiziert, aber **nicht** in `RefillViewModel` verdrahtet.
