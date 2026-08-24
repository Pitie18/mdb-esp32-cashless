# Android Phase 5a: Refill-Tour auf iOS-Stand — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Android-Refill-Wizard von einem lager-blinden Drei-Schritt-Rumpf auf den fachlichen Stand von iOS' `RefillWizardViewModel` bringen: Packen aus einem gewählten Lager mit echter Bestandsdeckelung, FIFO-Abbuchung **nur für tatsächlich gepackte Ware**, atomare, idempotente Tray-Buchung über `refill_machine_trays`, vollständiges `activity_log`-Schreiben (Tour-Start, Befüllung, Skip) und eine unterbrechbare/fortsetzbare Tour.

**Architecture:** Wie Phase 2-4. Sämtliche Mengen-, Deckelungs- und Abbuchungsmathematik wandert in ein reines, testbares `RefillTourLogic`-Objekt (kein Supabase, keine Coroutines) — das ist die Stelle, an der iOS Geld verloren hat, also die Stelle mit Tests. Netzwerk in `RefillRepository` (Lese- und Schreibpfade getrennt). Persistenz der laufenden Tour über die bereits existierende `KeyValueStore`-Naht aus `ServerStore.kt`. Oberfläche in Compose/Material 3, ein Composable je Wizard-Schritt, die Schritt-Composables sprechen **nie** direkt mit dem ViewModel (gleiche Trennung wie im Lager-Modul aus Phase 4).

**Tech Stack:** Kotlin 2.4.10 · AGP 9.3.1 · Gradle 9.5 · Compose BOM 2026.06.01 · compileSdk 36 · JUnit 4 · kotlinx.serialization

## Ausgangslage

| | iOS | Android heute |
|---|---:|---:|
| `RefillWizardViewModel` | 2.146 LOC | `RefillViewModel.kt`, **179 LOC** |
| Repository | (macht alles inline im ViewModel) | `RefillRepository.kt`, **53 LOC** |
| Views | 7 Dateien, ~131 KB (`PackingStepView` allein 36 KB) | 3 Dateien, 823 LOC |
| Lagerbindung | Lagerauswahl, Bestandsdeckelung, FIFO-Abbuchung | **keine** — `grep -n "arehouse" ui/refill/*.kt data/RefillRepository.kt` → kein Treffer |
| Tray-Buchung | `refill_machine_trays`-RPC, atomar + idempotent, 3× Retry | Schleife aus einzelnen `UPDATE`s, **Rückgabewert wird verworfen** (`RefillViewModel.kt:151`) |
| `activity_log` | `tour_started`, `stock_refill_tour`, `stock_refill_tour_skip` | **schreibt nichts** — die Tour ist im Verlauf unsichtbar |
| Tour fortsetzen | `PersistedTourState` in einer Datei, Resume-Abfrage | **nichts** — App-Kill mitten in der Tour verliert alles |
| Packen | produktzentriert, Mengen pro Maschine, Chips, Lager-Pickreihenfolge | maschinenzentrierte Checkbox-Liste, keine Mengen |

**Drei Befunde aus dem Ist-Stand, die dieser Plan behebt und die der Umsetzer kennen muss:**

1. `RefillViewModel.nextMachine()` ruft `RefillRepository.applyRefill(current.items)` auf und **ignoriert das `Result`** — eine fehlgeschlagene Buchung markiert die Maschine trotzdem als erledigt und rückt weiter. Genau der Fehlerfall, für den serverseitig `refill_machine_trays` gebaut wurde (siehe Migrations-Kommentar in `20260511120000_refill_machine_trays_rpc.sql`).
2. Der Refill-Screen hat **hart codierte englische Strings** (`RefillWizardScreen.kt:29-33`: `"Pack Items"`, `"Refill"`, `"Summary"`) — das Modul wurde vom Lokalisierungs-Sweep der Phasen 3/4 nie erfasst. Task 12 zieht das nach.
3. `ActivityFeedBuilder.kt:87-115` **rendert `stock_refill_tour` und `tour_started` schon vollständig** (inkl. `products[]`-Zeilen), Android hat diese Zeilen nur nie geschrieben. Die Schreibseite muss die dort erwarteten Schlüssel exakt treffen — die Feld-Namen stehen in `models/ActivityFeed.kt:37-42`. `stock_refill_tour_skip` kennt der Builder **nicht**; unbekannte Actions werden still übersprungen (`makeActivityItems` ist ein `mapNotNull`), das ist wie auf iOS und bleibt so.

**Umfangsentscheidung — dieser Plan ist 5a von zwei Plänen.** Die Spec nennt für Refill drei Ziele: *Review-Schritt, Layout-Grid, Ersatzprodukt-Picker* — plus den Abbuchungs-Fix. Dieser Plan liefert die **Tour selbst** (Packen → Befüllen → Zusammenfassung) inklusive des Abbuchungs-Fixes, der atomaren Buchung und des Verlaufs-Schreibens. Der vorgeschaltete **Review-Schritt** (`ReviewStepView.swift`, 19 KB), der **Ersatzprodukt-Picker** (`ReplacementProductPicker.swift`, 25 KB) und das **Maschinen-Layout-Grid** im Befüllen-Schritt (`MachineLayoutGrid.swift`, 16 KB) sind Plan **5b** — sie hängen an der Tour, nicht umgekehrt: iOS überspringt den Review-Schritt vollständig, wenn es keine Ersatzvorschläge gibt (`RefillWizardViewModel.swift:1346-1351`), also ist die Tour ohne ihn ein vollständiger, abnehmbarer Fluss. Das ist eine bewusste Zweiteilung, keine Auslassung.

**Ebenfalls außerhalb beider Pläne:** `resolveTourCash` / die Barkassen-Auflösung am Tour-Ende (`RefillWizardViewModel.swift:2090`). Android hat kein Kassenbuch-Modul, nur eine lesende `CashBookCard` auf dem Dashboard — die Tour-Bargeld-Auflösung setzt `CashBookViewModel.fetchTheoreticalCash` voraus, das es auf Android nicht gibt. Gehört in eine eigene Kassenbuch-Parität-Aufgabe.

## Referenz-Implementierung

| iOS-Datei | Was daraus portiert wird |
|---|---|
| [`ViewModels/RefillWizardViewModel.swift`](../../../ios/VMflow/ViewModels/RefillWizardViewModel.swift) | Alles außer Review/Replacement: Datenstrukturen (Z. 4-230), Pack-Mathematik (Z. 440-940), Laden (Z. 1213-1545), `startTour`/`deductWarehouseStock` (Z. 1652-1800), `confirmRefill`/`applyRefillRPC`/`recordRefillSuccess`/`skipMachine` (Z. 1848-2024), `writeActivityLog` (Z. 2028), Persistenz (Z. 305-437) |
| [`Views/Refill/PackingStepView.swift`](../../../ios/VMflow/Views/Refill/PackingStepView.swift) | Pack-Oberfläche: Lager-Picker, Chip-Leiste, Produktkarte mit Maschinen-Bedarfszeilen, Lagerbestands-Badge, untere Aktionsleiste |
| [`Views/Refill/RefillStepView.swift`](../../../ios/VMflow/Views/Refill/RefillStepView.swift) | Befüllen-Oberfläche: Maschinen-Kopf, Maschinen-Auswahl-Sheet, Tray-Karte, "volle Trays"-Zeile, untere Aktionsleiste. **Das Layout-Grid darin ist 5b** |
| [`Views/Refill/RefillWizardView.swift`](../../../ios/VMflow/Views/Refill/RefillWizardView.swift) | Wizard-Hülle + Schritt-Indikator (Z. 112) |
| [`Views/Refill/RefillSummaryView.swift`](../../../ios/VMflow/Views/Refill/RefillSummaryView.swift) | Zusammenfassung aus dem `tourLog` |

**Umsetzer lesen die für ihren Task genannten iOS-Zeilenbereiche vollständig, bevor sie beginnen.** In den vorigen Phasen haben sie dabei mehrfach echte Fehler in der Plan-Prosa gefunden — das ist erwünscht und gehört in ihren Report.

Zweitreferenz für die fachliche Seite: [`management-frontend/app/composables/useRefillWizard.ts`](../../../management-frontend/app/composables/useRefillWizard.ts). Bei Abweichungen zwischen PWA und iOS gilt **iOS**, weil dort der Abbuchungs-Fix sitzt.

## Global Constraints

- **Keine Backend-Änderungen.** Beide RPCs existieren: `refill_machine_trays(p_machine_id uuid, p_tour_id text, p_trays jsonb)` (Migration `20260511120000`, Ambiguitäts-Fix `20260513120000`) und `deduct_warehouse_stock_fifo` (Migration `20260305000000`). Keine neue Migration, keine Edge-Function.
- **`activity_log.metadata` ist ein typisierter Vertrag über drei Clients hinweg.** Kein Schlüssel darf einen anderen Typ bekommen als iOS/PWA ihn schreiben — ein Objekt-statt-Array-Wechsel hat den iOS-Feed schon einmal zerlegt. Die zu schreibenden Schlüssel sind exakt: `tour_id`, `_user_email`, `_user_display`, `machine_id`, `machine_name`, `warehouse_id`, `warehouse_name`, `machine_count` (Int), `machine_ids` (Array), `machine_names` (Array), `trays_refilled` (Int), `total_added` (Int), `products` (Array aus `{product_id, product_name, quantity}`). Gegenprobe gegen die **Leseseite auf Android selbst**: `models/ActivityFeed.kt:30-60`.
- **Actions:** `tour_started` (entity_id = `tour_id`, entity_type `stock`), `stock_refill_tour` (entity_id = machine_id), `stock_refill_tour_skip` (entity_id = machine_id). Keine neuen Action-Namen erfinden.
- **Abbuchung nur für gepackte Ware.** `deduct_warehouse_stock_fifo` wird ausschließlich für die Schnittmenge aus `packedItems[machineId]` und den Produkt-IDs der Trays dieser Maschine aufgerufen, mit der **gepackten Menge** (`packingQuantity`), nie mit dem Tray-Defizit. Das ist der Fix für den Fehler, der auf iOS über 53 Touren ~334 Einheiten Lagerbestand verbrannt hat.
- Abbuchungsfehler sind **nicht** blockierend (Tour läuft weiter, Fehler wird geloggt) — wie iOS. Buchungsfehler bei `refill_machine_trays` sind **blockierend** (Maschine bleibt in der Tour, Nutzer kann erneut versuchen) — auch wie iOS.
- Alle neuen und alle **bestehenden** Nutzertexte des Refill-Moduls als Ressourcen in `values/strings.xml` **und** `values-de/strings.xml`; Mengen über `<plurals>`. Das Modul ist heute unlokalisiert (siehe Ausgangslage Befund 2).
- Material 3, `stringResource`, `contentDescription` an Icon-Buttons, keine festen Zeilenhöhen, Touch-Targets ≥ 48 dp (Trayzeilen im Refill deutlich größer — Spec).
- `SupabaseService.client` nur über den Getter, nie in einem `val` festgehalten — der Server-Picker tauscht den Client aus.
- Statusfarben **nicht** aus `MaterialTheme.colorScheme` ableiten (`primary`/`secondary`/`tertiary` kollabieren im Dunkelmodus zu identischen Tönen). Feste Tokens aus `ui/theme/Color.kt` verwenden — die Lager-Tokens `StockRed`/`StockOrange` existieren bereits aus Phase 4.
- Nur JUnit 4 im Testpfad. Keine neuen Abhängigkeiten — insbesondere **kein** `datastore-preferences`: Persistenz läuft über die vorhandene `KeyValueStore`-Naht (`data/ServerStore.kt:13-17`). Kein androidx-Bump (`core-ktx` 1.18.0 / `lifecycle` 2.10.0 stehen absichtlich still, die neueren verlangen `minCompileSdk 37`).
- Commits: `git add <pfade>`, dann `git commit` **ohne** Pathspec (ein `git commit -- <pfad>` hebt ein vorheriges `git rm --cached` wieder auf). Niemals `--amend`, `reset`, `rebase` — parallele Sessions committen auf denselben Branch.
- **Das Lager-Modul (`ui/warehouse/*`, `data/WarehouseRepository.kt`) wird nicht umgebaut.** Dieser Plan *ruft* es auf (`fetchWarehouses`, `fetchWarehouseStock`, `deductWarehouseStockFifo` — alle aus Phase 4 vorhanden) und ergänzt höchstens neue Lesefunktionen; bestehende Signaturen bleiben unangetastet, das Lager-UI wird nicht angefasst.

## Testumgebung

Physisches Gerät: Samsung Galaxy S10, `adb -s <Seriennummer aus 'adb devices -l'>`, Android 16 / API 36. Der Emulator `Pixel_9a` ist unbrauchbar (fünfmal gestorben, `system_server` ANR, überlebt `-wipe-data`) — nicht darauf ausweichen.

**Das installierte, angemeldete Gerät zeigt auf `https://supabase-test.kerl-handel.de`, nicht auf den lokalen Stack des Macs.** Eine bereits authentifizierte Installation behält ihre gespeicherte Server-URL; die Build-Zeit-`SUPABASE_URL` wirkt nur auf eine frische, abgemeldete Installation. Konsequenz für die Verifikation: `curl`/`psql` gegen `127.0.0.1:54321` beweist **nichts** über die Schreibvorgänge des Geräts. Beweis führen über einen echten Kaltstart (`adb shell am force-stop xyz.vmflow.app`, dann neu starten) — was danach wieder da ist, kam vom Server. Host im Zweifel prüfen: `adb logcat -d | grep Supabase-Auth`.

Das korrekte Paket ist **`xyz.vmflow.app`** — auf dem Testgerät liegt außerdem ein fremdes, veraltetes `xyz.vmflow`.

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest \
  -PSUPABASE_URL=http://<LAN-IP des Macs>:54321 \
  -PSUPABASE_ANON_KEY=<anon key aus 'supabase status'>
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Bildschirm-Koordinaten:** `adb shell input tap/swipe` erwartet Gerätepixel (S10: 1440×3040), nicht die Koordinaten eines verkleinert dargestellten Screenshots. Im Zweifel `adb shell uiautomator dump` + `bounds="[x1,y1][x2,y2]"`. Ein Swipe nahe dem rechten Rand wird als System-Zurück-Geste gedeutet.

**Umsetzer installieren nicht selbst** — Build und Tests ja, Gerätetest und Screenshots macht der Orchestrator.

---

### Task 1: Reine Logik — Packliste, Mengen-Deckelung, Abbuchungs-Set, Pickreihenfolge

Der fachliche Kern. Alles hier ist rein: keine Coroutines, kein Supabase, keine Android-Klassen. Referenz: `RefillWizardViewModel.swift` Z. 440-500 (Bestands-Reste), Z. 498-612 (`combinedPackingList` inkl. Sortierung), Z. 780-940 (Mengen), Z. 1456-1545 (Pickreihenfolge), Z. 1652-1800 (`startTour`-Verteilung + `deductWarehouseStock`).

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/RefillTourLogic.kt`
- Test: `android/app/src/test/java/xyz/vmflow/data/RefillTourLogicTest.kt`

**Interfaces:**
- Consumes: `models.Tray`, `models.VendingMachineWithEmbedded` (bestehend).
- Produces (exakte Signaturen, spätere Tasks hängen daran):
  - `data class PackedDeduction(val machineId: String, val productId: String, val quantity: Int)`
  - `data class TrayFill(val trayId: String, val fillAmount: Int)`
  - `fun buildCombinedPackingList(machines: List<RefillMachine>, pickOrder: Map<String, Int>): List<CombinedPackingItem>`
  - `fun packingQuantity(machine: RefillMachine, productId: String, customQuantities: Map<String, Map<String, Int>>): Int`
  - `fun committedQuantity(machines: List<RefillMachine>, productId: String, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>): Int`
  - `fun remainingWarehouseStock(machines: List<RefillMachine>, productId: String, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>, warehouseStock: Map<String, Int>): Int`
  - `fun maxPackingQuantity(machines: List<RefillMachine>, machineId: String, productId: String, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>, warehouseStock: Map<String, Int>, stockLoaded: Boolean): Int`
  - `fun displayQuantity(machines: List<RefillMachine>, machineId: String, productId: String, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>, warehouseStock: Map<String, Int>, stockLoaded: Boolean): Int`
  - `fun isOutOfStockForMachine(machines: List<RefillMachine>, machineId: String, productId: String, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>, warehouseStock: Map<String, Int>, stockLoaded: Boolean): Boolean`
  - `fun buildDeductions(machines: List<RefillMachine>, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>): List<PackedDeduction>`
  - `fun applyTourInclusion(machines: List<RefillMachine>, packedItems: Map<String, Set<String>>, customQuantities: Map<String, Map<String, Int>>): List<RefillMachine>`
  - `fun flattenPickOrder(groups: List<WarehousePositionGroup>, positions: List<WarehouseProductPosition>): List<String>`

Die Modelle (`RefillMachine`, `RefillTray`, `CombinedPackingItem`, `MachineNeed`, `WarehousePositionGroup`, `WarehouseProductPosition`) kommen aus Task 2. **Task 1 und Task 2 werden deshalb zusammen umgesetzt** (ein Umsetzer, ein Commit) — sonst kompiliert keiner von beiden allein. Der Umsetzer beginnt mit Task 2's Modellen, dann Task 1's Logik.

Fachliche Regeln, die exakt so gelten (jede einzelne ist ein Testfall):

1. **`packingQuantity`** = gesetzte `customQuantities[machineId][productId]`, sonst Summe der Defizite aller Trays dieser Maschine mit diesem Produkt und `deficit > 0`.
2. **`maxPackingQuantity`** = `min(trayMax, available)` mit `trayMax` = Summe `capacity - currentStock` über alle Trays der Maschine mit diesem Produkt, `available` = `max(0, warehouseStock[productId] - Summe packingQuantity über ANDERE gepackte Maschinen)`. Ist `stockLoaded == false` oder der Bestand leer, gilt nur `trayMax`. Ist `stockLoaded == true`, aber das Produkt kommt im Bestand nicht vor, ist das Maximum **0**.
3. **`displayQuantity`** = bei gepackter Maschine `packingQuantity` (der committete Wert), sonst `min(packingQuantity, maxPackingQuantity)` — die UI darf nie mehr versprechen, als das Lager liefern kann.
4. **`isOutOfStockForMachine`** = `false`, solange kein Bestand geladen ist oder die Maschine dieses Produkt schon gepackt hat (sie hat ihre Zuteilung); sonst `remainingWarehouseStock <= 0`.
5. **`buildDeductions`**: für jede Maschine mit `isPacked`, über die **Schnittmenge** aus `packedItems[machineId]` und den `productId`s ihrer Trays, mit `packingQuantity` als Menge, `quantity > 0` gefiltert. **Nie** über alle Tray-Produkte iterieren — genau das war der iOS-Bug.
6. **`applyTourInclusion`**: unpackte Maschinen → alle Trays `isInTour = false`. Gepackte Maschinen: Trays ohne Produkt bleiben immer `isInTour = true` mit ihrem Ausgangs-`fillAmount` (der Fahrer füllt sie manuell). Trays mit Produkt sind nur in der Tour, wenn das Produkt gepackt wurde — sonst `isInTour = false` **und** `fillAmount = 0`. Ist eine `customQuantity` gesetzt, wird sie **proportional** über alle Trays dieser Maschine mit diesem Produkt und `deficit > 0` verteilt: `ratio = customQty / totalDeficit`, `fillAmount = (deficit * ratio).roundToInt()`, geklemmt auf `0..(capacity - currentStock)`.
7. **`buildCombinedPackingList`**: gruppiert über alle Maschinen mit `isPacked`-unabhängigem Bedarf nach `productId`; je Maschine ein `MachineNeed(machineId, machineName, quantity = Summe der Defizite, capacity = Summe der Kapazitäten)`; `totalQuantity` = Summe über alle Needs.
8. **Sortierung** von `buildCombinedPackingList` — **muss eine totale Ordnung sein**, sonst tauschen Zeilen bei jedem Tastendruck die Plätze (`Map`-Iterationsreihenfolge ist nicht garantiert). Ohne Pickreihenfolge: `totalQuantity` absteigend, dann Name case-insensitive, dann `productId` als letzter Tiebreaker. Mit Pickreihenfolge: positionierte Produkte zuerst nach Position, unpositionierte danach, dann derselbe Name/ID-Tiebreaker.
9. **`flattenPickOrder`**: Gruppenbaum über `parentId`, jede Ebene nach `sortOrder`, Tiefensuche — pro Knoten erst seine Produkt-IDs, dann rekursiv die Kinder. Positionen ohne bekannte Gruppe kommen als "ungrouped" am **Ende**. Gruppen mit unbekanntem `parentId` sind Wurzeln.

- [ ] **Step 1: iOS-Referenz lesen** — die fünf oben genannten Zeilenbereiche in `RefillWizardViewModel.swift`. Abweichungen zwischen dieser Prosa und dem echten Swift-Code gehören in den Report.

- [ ] **Step 2: Die fehlschlagenden Tests schreiben.** Mindestens diese Fälle, jeder mit einem sprechenden Namen:

**Der Regressionstest, um den es in dieser Phase geht** (Spec: *"reduzierte und übersprungene Füllungen dürfen den Lagerbestand nicht belasten"*):
```kotlin
@Test
fun `buildDeductions ignores products the user never packed`() {
    // Maschine mit zwei Trays: Produkt A (Defizit 10), Produkt B (Defizit 5).
    // Gepackt wurde NUR A. Erwartung: genau eine Abbuchung, A mit 10.
}

@Test
fun `buildDeductions uses the reduced custom quantity, not the tray deficit`() {
    // Tray-Defizit 10, customQuantities[m][A] = 3 → Abbuchung 3, nicht 10.
}

@Test
fun `buildDeductions skips machines that were never packed`() {
    // isPacked = false → leere Liste, auch wenn packedItems Einträge hätte.
}

@Test
fun `buildDeductions drops zero quantities`() {
    // customQuantity 0 → kein Eintrag (kein RPC-Aufruf mit quantity 0).
}
```
Dazu: `applyTourInclusion` verteilt eine `customQuantity` proportional über zwei Trays desselben Produkts und klemmt auf die Restkapazität; setzt `fillAmount = 0` und `isInTour = false` für ungepackte Produkte; behält produktlose Trays in der Tour. `maxPackingQuantity` deckelt über zwei Maschinen hinweg (Maschine 2 sieht nur, was Maschine 1 nicht committet hat); gibt 0 zurück, wenn der Bestand geladen ist, das Produkt aber fehlt; ignoriert den Bestand bei `stockLoaded = false`. `displayQuantity` deckelt für ungepackte, nicht für gepackte Maschinen. `isOutOfStockForMachine` ist `false` für eine schon gepackte Maschine. `flattenPickOrder`: verschachtelte Gruppen in Tiefensuche, `sortOrder` je Ebene, ungruppierte am Ende, unbekannte `parentId` als Wurzel. `buildCombinedPackingList`: Sortierung ist stabil und total (zweimal aufrufen → identische Reihenfolge; zwei Produkte mit gleicher `totalQuantity` und gleichem Namen werden per ID getrennt).

- [ ] **Step 3: Tests laufen lassen, Fehlschlag bestätigen** — `cd android && ./gradlew testDebugUnitTest --tests '*RefillTourLogicTest*'`, erwartet: Kompilierfehler bzw. `FAILED`.

- [ ] **Step 4: Modelle (Task 2) und `RefillTourLogic` implementieren**, bis die Tests grün sind. Keine `TODO()`-Rümpfe, keine Funktion ohne Test.

- [ ] **Step 5: Volle Suite** — `./gradlew testDebugUnitTest`, alle 231 Alt-Tests plus die neuen grün.

- [ ] **Step 6: Commit** — `git add` der drei Dateien, dann `git commit -m "feat(android): add RefillTourLogic + refill tour models"`.

---

### Task 2: Modelle (zusammen mit Task 1 umgesetzt)

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/models/Models.kt`

Die bestehenden `RefillItem`/`RefillMachine`/`RefillSummary` werden ersetzt. **Korrektur gegenüber der ersten Planfassung:** sie haben nicht einen, sondern **sechs** Nutzer (`RefillRepository`, `RefillViewModel`, `PackingStep`, `RefillStep`, `RefillSummaryStep`, `Models` selbst) und hängen an einer *live* Navigationsroute — ein Löschen in Task 2 hätte den Baum gebrochen. Umgesetzt wurde deshalb ein rein mechanisches Umbenennen in `LegacyRefillItem`/`LegacyRefillMachine`/`LegacyRefillSummary` (verhaltensneutral, unabhängig verifiziert). **Task 6 löscht diesen `Legacy*`-Block ersatzlos**, sobald das neue ViewModel steht. `RefillSummary` fällt dabei ganz weg (die Zusammenfassung wird in Task 9 aus dem `tourLog` berechnet, wie auf iOS).

Neu bzw. ersetzt, `@Serializable` nur wo Persistenz oder Wire-Format es braucht:

| Modell | Zweck | Wire/Persistenz |
|---|---|---|
| `RefillTray(tray: Tray, fillAmount: Int, isInTour: Boolean = true)` | ein Tray in der Tour; `deficit`, `maxFill = capacity - currentStock`, `targetStock` als abgeleitete Werte | `@Serializable` (Tour-Persistenz) |
| `RefillMachine(machine: VendingMachineWithEmbedded, trays: List<RefillTray>, isPacked: Boolean = false, isRefilled: Boolean = false, isSkipped: Boolean = false)` | plus abgeleitet: `totalDeficit`, `traysNeedingRefill`, `totalCurrentStock`, `totalCapacity`, `stockPercent` | `@Serializable` |
| `MachineNeed(machineId: String, machineName: String, quantity: Int, capacity: Int)` | Bedarf einer Maschine an einem Produkt | – |
| `CombinedPackingItem(productId: String, productName: String, imagePath: String?, sellprice: Double?, totalQuantity: Int, machineNeeds: List<MachineNeed>)` | eine Zeile der Packliste | – |
| `TourLogEntry(machineId: String, machineName: String, traysRefilled: Int, totalAdded: Int, skipped: Boolean)` | Tour-Protokoll, Quelle der Zusammenfassung | `@Serializable` |
| `WarehousePositionGroup(id: String, parentId: String?, sortOrder: Int)` | `@SerialName("parent_id")`, `@SerialName("sort_order")` | Wire |
| `WarehouseProductPosition(productId: String, sortOrder: Int, groupId: String?)` | `@SerialName("product_id")`, `@SerialName("sort_order")`, `@SerialName("group_id")` | Wire |
| `RefillTrayPayload(trayId: String, fillAmount: Int)` | RPC-Eingabe, `@SerialName("tray_id")` / `@SerialName("fill_amount")` | Wire |
| `TrayApplicationResult(trayId: String, oldStock: Int, newStock: Int, fillAmount: Int, wasAlreadyApplied: Boolean)` | RPC-Rückgabe, `@SerialName("tray_id"/"old_stock"/"new_stock"/"fill_amount"/"was_already_applied")` | Wire |
| `PersistedTourState(step: String, machines: List<RefillMachine>, currentMachineIndex: Int, selectedWarehouseId: String?, tourId: String, tourLog: List<TourLogEntry>, savedAt: String)` | Resume-Zustand; `savedAt` als ISO-8601-String (kein `Date`) | `@Serializable` |

**Jeder `@SerialName` wird gegen die echte Quelle geprüft, nicht geraten:** die RPC-Spalten gegen `Docker/supabase/migrations/20260511120000_refill_machine_trays_rpc.sql` (`RETURNS TABLE`-Block), die Positions-Tabellen gegen `20260318200000_warehouse_position_groups.sql` und die `warehouse_product_positions`-Definition. Ergebnis der Prüfung in den Report.

Kompiliert wird zusammen mit Task 1; Commit ebenfalls gemeinsam.

---

### Task 3: Repository — Lesepfade

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt` (Vollersatz des heutigen 53-Zeilers)

**Interfaces:**
- Consumes: `RefillTourLogic.flattenPickOrder`, Modelle aus Task 2, `WarehouseRepository.fetchWarehouses()` / `fetchWarehouseStock(warehouseId)` (Phase 4, unverändert nutzen).
- Produces:
  - `suspend fun fetchRefillMachines(): Result<List<RefillMachine>>`
  - `suspend fun fetchWarehouseStockTotals(warehouseId: String): Result<Map<String, Int>>`
  - `suspend fun fetchPickOrder(warehouseId: String): Result<Map<String, Int>>`

Regeln:
- `fetchRefillMachines` holt Maschinen und Trays wie iOS `loadData` (Z. 1216-1240): `vendingMachine` mit `embeddeds(...)`-Join, `machine_trays` mit `products(name, image_path, discontinued, sellprice)`-Join, nach `item_number` sortiert; baut daraus `RefillMachine`-Objekte mit **allen** Trays der Maschine (nicht nur den defizitären — der Befüllen-Schritt zeigt auch volle Trays). Maschinen ohne Trays fallen heraus. `fillAmount` startet auf `deficit`.
- `fetchWarehouseStockTotals` aggregiert `WarehouseRepository.fetchWarehouseStock(warehouseId)` (Chargen mit `quantity > 0`) zu `productId -> Summe`. **Keine neue Query** — die Phase-4-Funktion filtert schon korrekt.
- `fetchPickOrder` liest `warehouse_position_groups` (`id, parent_id, sort_order`) und `warehouse_product_positions` (`product_id, sort_order, group_id`) für das Lager, jeweils nach `sort_order`, gibt `RefillTourLogic.flattenPickOrder(...)` als `productId -> Index` zurück. Ein Fehler hier ist **nicht** fatal: der Aufrufer (Task 6) behandelt `Result.failure` als leere Map, dann sortiert die Packliste nach Menge. Genau wie iOS' `fetchOrderedProductIdsOrEmpty`.
- `buildRefillPlan` (die alte Funktion) entfällt — die Filterung "welche Maschine braucht Nachschub" macht ab jetzt die Packliste über den Bedarf, nicht eine Vorfilterung.

Keine neuen Unit-Tests (Repository-Glue, entspricht der Konvention dieses Projekts — die Logik steckt in Task 1 und ist dort getestet).

- [ ] **Step 1:** iOS `loadData` Z. 1213-1260 und `loadWarehouseStock` Z. 1381-1441 lesen.
- [ ] **Step 2:** Die drei Funktionen implementieren, `postgrest`-Zugriff über den `SupabaseService.client`-Getter.
- [ ] **Step 3:** `./gradlew compileDebugKotlin testDebugUnitTest` — grün. `buildRefillPlan` und `applyRefill` bleiben in diesem Task **unangetastet stehen**, weil der alte `RefillViewModel` sie noch aufruft; sie fallen erst in Task 6 zusammen mit ihm weg. Der Baum muss nach jedem Task kompilieren.
- [ ] **Step 4: Commit** — `feat(android): add refill read paths (machines+trays, warehouse totals, pick order)`.

---

### Task 4: Repository — Schreibpfade (atomare Buchung, Abbuchung, Verlauf)

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt`

**Interfaces:**
- Produces:
  - `suspend fun refillMachineTrays(machineId: String, tourId: String, trays: List<RefillTrayPayload>): Result<List<TrayApplicationResult>>`
  - `suspend fun deductForTour(warehouseId: String, tourId: String, deductions: List<PackedDeduction>): Result<Unit>`
  - `suspend fun writeTourActivity(action: String, machineId: String?, machineName: String?, tourId: String, warehouseId: String?, extra: Map<String, JsonElement>): Result<Unit>`
- Consumes: `WarehouseRepository.deductWarehouseStockFifo(...)` (Phase 4). **Vor dem Aufruf die dortige Signatur und die `p_metadata`-Behandlung lesen** — sie muss `tour_id` und `_user_email` mitschicken, `p_notes = "Refill tour"`, `p_reference_id = machineId`. Fehlt einer dieser Parameter oder heißt er anders, wird das im Report vermerkt und die Lager-Funktion **erweitert statt umgeschrieben** (bestehende Aufrufer aus Phase 4 dürfen sich nicht ändern).

Regeln:
- `refillMachineTrays` ruft `postgrest.rpc("refill_machine_trays", …)` mit exakt `p_machine_id`, `p_tour_id`, `p_trays` und dekodiert die Rückgabe nach `List<TrayApplicationResult>`. **Kein Retry hier** — der Retry sitzt im ViewModel (Task 9), damit er zusammen mit dem UI-Zustand testbar bleibt und der Repository-Layer eine Ebene bleibt.
- `deductForTour` iteriert die Abbuchungen und ruft je Eintrag die Lager-Funktion. Ein Fehler bricht die Schleife **nicht** ab (Log + weiter), das `Result` ist `success`, solange nichts Unerwartetes geworfen wurde — die Tour darf an einer Abbuchung nicht scheitern.
- `writeTourActivity` schreibt eine `activity_log`-Zeile. Vorbild ist der bereits existierende Schreibpfad `MachineAnalysisRepository.kt:208` (`postgrest.from("activity_log").insert(...)`) — **derselbe Weg, dieselbe company_id-Auflösung**, nicht ein zweiter, eigener Stil. `entity_type = "stock"`, `entity_id = machineId ?: tourId`, `user_id` und `company_id` aus der Session bzw. `organization_members`. Metadaten: `tour_id`, `_user_email`, `_user_display` (Vor-/Nachname aus den User-Metadaten, sonst E-Mail), optional `machine_id`, `machine_name`, `warehouse_id`, plus `extra`. Fehler sind nicht blockierend (loggen, `Result.success`).
- Der Schlüssel heißt wirklich `_user_display` **mit** führendem Unterstrich (und `_user_email` ebenso) — in `models/ActivityFeed.kt:43-45` steht dazu ein Kommentar, weil es schon einmal falsch geraten wurde. Die Leseseite ist über `ActivityFeedBuilderTest.kt` getestet; dieser Task ändert daran nichts, aber ein Tippfehler auf der Schreibseite fällt genau dort auf.
- **Auth vor dem ersten Schreibzugriff auflösen** (nicht danach) — dieselbe Reihenfolge, die im Lager-Review in Phase 4 nachgezogen werden musste: eine abgelaufene Session darf nicht erst schreiben und dann beim Audit-Eintrag scheitern.

- [ ] **Step 1:** `refill_machine_trays`-Migration (Rückgabespalten, `p_trays`-Form) und `WarehouseRepository.deductWarehouseStockFifo` lesen; `MachineAnalysisRepository.kt:190-230` als Vorbild für den `activity_log`-Insert lesen.
- [ ] **Step 2:** Die drei Funktionen implementieren.
- [ ] **Step 3:** `./gradlew compileDebugKotlin testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): add refill write paths (atomic tray RPC, FIFO deduction, activity log)`.

---

### Task 5: Tour-Persistenz (`TourStore`)

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/TourStore.kt`
- Test: `android/app/src/test/java/xyz/vmflow/data/TourStoreTest.kt`

**Interfaces:**
- Consumes: `KeyValueStore` aus `data/ServerStore.kt:13-17` (bestehende Naht, macht das Ganze ohne Android-Runtime testbar), `PersistedTourState` aus Task 2.
- Produces: `class TourStore(private val storage: KeyValueStore)` mit `fun save(state: PersistedTourState)`, `fun load(): PersistedTourState?`, `fun clear()`, `val hasSavedTour: Boolean`.

Regeln (Referenz iOS Z. 305-437):
- Serialisierung über `kotlinx.serialization` mit `ignoreUnknownKeys = true` — ein älterer gespeicherter Zustand darf nach einem App-Update nicht crashen, sondern höchstens verworfen werden.
- `load()` gibt bei kaputtem JSON `null` zurück und **räumt den Schlüssel auf**, statt bei jedem Start erneut daran zu scheitern.
- Gespeichert wird **nur** während `REFILL` und `SUMMARY`. Im Pack-Schritt gibt es nichts zu retten (kein `tourId`, keine Buchung) — iOS hat dafür einen expliziten Guard (Z. 361).
- Ein Zustand älter als 24 h gilt als abgelaufen: `load()` gibt `null` zurück und räumt auf. **Korrektur aus dem Abschluss-Review: das ist keine Abweichung, sondern Parität.** iOS hat dieselben 24 h (`RefillWizardViewModel.swift:314-315`, Kommentar dort: matches web), die PWA ebenfalls (`MAX_AGE_MS` in `useRefillWizard.ts`). Die erste Planfassung behauptete, iOS biete eine gespeicherte Tour unbegrenzt an — das war falsch und stand eine Zeit lang auch so im Code-Kommentar. Wer das liest, soll die Ablaufprüfung nicht aus falsch verstandener Paritätstreue wieder ausbauen.

- [ ] **Step 1: Tests schreiben** — Roundtrip save→load; `load` nach `clear` ist `null`; kaputtes JSON → `null` **und** Schlüssel geleert; Zustand mit `savedAt` vor 25 h → `null`; Zustand mit unbekanntem Zusatzfeld im JSON lädt trotzdem.
- [ ] **Step 2:** Tests laufen lassen, Fehlschlag bestätigen.
- [ ] **Step 3:** `TourStore` implementieren.
- [ ] **Step 4:** `./gradlew testDebugUnitTest` — alles grün.
- [ ] **Step 5: Commit** — `feat(android): persist and resume an in-progress refill tour`.

---

### Task 6: ViewModel Teil 1 — Laden, Lagerauswahl, Zustandsgerüst

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillViewModel.kt` (Vollersatz)
- Modify: `android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt` (nur: `buildRefillPlan` und `applyRefill` entfernen — ab hier unbenutzt)
- Modify: `android/app/src/main/java/xyz/vmflow/models/Models.kt` (den `LegacyRefillItem`/`LegacyRefillMachine`/`LegacyRefillSummary`-Block **ersatzlos löschen** — er existiert nur als Kompilier-Brücke aus Task 2 und hat nach diesem Task keinen Nutzer mehr; er trägt einen entsprechenden Kommentar)

**Interfaces:**
- Produces: `RefillUiState` und `RefillViewModel` mit den in Task 7-9 ergänzten Aktionen. Das `UiState` ist ab hier der Vertrag für die UI-Tasks 10-12.
- **`RefillStep` ist bereits nach `models/Models.kt` gewandert** (Task 5, `@Serializable`) — dieser Task deklariert das Enum **nicht** neu, sondern importiert es. Grund: `TourStore` liegt im Datenlayer und darf nicht aus `ui/` importieren; solange der Schritt ein `String` war, konnte ein Tippfehler im ViewModel dazu führen, dass `TourStore.save()` still nichts speichert. Der Guard ist jetzt ein erschöpfendes `when` über das Enum, also compilergeprüft. 5b stellt `REVIEW` vor `PACKING`.
```kotlin
// aus xyz.vmflow.models importieren, nicht neu deklarieren:
// enum class RefillStep { PACKING, REFILL, SUMMARY }

data class RefillUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val step: RefillStep = RefillStep.PACKING,
    val machines: List<RefillMachine> = emptyList(),
    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouseId: String? = null,
    val warehouseStock: Map<String, Int> = emptyMap(),
    val pickOrder: Map<String, Int> = emptyMap(),
    val packedItems: Map<String, Set<String>> = emptyMap(),
    val customQuantities: Map<String, Map<String, Int>> = emptyMap(),
    val activeChip: String? = null,          // null = "Alle", sonst machineId
    val packingList: List<CombinedPackingItem> = emptyList(),
    val currentMachineId: String? = null,
    val tourId: String = "",
    val tourLog: List<TourLogEntry> = emptyList(),
    val hasSavedTour: Boolean = false,
    val error: String? = null,
)
```
- `packingList` wird bei jeder Zustandsänderung aus `RefillTourLogic.buildCombinedPackingList` neu abgeleitet und im State gehalten (nicht als `val`-Getter im UiState berechnet — die Pack-UI liest sie pro Frame, und die Sortierung ist nicht billig).

Regeln:
- `loadData()`: Maschinen+Trays, Lager, dann Bestand und Pickreihenfolge des **ersten** Lagers (iOS wählt ebenso das erste automatisch). Pickreihenfolgen-Fehler → leere Map, kein `error`.
- `selectWarehouse(id)`: lädt Bestand und Pickreihenfolge neu. **Eine langsamere Antwort für ein vorher gewähltes Lager darf eine neuere nicht überschreiben** — der Aufruf prüft nach dem `await`, ob `selectedWarehouseId` noch derselbe ist, und verwirft sonst das Ergebnis. (Dieses Muster fehlt an derselben Stelle im Lager-ViewModel aus Phase 4 und ist dort als plattformübergreifender Folgepunkt notiert — hier wird es von Anfang an richtig gemacht, ohne den Lager-Code anzufassen.)
- `clearError()` für die Snackbar-Anzeige (gleiches Idiom wie `WarehouseViewModel`/`LoginScreen`).
- Einstiegs-Gate: `loadData` läuft **einmal** pro ViewModel-Leben (`didRunInitialLoad`), nicht bei jedem Tab-Wechsel — der Tab-Root feuert seinen Lade-Effekt bei jeder Neuauswahl. Auf iOS hat genau das die Refill-Ansicht mitten in einer Tour zurückgesetzt.
- Beim Start prüft das ViewModel `TourStore.hasSavedTour` und setzt `hasSavedTour` — die Resume-Abfrage selbst kommt in Task 9.

- [ ] **Step 1:** iOS `loadData` (Z. 1213-1380) und `loadWarehouseStock` (Z. 1381-1441) lesen.
- [ ] **Step 2:** ViewModel neu schreiben (nur Laden/Auswahl/State), alte Aktionen vorläufig entfernen. Die UI kompiliert dadurch nicht mehr — das ist erwartet und wird in **diesem** Task mit erledigt: `RefillWizardScreen.kt` und die drei Step-Composables auf die neuen Namen anpassen, ohne ihre Optik zu verändern (Platzhalter-Callbacks, die noch nichts tun, sind hier zulässig, aber jede muss in Task 7-11 verdrahtet werden — Liste in den Report).
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `refactor(android): rewrite RefillViewModel loading and warehouse selection`.

---

### Task 7: ViewModel Teil 2 — Pack-Zustand

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillViewModel.kt`

**Interfaces:**
- Produces: `fun togglePackedForMachine(machineId: String, productId: String)`, `fun togglePackedAll(productId: String)`, `fun packEverything()`, `fun packAllForMachine(machineId: String)`, `fun setPackingQuantity(machineId: String, productId: String, quantity: Int)`, `fun selectChip(machineId: String?)`, plus die ableitenden Helfer, die die UI braucht: `fun displayQuantity(machineId: String, productId: String): Int`, `fun maxPackingQuantity(...)`, `fun isPacked(machineId: String, productId: String): Boolean`, `fun isOutOfStockForMachine(...)`, `fun visiblePackingList(): List<CombinedPackingItem>`, `fun chipItemCount(machineId: String?): Int`, `fun chipIsFullyPacked(machineId: String?): Boolean`.

Regeln (Referenz iOS Z. 613-945):
- Jede dieser Funktionen ist **dünn**: sie delegiert an `RefillTourLogic` und legt das Ergebnis in den State. Keine zweite Kopie der Mathematik im ViewModel.
- **`pinPackingQuantity`**: im Moment des Packens wird die aktuelle Menge als expliziter `customQuantities`-Eintrag festgeschrieben. Ohne das driftet die angezeigte gepackte Menge unter Realtime-Aktualisierungen (ein Verkauf vergrößert das Defizit) und der Fahrer nimmt weniger mit, als die UI behauptet hat. Gilt für alle drei Pack-Wege (einzeln, alle Maschinen eines Produkts, "alles packen").
- Abwählen löscht den `customQuantities`-Eintrag, damit er beim erneuten Packen frisch berechnet wird.
- Packen wird **übersprungen** (nicht gesperrt-mit-Fehler), wenn `isOutOfStockForMachine` gilt — bei "alles packen" heißt das: der Rest wird trotzdem gepackt.
- `machines[i].isPacked` ist abgeleitet: eine Maschine ist gepackt, sobald mindestens ein Produkt für sie angehakt ist. Nach jeder Pack-Änderung synchronisieren.
- `visiblePackingList`: Zeilen ohne Lagerbestand **und** ohne bereits gepackte Menge werden ausgeblendet, nicht ausgegraut. Teilweise gepackte bleiben sichtbar, damit man sie noch korrigieren kann.

- [ ] **Step 1:** iOS Z. 613-945 lesen.
- [ ] **Step 2:** Aktionen und Helfer implementieren.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): stock-aware packing state in RefillViewModel`.

---

### Task 8: ViewModel Teil 3 — Tour starten (Abbuchung + `tour_started`)

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillViewModel.kt`

**Interfaces:**
- Produces: `fun startTour()`.

Reihenfolge — **exakt diese**, sie ist auf iOS bewusst so und in einem Memory festgehalten (`tour_started` wird *nach* den Abbuchungen geschrieben, damit ein abgebrochener Start keinen verwaisten Feed-Eintrag hinterlässt):

1. Abbruch, wenn keine Maschine gepackt ist.
2. `tourId = UUID.randomUUID().toString()`, `tourLog` leeren.
3. `RefillTourLogic.applyTourInclusion(...)` → neue `machines`-Liste mit `isInTour`/`fillAmount`.
4. Falls ein Lager gewählt ist: `RefillTourLogic.buildDeductions(...)` → `RefillRepository.deductForTour(...)`. Fehler blockieren nicht.
5. `writeTourActivity("tour_started", machineId = null, …)` mit `machine_count` (Int), `machine_ids` (Array), `machine_names` (Array) und — falls vorhanden — `warehouse_name`. Feldnamen gegen `models/ActivityFeed.kt:37-42` prüfen: der **Android-Dashboard-Feed liest diese Zeile selbst**, ein Tippfehler zeigt sich sofort als leere Tour-Karte.
6. **Besuchsreihenfolge festlegen** (Nachtrag aus dem Task-3-Review): `fetchRefillMachines` liefert die Maschinen in bedeutungsloser Reihenfolge — iOS sortiert nach Dringlichkeit in dem `buildRefillMachines`-Schritt, den dieser Plan absichtlich umgeht. Die Tour sortiert ihre gepackten Maschinen deshalb hier selbst, wie iOS (`RefillWizardViewModel.swift:1017-1022`): Maschinen mit leeren Trays zuerst, dann nach `totalDeficit` absteigend, mit einem stabilen letzten Tiebreaker (Maschinen-ID), damit die Reihenfolge zwischen zwei Aufrufen nicht springt.
7. `step = REFILL`, `currentMachineId` = erste gepackte, nicht erledigte Maschine dieser Sortierung, `TourStore.save(...)`.

- [ ] **Step 1:** iOS `startTour` (Z. 1652-1740) und `deductWarehouseStock` (Z. 1741-1800) lesen — inklusive des Kommentars, der den Abbuchungs-Bug beschreibt.
- [ ] **Step 2:** `startTour()` implementieren.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): start refill tour with FIFO deduction for packed goods only`.

---

### Task 9: ViewModel Teil 4 — Befüllen, Skip, Zusammenfassung, Fortsetzen

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillViewModel.kt`

**Interfaces:**
- Produces: `fun adjustFillAmount(machineId: String, trayId: String, amount: Int)`, `fun fillTrayToCapacity(machineId: String, trayId: String)`, `fun fillAllTrays(machineId: String)`, `fun confirmRefill(machineId: String)`, `fun skipMachine(machineId: String)`, `fun selectMachine(machineId: String)`, `fun resumeTour()`, `fun discardSavedTour()`, `fun reset()`, sowie die Zusammenfassungs-Werte `machinesVisited`, `traysRefilled`, `totalItemsAdded`, `machinesSkipped` (aus `tourLog` berechnet, nicht separat gezählt).

Regeln (Referenz iOS Z. 1801-2024, 369-437):
- `confirmRefill`: Trays mit `fillAmount > 0` sammeln. **Leere Auswahl** → trotzdem Besuch protokollieren (Tour-Log-Eintrag mit 0/0 und `activity_log`), damit der Verlauf zeigt, dass die Maschine geöffnet wurde.
- Sonst `RefillRepository.refillMachineTrays(...)` mit **3 Versuchen** und Backoff 1 s / 3 s zwischen Versuch 1→2 und 2→3. Der RPC ist über `(tour_id, tray_id)` idempotent — ein blinder Retry kann nicht doppelt buchen (siehe Migrations-Kommentar).
- Nach Erfolg: die **Server-Werte** (`new_stock`) in den lokalen Zustand spiegeln, nicht die lokal erwarteten. `itemsAdded = Summe max(0, new_stock - old_stock)`.
- Nach drei Fehlversuchen: `error` setzen, Maschine **nicht** als erledigt markieren, Schritt nicht wechseln — der Fahrer kann dieselbe Maschine erneut bestätigen. (Der heutige Code verwirft den Fehler stillschweigend und rückt weiter; das ist der Befund 1 aus der Ausgangslage.)
- Erfolgs-Nachbereitung: `isRefilled = true`, `TourLogEntry`, `writeTourActivity("stock_refill_tour", …)` mit `trays_refilled`, `total_added` und `products` (Array aus `{product_id, product_name, quantity}` — Snapshot der gebuchten Trays), dann nächste Maschine, dann `TourStore.save`.
- `skipMachine`: `isSkipped = true`, Log-Eintrag mit `skipped = true`, `writeTourActivity("stock_refill_tour_skip", …)` ohne Zusatzfelder, weiter.
- Weiterrücken: Wenn keine gepackte, nicht erledigte, nicht übersprungene Maschine bleibt → `step = SUMMARY`.
- **`company_id` einmal je Tour auflösen** (Nachtrag aus dem Task-4-Review): `RefillRepository.writeTourActivity` löst die `company_id` heute pro Zeile über die Edge-Function `get-my-organization` auf (so wollte es der Plan, es ist das Muster von `MachineAnalysisRepository`). Bei einer Tour über N Maschinen sind das N Edge-Function-Aufrufe, jeder *nach* der schon committeten Tray-Buchung — fällt die Edge-Runtime aus, während PostgREST läuft, verschwindet die Audit-Zeile stillschweigend. iOS liest stattdessen direkt aus `organization_members`. Das ViewModel löst die `company_id` deshalb **einmal** beim Tourstart auf und gibt sie an jeden Log-Aufruf weiter; `writeTourActivity` bekommt dafür einen optionalen `companyId`-Parameter (fällt ohne ihn auf das heutige Verhalten zurück, damit kein bestehender Aufrufer bricht).
- `resumeTour()`: Zustand aus `TourStore` in den State übernehmen (inkl. `tourId` — sonst verliert der Retry seine Idempotenz-Klammer). **Achtung (Nachtrag aus dem Task-6-Review):** der persistierte Zustand trägt einen `currentMachineIndex`, das laufende UiState aber eine `currentMachineId`. Der Index bezieht sich auf die **nach Dringlichkeit sortierte** Tour-Liste aus Task 8, nicht auf die Abrufreihenfolge des Repositories — beim Fortsetzen also erst sortieren, dann indizieren, sonst landet der Fahrer an der falschen Maschine. `discardSavedTour()` räumt auf. `reset()` leert alles, löscht den gespeicherten Zustand und entsperrt das Einstiegs-Gate, damit der nächste Tab-Besuch frisch lädt.

- [ ] **Step 1:** iOS Z. 1801-2024 und die Persistenz Z. 305-437 lesen.
- [ ] **Step 2:** Implementieren.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): atomic refill with retry, skip, tour log and resume`.

---

### Task 10: UI — Pack-Schritt

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/PackingStep.kt` (Vollersatz des Inhalts)

Aufbau von oben nach unten (Referenz `PackingStepView.swift`):

1. **Lager-Picker** — nur wenn mehr als ein Lager existiert (gleiches Gating wie `WarehouseScreen.kt` aus Phase 4). **Nachtrag aus dem Task-6-Review:** schlägt der Bestandsabruf fehl, bleibt `warehouseStock` leer, und leer heißt für die Deckelungslogik „kein Bestand geladen" — die Packmengen werden dann nur noch von der Tray-Kapazität begrenzt, der Fahrer bekommt also mehr zum Packen angezeigt, als das Lager hergibt, und der Fehler ist nach dem Snackbar weg. `selectWarehouse` mit derselben ID kehrt früh zurück, der Picker kann es also nicht erneut versuchen. Dieser Task braucht deshalb einen sichtbaren Wiederholen-Weg für einen fehlgeschlagenen Bestandsabruf (z. B. eine Hinweiszeile mit „Erneut laden" über der Liste, die das ViewModel zum Neuladen des aktuellen Lagers zwingt).
2. **Chip-Leiste** — "Alle" plus ein Chip je Maschine mit Bedarf; je Chip die offene Stückzahl und ein Häkchen, wenn vollständig gepackt. Bei mehr als drei Chips **umbrechen** (`FlowRow`) — die 4-Chip-Zeile im Lager-Modul lief auf ~360 dp und in Deutsch aus dem Bild, das war ein Review-Befund in Phase 4 und muss hier nicht wiederholt werden.
3. **Produktkarte** je Zeile der sichtbaren Packliste: Bild (Platzhalter bei fehlendem Pfad), Name — `CombinedPackingItem.productName` ist **nullable**, ein fehlender Name wird hier über `R.string.machine_card_unassigned_slot` mit der Slot-Nummer aufgelöst (die reine Logik synthetisiert bewusst keinen Text, gleiche Konvention wie `IntakeEntry.productName`) —, Gesamtmenge, Lagerbestands-Badge (verbleibender Bestand; rot bei 0, orange bei "weniger als gefordert"), und je Maschine eine **Bedarfszeile** mit Häkchen, Maschinenname, Mengen-Stepper (−/Wert/+) und der Kapazität als Kontext. Steppergrenzen kommen aus `maxPackingQuantity`, der Anzeigewert aus `displayQuantity`.
4. **Untere Aktionsleiste** — "Alles packen" bzw. bei aktivem Maschinen-Chip "Alles für <Maschine> packen", und "Tour starten" (deaktiviert, solange keine Maschine gepackt ist), mit der Anzahl gepackter Maschinen.

Fehler erscheinen als Snackbar über `uiState.error` + `clearError()` (Muster aus `LoginScreen.kt`/`WarehouseScreen.kt`), nicht als stiller Zustand.

- [ ] **Step 1:** `PackingStepView.swift` vollständig lesen (36 KB — der Umsetzer bekommt in diesem Task **nur** diese Aufgabe, gerade weil die Datei groß ist).
- [ ] **Step 2:** Composable(s) bauen, alle Texte als String-Ressourcen in **beiden** Locale-Dateien anlegen.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): product-centric packing step with warehouse stock caps`.

---

### Task 11: UI — Befüllen-Schritt und Wizard-Hülle

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillStep.kt` (Vollersatz des Inhalts)
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt`

Befüllen-Schritt (Referenz `RefillStepView.swift`):
- **Maschinen-Kopf** mit Name, Fortschritt (erledigte/gesamte gepackte Maschinen) und Umschalter auf ein **Maschinen-Auswahl-Sheet** (der Fahrer fährt nicht zwingend in der vorgegebenen Reihenfolge).
- **Tray-Karten** für Trays mit `isInTour`: Slot-Nummer, Produktbild/-name, aktueller Bestand → Ziel, Stepper und "voll"-Knopf. Deutlich größere Touch-Ziele als im Rest der App (Spec: *"Trayzeilen im Refill deutlich größer"*).
- Trays ohne Bedarf in einer eingeklappten **"volle Trays"-Zeile**, nicht als Karten.
- **Untere Leiste**: "Alle füllen", "Überspringen", "Befüllung bestätigen" (mit Spinner während `isSaving`, gesperrte Eingaben währenddessen — dasselbe Doppel-Absenden-Problem wie im `BatchAdjustSheet` in Phase 4).

Wizard-Hülle:
- **Schritt-Indikator** (Referenz `RefillWizardView.swift:112`). **Umgesetzt als reine Fortschrittsanzeige, absichtlich NICHT antippbar — Abweichung von dieser Planvorgabe, dem Nutzer vorzulegen:** iOS' `navigateToStep` setzt nur `currentStep`, und iOS' `startTour` prüft ausschließlich, ob irgendetwas gepackt ist. Wer dort vom Befüllen zurück auf Packen springt und erneut startet, erzeugt eine **neue** `tourId`, führt die FIFO-Abbuchung für dieselbe Ware **ein zweites Mal** aus und wirft die `tourId` weg, über die `refill_machine_trays` dedupliziert — also genau die Fehlerklasse, für die diese Phase existiert. Ein sicherer Rücksprung braucht eine Tour-Zurücksetzung **mit Rückbuchung** der Abbuchungen (`deduct_warehouse_stock_fifo` hat keinen Idempotenz-Schlüssel, eine Wiederverwendung der `tourId` genügt nicht) — eigener Task, nicht hier.
- **Resume-Abfrage** beim Betreten, wenn `hasSavedTour`: „Tour fortsetzen?" mit Fortsetzen/Verwerfen. **Harte Anforderung aus dem Task-9-Review, nicht optional:** `loadData` veröffentlicht `hasSavedTour` *bevor* der Abruf fertig ist und schreibt `machines` danach bedingungslos — eine Abfrage, die sofort beim Umschlagen von `hasSavedTour` erscheint, kann also ein `resumeTour()` mitten in einen laufenden Ladevorgang legen, der anschließend `isRefilled`/`isSkipped`/`fillAmount` überschreibt. Die Abfrage darf deshalb erst *nach* Abschluss des Ladens erscheinen (oder das Laden braucht ein Generationen-Token). Ohne das verliert eine fortgesetzte Tour genau die Fortschritte, um die es beim Fortsetzen geht.
- **`isSaving` bleibt über den Audit-Schreibvorgang hinweg gesetzt** (wie auf iOS): ein Tipp auf „Bestätigen" der nächsten Maschine wird in diesem Fenster vom Re-Entrancy-Guard verworfen. Das darf nicht rückmeldungslos passieren — Knopf während `isSaving` sichtbar deaktiviert mit Spinner, damit ein verworfener Tipp erklärt ist statt zu wirken wie eine kaputte App.
- **Bildschirm bleibt an, solange `step == REFILL`** (Spec Z. 161): `KeepScreenOn` über `LocalView.current.keepScreenOn` in einem `DisposableEffect`, das die Flagge beim Verlassen zuverlässig zurücknimmt — nicht dauerhaft für den ganzen Wizard.
- Die drei hart codierten englischen Titel verschwinden hier (Ausgangslage Befund 2).

- [ ] **Step 1:** `RefillStepView.swift` und `RefillWizardView.swift` lesen. **Das Layout-Grid ist nicht Teil dieses Tasks** (Plan 5b) — wenn die iOS-Datei es einbindet, wird die Stelle im Report als offener Haken vermerkt, nicht nachgebaut.
- [ ] **Step 2:** Bauen, alle Texte in beide Locale-Dateien.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün.
- [ ] **Step 4: Commit** — `feat(android): rebuild refill step and wizard shell with resume prompt`.

---

### Task 12: UI — Zusammenfassung, Lokalisierungs-Sweep, Abschluss

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillSummaryStep.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-de/strings.xml`

- Zusammenfassung aus dem `tourLog`: Kennzahlen (besuchte Maschinen, befüllte Trays, eingefüllte Stück, übersprungene Maschinen) plus eine Zeile je Maschine mit ihrem Beitrag und einer Kennzeichnung für Übersprungene. "Fertig" ruft `reset()` und verlässt den Wizard.
- **Lokalisierungs-Sweep über das gesamte Modul**: `grep -rn 'Text("' ui/refill/` und `grep -rn 'contentDescription = "' ui/refill/` müssen leer sein. **Zusätzlich** (aus dem Task-9-Review): Task 9 hat bewusst einen nicht lokalisierten Fehlertext im ViewModel hinterlassen — die Meldung nach drei fehlgeschlagenen Buchungsversuchen. Sie gehört ebenfalls in beide `strings.xml`; das ViewModel darf dafür keinen Text mehr selbst formulieren (Muster: das ViewModel setzt einen Fehlercode/Schlüssel oder die UI übersetzt beim Anzeigen).
- **`reset()` hinterlässt `isLoading = true`** (der `RefillUiState()`-Default) bei zurückgesetztem Einstiegs-Gate. Das ist richtig, wenn der Screen verlassen und neu betreten wird — genau so muss „Fertig" also verdrahtet werden (erst `reset()`, dann raus). Bleibt die Zusammenfassung stattdessen stehen, zeigt sie einen dauerhaften Spinner. Mengen über `<plurals>`, Währung über `NumberFormat.getCurrencyInstance`, Datum locale-abhängig.
- **Schlüssel-Paritätsprüfung** beider Locale-Dateien: Differenz darf nur aus den bekannten nicht-übersetzbaren technischen Schlüsseln bestehen (`app_name`, `supabase_anon_key`, `supabase_url`).

- [ ] **Step 1:** `RefillSummaryView.swift` lesen, Zusammenfassung bauen.
- [ ] **Step 2:** Sweep-Greps ausführen, Treffer beheben.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` — grün, Testzahl im Report nennen.
- [ ] **Step 4: Commit** — `feat(android): refill summary from tour log + localization sweep`.

---

## Abnahme der Phase

Alles hiervon macht der **Orchestrator** selbst, nicht ein Umsetzer:

1. `./gradlew assembleDebug assembleRelease testDebugUnitTest` grün (Release wegen R8 — in Phase 1 hat nur der Release-Build eine Regel gefunden).
2. Auf dem S10, eine echte Tour von Anfang bis Ende: Lager wählen → zwei Produkte für zwei Maschinen packen, davon eines mit **reduzierter** Menge → Tour starten → erste Maschine befüllen → zweite Maschine **überspringen** → Zusammenfassung.
3. **Die zentrale Prüfung dieser Phase:** Der Lagerbestand darf nach dieser Tour **nur um die gepackten Mengen** gesunken sein — nicht um die Tray-Defizite, und nicht für das Produkt der übersprungenen Maschine, sofern es nie gepackt wurde. Ablesbar im Lager-Tab (Phase 4) vor/nach der Tour, plus Chargen-Drilldown für die FIFO-Reihenfolge (älteste Charge zuerst geleert). Damit ist gleichzeitig der in Phase 4 formal offene manuelle `deduct_warehouse_stock_fifo`-Test erledigt — er läuft hier zum ersten Mal über echte UI.
4. Idempotenz: während einer Bestätigung Flugmodus einschalten, den Fehler nach drei Versuchen sehen, Flugmodus aus, **dieselbe** Maschine erneut bestätigen → der Bestand steigt **einmal**, nicht zweimal (`refill_tour_tray_applications` dedupliziert über `(tour_id, tray_id)`).
5. Fortsetzen: mitten in der Tour `adb shell am force-stop xyz.vmflow.app`, neu starten → Resume-Abfrage erscheint, Fortsetzen führt in denselben Zustand mit derselben `tourId`.
6. Verlauf: Nach der Tour zeigt der **Android-Dashboard-Feed** selbst die Tour-Start-Karte (mit Maschinenanzahl und Lagername) und die Befüllungs-Karte (mit Produktzeilen) — `ActivityFeedBuilder` liest exakt die Schlüssel, die Task 4/8/9 schreiben, also ist das die schärfste Gegenprobe auf den Metadaten-Vertrag. Zusätzlich in der PWA `/history` und im iOS-Verlauf gegenprüfen, dass die Zeilen dort ebenso korrekt gerendert werden — der Vertrag gilt über drei Clients.
7. Bildschirm bleibt während des Befüllen-Schritts an, geht danach wieder in den normalen Timeout.
8. Kein hart codierter Text mehr in `ui/refill/`, Schlüssel-Parität beider Locale-Dateien geprüft.

**Bekannte Abweichung von iOS, aufgedeckt im Task-10-Review** (dem Nutzer vorlegen, nicht stillschweigend abhaken): `RefillRepository.fetchRefillMachines` übernimmt **keine** von iOS' Vorfilterung (`RefillWizardViewModel.swift:998-1012`: eine Maschine kommt nur in die Liste, wenn sie ein leeres oder unter dem Mindestbestand liegendes Tray hat; Trays müssen kritisch/niedrig/unter der Nachfüllschwelle sein). Android zeigt damit jede Maschine, die überhaupt Trays hat — auch eine, die eine einzige Einheit unter Kapazität liegt. Das war eine bewusste Planentscheidung (der Befüllen-Schritt zeigt volle Trays eingeklappt mit), hat aber zwei Folgen, die der Plan nicht vorhergesehen hat: die „Alle"-Chip-Logik musste in der UI über die Packlisten-Maschinen statt über `uiState.machines` gebildet werden, und die Kosten pro Recomposition wachsen mit der Flottengröße statt mit dem Nachfüllbedarf. Kandidat für einen eigenen Folge-Task, nicht für einen Schnellschuss innerhalb dieser Phase.

## Bekannte Restrisiken nach 5a (aus dem Abschluss-Review, bewusst nicht gefixt)

Die Lager-Invariante dieser Phase ist **einseitig**: sie garantiert, dass nichts abgebucht wird, was der Fahrer nicht gepackt hat. Sie garantiert nicht die Rückrichtung. Das ist eine Prozessentscheidung des Nutzers (Rückbuchung bei reduzierten oder übersprungenen Füllungen bleibt manuell), aber es gehört aufgeschrieben, damit niemand die Invariante für geschlossen hält:

1. **Nach Tourstart kann der Fahrer jedes Tray in der Tour bis zur Kapazität hochdrehen** (`adjustFillAmount`/`fillTrayToCapacity` deckeln auf `maxFill`, nicht auf die gepackte Menge) — dann wird Maschinenbestand gebucht, für den das Lager nie belastet wurde. Umgekehrt bleibt bei Herunterdrehen oder Überspringen das Lager belastet, obwohl die Ware im Auto geblieben ist. Es gibt auf keinem der drei Clients einen Rückbuchungspfad.
2. **Prozesstod zwischen Abbuchung und erstem `TourStore.save()`** (innerhalb von `startTour`) verliert die Tour, während das Lager schon belastet ist — der Fahrer packt neu und wird ein zweites Mal belastet.
3. **`deductForTour` hat keine `(tour_id, product_id)`-Deduplizierung** der Art, die die Tray-Buchung sicher macht.
4. **Doppelte Audit-Zeile bei Prozesstod** zwischen dem Zustandsschreiben von `recordRefillSuccess` und dem `TourStore.save()` in `advanceToNextMachine`: die fortgesetzte Tour bestätigt dieselbe Maschine erneut, der RPC liefert die alten Werte zurück (DB-Bestand bleibt korrekt), aber eine zweite `stock_refill_tour`-Zeile mit demselben `total_added` entsteht. Vom legitimen Flugmodus-Retry nicht unterscheidbar, deshalb absichtlich unverändert.
5. **Nicht zugewiesene Fächer** (`product_id IS NULL`) starten mit `fillAmount = deficit` und bleiben in der Tour — ein einfaches Bestätigen bucht sie auf Kapazität, ohne Produkt und ohne Lagerbelastung. iOS-Parität, in der Praxis überraschend; am Gerät ansehen.
6. **`fetchRefillMachines` paginiert nicht.** Ein Abruf aller `machine_trays` einer Firma; die CLI-Dev-Konfiguration setzt `max_rows = 1000` (`Docker/supabase/config.toml`), die Docker-Prod-Konfiguration derzeit keine Grenze. Über 1000 Tray-Zeilen würde **still** abgeschnitten: Maschinen verlieren Fächer, halbleere Automaten sehen voll aus. Dieses Repo hatte die Klasse schon einmal (der Nayax-Import paginiert genau deswegen in 1000er-Blöcken). Vor dem Merge die Tray-Zeilenzahl der Zielfirma zählen; nahe an der Grenze → hier paginieren, nicht später.
7. **Kosten pro Zustandsänderung skalieren mit der Flottengröße**, nicht mit dem Nachfüllbedarf: jedes `withPackingList()` gruppiert und sortiert die ganze Flotte neu, und der Namensvergleicher baut pro Aufruf einen frischen ICU-`Collator`. Am Gerät mit echten Daten fühlbar prüfen (Stepper halten und schnell tippen).
8. **`flattenPickOrder` verliert Gruppen in einem Eltern-Zyklus** (eine zyklisch verkettete Gruppe ist von keiner Wurzel erreichbar, ihre Produkte fallen aus der Pickreihenfolge). Kein Absturz, kein Datenverlust außer der Sortierung; datengetrieben und unwahrscheinlich.
9. **Der Rundungsfehler bei reduzierten Packmengen existiert weiterhin auf iOS und in der PWA** (`RefillWizardViewModel.swift:1705-1709` und das PWA-Äquivalent): dort wird jede Tray-Quote einzeln gerundet, sodass die Maschine mehr Einheiten gutgeschrieben bekommen kann als das Lager belastet wird. Auf Android in dieser Phase behoben (Largest-Remainder-Verteilung + Eigenschaftstest); für die anderen zwei Clients offen.

**Was nach 5a bewusst offen bleibt** (Plan 5b, dem Nutzer vor dem Merge vorlegen, nicht stillschweigend abhaken): Review-Schritt mit Ersatzvorschlägen (4 Gründe: `discontinued`, `expired`, `noStock`, `unassigned`), Ersatzprodukt-Picker mit Kategorie-Gruppierung, Maschinen-Layout-Grid im Befüllen-Schritt, sowie die Barkassen-Auflösung am Tour-Ende (letztere braucht erst ein Kassenbuch-Modul auf Android).
