# Android Phase 5b: Review-Schritt, Ersatzprodukt-Picker, Layout-Grid — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den letzten fehlenden Teil der Refill-Parität liefern: den **Review-Schritt**, der vor dem Packen die Fächer auflistet, deren Produkt so nicht weiterlaufen sollte (abgekündigt, abgelaufen, kein Lagerbestand, gar nicht zugewiesen), den **Ersatzprodukt-Picker** dafür, und das **Maschinen-Layout-Grid** im Befüllen-Schritt, damit der Fahrer das Fach am Automaten wiederfindet.

**Architecture:** Wie 5a. Erkennungslogik (welches Fach braucht Ersatz, und warum) in reine, getestete Funktionen; Netzwerk in `RefillRepository`; Review-Zustand im bestehenden `RefillViewModel`; Oberfläche in Compose, Schritt-Composables ohne ViewModel-Zugriff. Zwei Dinge werden **verallgemeinert statt neu gebaut**: das Layout-Grid der Analyse-Ansicht (Phase 3) und der Ersatzprodukt-Picker der Analyse-Ansicht — beide existieren bereits in `ui/machines/`.

**Tech Stack:** Kotlin 2.4.10 · AGP 9.3.1 · Gradle 9.5 · Compose BOM 2026.06.01 · compileSdk 36 · JUnit 4

## Ausgangslage

Phase 5a hat die Tour geliefert (Packen → Befüllen → Zusammenfassung), 300 Tests, am S10 verifiziert und in `main`. Was iOS zusätzlich hat:

| iOS-Datei | Größe | Android heute |
|---|---:|---|
| `Views/Refill/ReviewStepView.swift` | 19 KB | **fehlt** — der Wizard startet direkt im Pack-Schritt |
| `Views/Refill/ReplacementProductPicker.swift` | 25 KB | **fehlt** für den Review; ein *anderer*, einfacherer Picker existiert in der Analyse-Ansicht |
| `Views/Refill/MachineLayoutGrid.swift` | 16 KB | **fehlt** im Refill; ein Äquivalent existiert in der Analyse-Ansicht |
| `RefillWizardViewModel` Review-Teil (Z. 1213-1351 Erkennung, 1547-1613 Aktionen) | — | **fehlt** |

**Was Android schon hat und wiederverwendet werden muss — nicht neu bauen:**
- `data/MachineAnalysis.kt`: `slotRowCol`, `computeSlotWidths`, `buildGridSlots` (die Grid-Geometrie, getestet in `MachineAnalysisTest.kt`) und `buildSuggestionPool` (Bestseller + Newcomer als Ersatzkandidaten).
- `ui/machines/MachineAnalysisView.kt`: `AnalysisLayoutGrid`, `AnalysisGridCell`, `buildGridEntries` (das gerenderte Grid), `ReplaceProductSheet`, `SuggestionThumbnail`, `SwapConfirmDialog`.
- `ui/machines/MachineAnalysisViewModel.kt::applySwap` + `data/MachineAnalysisRepository.kt::logProductSwap` — der bestehende Schreibpfad „Fach bekommt ein neues Produkt, Bestand auf 0, `activity_log`-Zeile".
- Aus 5a: `RefillStep` (in `models/`), `RefillUiState`, `TourStore`, `RefillRepository`, `RefillTourLogic`.

**Was Android nicht hat:** Produktkategorien. `grep -rn "ProductCategory\|product_category" android/app/src/main/java` ist leer. iOS' Picker gruppiert nach Kategorie, also braucht dieser Plan Modell + Lesepfad dafür.

## Referenz-Implementierung

| iOS-Datei | Was daraus portiert wird |
|---|---|
| [`ViewModels/RefillWizardViewModel.swift`](../../../ios/VMflow/ViewModels/RefillWizardViewModel.swift) | Ersatz-Erkennung in `loadData` (Z. 1258-1351), Review-Aktionen (Z. 1547-1613), `currentCategoryId` (Z. 473-479) |
| [`Views/Refill/ReviewStepView.swift`](../../../ios/VMflow/Views/Refill/ReviewStepView.swift) | Karten je Vorschlag, Grund-Badge (Z. 262-290), Ziel-Fach (Z. 291), „vorhandene Fächer" (Z. 306), untere Leiste (Z. 395) |
| [`Views/Refill/ReplacementProductPicker.swift`](../../../ios/VMflow/Views/Refill/ReplacementProductPicker.swift) | Bestands-Buckets (Z. 101-170), Kategorie-Gruppen (Z. 78), Sortierschlüssel (Z. 170), Slot-Badge-Label (Z. 14) |
| [`Views/Refill/MachineLayoutGrid.swift`](../../../ios/VMflow/Views/Refill/MachineLayoutGrid.swift) | Zelle, Lücke, Grid — **nur als Vergleich**: Android hat die Geometrie schon, siehe Task 7 |

## Global Constraints

- **Keine Backend-Änderungen.** Alle Tabellen existieren (`products`, `product_category`, `machine_trays`, `warehouse_stock_batches`). Keine Migration, keine Edge-Function.
- **`RefillStep` bekommt `REVIEW` als ersten Wert.** Das Enum ist `@Serializable` und Teil von `PersistedTourState`, wird aber **nur** mit `REFILL`/`SUMMARY` persistiert (`TourStore`s Guard ist ein erschöpfendes `when` — der neue Zweig muss dort `false` liefern, und der Compiler erzwingt die Entscheidung). Ein alter gespeicherter Zustand enthält niemals `REVIEW`, ist also weiter lesbar.
- **Der Review-Schritt wird übersprungen, wenn es keine Vorschläge gibt** — wie iOS (`RefillWizardViewModel.swift:1346-1351`). Ein Fahrer ohne Ersatzbedarf darf keinen leeren Zwischenschritt sehen.
- **`reviewCompleted` ist ein Gate:** nach dem Review lädt `loadData` die Vorschläge nicht erneut, sonst erscheint der gerade abgearbeitete Schritt wieder. iOS hält dafür ein eigenes Flag (Z. 1258).
- **Ersatz setzen heißt: `product_id` neu, `current_stock` auf 0.** Der Altbestand des vorherigen Produkts ist für das neue bedeutungslos, und ohne die Null zeigt das Fach im Pack-Schritt kein Defizit (iOS kommentiert genau das, Z. 1578-1581).
- **`activity_log`: hier weicht dieser Plan bewusst von iOS ab.** iOS schreibt für eine Review-Ersetzung **keine** Verlaufszeile. Android hat für exakt dieselbe Operation schon einen Schreibpfad (`MachineAnalysisRepository.logProductSwap`, Action `analysis_swap`), und ein Produktwechsel ohne Audit-Zeile ist in einem Mehrbenutzer-Betrieb eine Lücke. **Entscheidung: wir schreiben eine Zeile** — mit einer eigenen Action (`refill_review_swap`), nicht mit `analysis_swap`, damit die Herkunft unterscheidbar bleibt. Der Metadaten-Vertrag gilt wie immer: keine bestehende Schlüssel-Typänderung, und `ActivityFeedBuilder` ignoriert unbekannte Actions still (`mapNotNull`), also bricht nichts, solange die Zeile nur additiv ist. Rendering im Feed ist **nicht** Teil dieses Plans.
- Alle neuen Nutzertexte in `values/strings.xml` **und** `values-de/strings.xml`, Mengen über `<plurals>`, Deutsch an `Localizable.xcstrings` angelehnt, wo iOS denselben Begriff schon hat.
- Statusfarben **nie** aus `MaterialTheme.colorScheme` — feste Tokens aus `ui/theme/Color.kt`. Für die vier Grund-Badges braucht es vier klar trennbare Töne (iOS: rot/orange/violett/blau); vorhandene Tokens prüfen, fehlende dort ergänzen, mit eigenen Hell/Dunkel-Werten.
- `contentDescription` an jedem Icon-Button, Touch-Ziele ≥ 48 dp, keine festen Zeilenhöhen, Chip-/Flow-Reihen umbrechen (`FlowRow`).
- `SupabaseService.client` nur über den Getter. Nur JUnit 4, keine neuen Abhängigkeiten, kein androidx-Bump.
- **Der Tour-Pfad aus 5a wird nicht umgebaut.** `startTour`, `confirmRefill`, `skipMachine`, die Abbuchung und `TourStore` bleiben unangetastet; dieser Plan hängt einen Schritt **davor** und ein Grid **daneben**.
- Commits: `git add <pfade>`, dann `git commit` **ohne** Pathspec. Niemals `--amend`, `reset`, `rebase`.

## Testumgebung

Wie 5a: Samsung Galaxy S10, `adb -s <Seriennummer>`. **Das angemeldete Gerät zeigt auf `supabase-test.kerl-handel.de`**, nicht auf den lokalen Stack — `curl` gegen `127.0.0.1` beweist nichts, Persistenz über Kaltstart nachweisen. Paket: `xyz.vmflow.app`.

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Umsetzer installieren nicht selbst.** Gerätetest macht der Orchestrator.

**Datenlage, die 5a's Gerätetest festgestellt hat und die hier Folgen hat:** in den echten Daten liegt jedes Produkt in genau *einem* Fach pro Automat. Für den Review-Schritt heißt das, die „vorhandene Fächer"-Zeile (iOS zeigt, wo dasselbe Produkt sonst noch liegt) wird am Gerät meist leer sein — das ist korrekt, nicht kaputt.

---

### Task 1: Reine Logik — Ersatzvorschläge erkennen

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/RefillReviewLogic.kt`
- Test: `android/app/src/test/java/xyz/vmflow/data/RefillReviewLogicTest.kt`

**Interfaces:**
- Consumes: `models.Tray`, `models.Product`, `models.WarehouseStockBatch`, `models.VendingMachineWithEmbedded`.
- Produces:
  - `enum class ReplacementReason { DISCONTINUED, EXPIRED, NO_STOCK, UNASSIGNED }`
  - `data class ReplacementSuggestion(val trayId: String, val machineId: String, val machineName: String, val slotNumber: Int, val currentProductId: String?, val currentProductName: String?, val currentProductImage: String?, val currentStock: Int, val reason: ReplacementReason, val replacementProductId: String? = null, val isSkipped: Boolean = false)`
  - `fun expiredProductIds(batches: List<WarehouseStockBatch>, today: String): Set<String>`
  - `fun buildReplacementSuggestions(machines: List<VendingMachineWithEmbedded>, traysByMachine: Map<String, List<Tray>>, stockedProductIds: Set<String>, expiredProductIds: Set<String>): List<ReplacementSuggestion>`

Regeln, jede einzeln ein Testfall (Referenz `RefillWizardViewModel.swift:1258-1351`):

1. **Prioritätsreihenfolge je Fach, erste Regel gewinnt:** (a) Produkt abgekündigt **und** Fachbestand 0 → `DISCONTINUED`; (b) Produkt in `expiredProductIds` → `EXPIRED`, unabhängig vom Fachbestand; (c) Fachbestand 0 **und** Produkt hat keinen Lagerbestand → `NO_STOCK`; (d) Fach ohne Produkt → `UNASSIGNED`.
2. Ein abgekündigtes Produkt mit Restbestand im Fach erzeugt **keinen** Vorschlag (der Fahrer verkauft es aus).
3. **`expiredProductIds`:** ein Produkt gilt als abgelaufen, wenn **alle** seine Chargen mit `quantity > 0` ein Ablaufdatum tragen **und alle** davor liegen. Eine einzige Charge ohne Datum oder mit Datum in der Zukunft schließt das Produkt aus. Datumsvergleich als String (`YYYY-MM-DD`, lexikographisch — wie iOS), `today` kommt als Parameter herein, damit die Funktion rein bleibt.
4. `stockedProductIds` = Produkte mit Gesamtbestand > 0 im gewählten Lager (der Aufrufer bildet das aus dem bestehenden `fetchWarehouseStockTotals`).
5. Jedes Fach erzeugt **höchstens einen** Vorschlag (iOS führt dafür ein `seenTrayIds`-Set).
6. Reihenfolge des Ergebnisses ist **deterministisch**: nach Maschinenname, dann Fachnummer. (iOS lässt sie von der Fetch-Reihenfolge abhängen — bewusste Abweichung, weil eine springende Liste zwischen Recompositions unbrauchbar ist. Im Report begründen.)

- [ ] **Step 1:** iOS Z. 1258-1351 vollständig lesen.
- [ ] **Step 2:** Fehlschlagende Tests schreiben — je Regel oben mindestens einer, plus: abgelaufenes Produkt mit vollem Fach erzeugt trotzdem `EXPIRED`; Produkt mit einer datumslosen Charge gilt **nicht** als abgelaufen; ein Fach ohne Produkt und ohne Bestand ist `UNASSIGNED` (nicht `NO_STOCK`); ein Automat ohne Fächer erzeugt nichts; die Ergebnisreihenfolge ist bei umgedrehter Eingabe identisch.
- [ ] **Step 3:** `./gradlew testDebugUnitTest --tests '*RefillReviewLogicTest*'` — Fehlschlag bestätigen.
- [ ] **Step 4:** Implementieren, bis grün.
- [ ] **Step 5:** Volle Suite (300 Tests + neue).
- [ ] **Step 6: Commit** — `feat(android): add refill review suggestion logic`.

---

### Task 2: Kategorien — Modell und Lesepfad

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/models/Models.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt`

**Interfaces:**
- Produces: `data class ProductCategory(val id: String, val name: String? = null, val company: String? = null)` (`@Serializable`, Feldnamen gegen die Tabelle prüfen — **nicht raten**: die `product_category`-Definition in `Docker/supabase/migrations/` nachlesen und im Report nennen, gegen welche Datei), sowie `suspend fun fetchProductCategories(): Result<List<ProductCategory>>` und `suspend fun fetchActiveProducts(): Result<List<Product>>`.

Regeln:
- `fetchActiveProducts` liefert nur nicht abgekündigte Produkte, alphabetisch — iOS' Filter ist `or("discontinued.is.null,discontinued.eq.false")` (Z. 1264). **Prüfen, ob `TrayRepository.fetchProducts()` das schon genau so tut**; wenn ja, diese Funktion nutzen und `fetchActiveProducts` **nicht** anlegen (im Report festhalten, was du gefunden hast). Doppelte Lesepfade für dieselbe Frage sind in diesem Projekt schon mehrfach zum Problem geworden.
- `Product.category` existiert bereits im Modell? Nachsehen. Falls nicht, additiv ergänzen (nullable, mit Default) — es ist der Schlüssel, über den der Picker gruppiert.

- [ ] **Step 1:** Migration für `product_category` lesen, Spalten notieren.
- [ ] **Step 2:** `TrayRepository.fetchProducts()` prüfen und entscheiden.
- [ ] **Step 3:** Modelle + Lesepfad(e) implementieren.
- [ ] **Step 4:** `./gradlew assembleDebug testDebugUnitTest` grün.
- [ ] **Step 5: Commit** — `feat(android): add product categories for the replacement picker`.

---

### Task 3: Repository — Review-Lesepfade und der Ersetzungs-Schreibpfad

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt`

**Interfaces:**
- Produces:
  - `suspend fun fetchAllPositiveBatches(warehouseId: String): Result<List<WarehouseStockBatch>>` — für die Ablauf-Erkennung. **Erst prüfen**, ob `WarehouseRepository.fetchWarehouseStock(warehouseId)` (Phase 4) genau das schon liefert; wenn ja, diese Funktion nicht anlegen, sondern direkt nutzen.
  - `suspend fun applyReplacement(trayId: String, productId: String): Result<Unit>` — setzt `product_id` und `current_stock = 0` in einem Update.
  - `suspend fun logReviewSwap(machineId: String, machineName: String, trayId: String, slotNumber: Int, oldProductId: String?, oldProductName: String?, newProductId: String, newProductName: String, tourId: String?, companyId: String?): Result<Unit>`
- Consumes: der bestehende `activity_log`-Schreibweg. **`MachineAnalysisRepository.logProductSwap` zuerst lesen** und die Metadaten-Schlüssel übernehmen, die es dort schon gibt, statt neue Namen für dieselben Dinge zu erfinden. Action ist `refill_review_swap` (siehe Global Constraints). Fehler beim Log sind nicht blockierend; Auth **vor** dem ersten Schreibzugriff auflösen.

Kein neuer Unit-Test (Repository-Glue, Projektkonvention).

- [ ] **Step 1:** `MachineAnalysisRepository.logProductSwap` (Z. 190-230) und `WarehouseRepository.fetchWarehouseStock` lesen.
- [ ] **Step 2:** Implementieren, dabei jede vermeidbare neue Funktion vermeiden.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` grün.
- [ ] **Step 4: Commit** — `feat(android): add review read paths and the tray replacement write`.

---

### Task 4: ViewModel — Review-Zustand und -Aktionen

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillViewModel.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/models/Models.kt` (`RefillStep` bekommt `REVIEW`)
- Modify: `android/app/src/main/java/xyz/vmflow/data/TourStore.kt` (der erschöpfende `when` bekommt den neuen Zweig → `false`)

**Interfaces:**
- Produces: `RefillUiState` wächst um `replacements: List<ReplacementSuggestion>`, `availableProducts: List<Product>`, `productCategories: List<ProductCategory>`, `isApplyingReplacements: Boolean`; plus `fun setReplacement(trayId: String, productId: String)`, `fun skipReplacement(trayId: String)`, `fun applyReplacementsAndContinue()`, `fun skipReview()`, `val RefillUiState.allReplacementsHandled: Boolean`, `fun categoryIdOfCurrentProduct(trayId: String): String?`.

Regeln (Referenz iOS Z. 1547-1613):
- `loadData` ermittelt die Vorschläge **nur**, wenn der Review noch nicht abgearbeitet ist (`reviewCompleted`-Gate, nicht im UiState — es ist Ablaufzustand, kein Anzeigezustand). Keine Vorschläge → `step = PACKING`; Vorschläge → `step = REVIEW`.
- `setReplacement` setzt das Ersatzprodukt und hebt ein etwaiges „übersprungen" auf; `skipReplacement` umgekehrt.
- `applyReplacementsAndContinue` schreibt **nur** die Vorschläge mit gesetztem Ersatzprodukt, läuft nur wenn `allReplacementsHandled`, schreibt je Ersetzung die Audit-Zeile, setzt `reviewCompleted`, lädt die Maschinendaten neu (die Fächer haben jetzt andere Produkte und Bestand 0) und geht auf `PACKING`. Ein Schreibfehler ist **blockierend**: Fehler anzeigen, im Review bleiben — ein halb angewandter Review, der weiterläuft, führt den Fahrer mit falschen Produkten los.
- `skipReview` markiert alle noch unbearbeiteten als übersprungen (bereits gewählte Ersetzungen **bleiben**) und ruft dann denselben Pfad.
- **`isTourInMemory` muss angepasst werden — Pre-Flight-Fund, nicht optional.** Der Wächter lautet heute `step != RefillStep.PACKING || tourId.isNotEmpty()` (`RefillViewModel.kt:380-381`). Sobald `REVIEW` existiert, ist er während des Reviews **wahr**, und damit wird `loadData` zum No-op — genau der Aufruf, mit dem `applyReplacementsAndContinue` die geänderten Fachprodukte nachladen muss. Der Wächter wird deshalb explizit: `step == RefillStep.REFILL || step == RefillStep.SUMMARY || tourId.isNotEmpty()`. Der Zweck bleibt derselbe (eine **laufende Tour** vor einem Vollreload schützen); nur ist „nicht im Pack-Schritt" ab jetzt nicht mehr dasselbe wie „Tour läuft". Beide Nutzer des Wächters — `loadData`s Guard und das Wieder-Scharfstellen des Einstiegs-Gates — sind damit weiter korrekt, und `startTour`s Re-Entrancy-Guard ebenfalls (er ist ohnehin nur aus dem Pack-Schritt erreichbar). Im Report festhalten, dass du beide Aufrufstellen geprüft hast.

- [ ] **Step 1:** iOS Z. 1547-1613 und das 5a-`loadData` lesen.
- [ ] **Step 2:** Implementieren. Der `TourStore`-`when` muss compilierfehlerfrei den neuen Zweig behandeln.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` grün.
- [ ] **Step 4: Commit** — `feat(android): review step state and actions in RefillViewModel`.

---

### Task 5: UI — Review-Schritt

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/refill/ReviewStep.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt` (vierter Schritt im Indikator, Verzweigung, Übergabe)

Aufbau (Referenz `ReviewStepView.swift`):
- Eine Karte je Vorschlag: Grund-Badge (vier trennbare Farben, Texte an iOS' „Discontinued/Expired/No Stock/Unassigned" angelehnt), Automatenname, Fachnummer als Badge, aktuelles Produkt mit Bild und Fachbestand, und die Auswahl „Ersatz wählen" bzw. das gewählte Produkt mit Möglichkeit zum Ändern, plus „Überspringen" je Karte.
- Der Schritt-Indikator hat jetzt **vier** Stationen. Er bleibt **nicht antippbar** — dieselbe Begründung wie in 5a (dokumentiert dort in Task 11); dieser Task ändert daran nichts und baut keinen Rücksprung ein.
- Untere Leiste: „Alle überspringen" und „Weiter", letzteres nur aktiv wenn `allReplacementsHandled`, mit Spinner und gesperrten Eingaben während `isApplyingReplacements` (dasselbe Muster wie „Befüllung bestätigen" in 5a).
- Fehler über die bestehende Snackbar mit `clearError()`.

- [ ] **Step 1:** `ReviewStepView.swift` vollständig lesen; `PackingStep.kt` als Stilreferenz lesen.
- [ ] **Step 2:** Bauen, alle Texte in beide Locale-Dateien.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` grün.
- [ ] **Step 4: Commit** — `feat(android): add the refill review step`.

---

### Task 6: UI — Ersatzprodukt-Picker

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/refill/ReplacementPickerSheet.kt`

**Zuerst eine Entscheidung, die in den Report gehört:** `ui/machines/MachineAnalysisView.kt` hat schon einen Ersatzprodukt-Picker (`ReplaceProductSheet` + `SuggestionThumbnail`). Er ist einfacher als iOS' Review-Picker (Vorschlagsliste statt Bestands-Buckets und Kategorie-Gruppen). Prüfe, ob er sich **verallgemeinern** lässt, ohne die Analyse-Ansicht zu verändern — und wenn nicht, sage im Report konkret, welche Anforderung dagegen steht, statt stillschweigend eine zweite Variante zu bauen.

Inhalt (Referenz `ReplacementProductPicker.swift`):
- Suchfeld über alle aktiven Produkte.
- Gruppierung: **Bestands-Buckets** (auf Lager / kein Bestand) und darin **Kategorien**, jede Gruppe zusammenklappbar; die Kategorie des aktuell im Fach liegenden Produkts wird zuerst gezeigt (dafür `categoryIdOfCurrentProduct`).
- Je Zeile: Bild, Name, Lagerbestand, und — wenn das Produkt im selben Automaten schon in anderen Fächern liegt — ein Slot-Badge (iOS `slotBadgeLabel`, Z. 14). Nach dem Datenbefund aus 5a wird dieses Badge in echten Daten selten erscheinen; das ist korrekt.
- Deterministische Sortierung innerhalb jeder Gruppe (iOS `sortKey`, Z. 170) — total, mit Produkt-ID als letztem Tiebreaker.

- [ ] **Step 1:** `ReplacementProductPicker.swift` vollständig lesen, dann `ReplaceProductSheet` in `MachineAnalysisView.kt`; Entscheidung treffen.
- [ ] **Step 2:** Bauen, Texte in beide Locale-Dateien.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` grün.
- [ ] **Step 4: Commit** — `feat(android): add the replacement product picker`.

---

### Task 7: UI — Maschinen-Layout-Grid im Befüllen-Schritt

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/components/MachineLayoutGrid.kt` (gemeinsame Komponente)
- Modify: `android/app/src/main/java/xyz/vmflow/ui/machines/MachineAnalysisView.kt` (auf die gemeinsame Komponente umstellen)
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillStep.kt` (Grid einsetzen)

**Das ist ausdrücklich eine Extraktion, kein Neubau.** `MachineAnalysisView.kt` enthält `AnalysisLayoutGrid`, `AnalysisGridCell` und `buildGridEntries`; die Geometrie liegt in `MachineAnalysis.kt` (`slotRowCol`, `computeSlotWidths`, `buildGridSlots`) und ist in `MachineAnalysisTest.kt` getestet. Ziehe die **Darstellung** in eine parametrisierte Komponente, die beide Aufrufer bedienen kann: die Analyse färbt Zellen nach Produkt-Tier, der Refill färbt nach Füllzustand (leer / niedrig / voll / gerade befüllt) und markiert das aktuell bearbeitete Fach.

Randbedingungen:
- **Die Analyse-Ansicht muss sich sichtbar nicht verändern.** Das ist die Abnahmebedingung dieses Tasks: gleiche Optik, gleiche Farben, gleiches Verhalten beim Antippen. Wenn die Verallgemeinerung das nicht zulässt, brich ab und melde es, statt die Analyse-Ansicht anzupassen.
- Keine Änderung an `MachineAnalysis.kt` und keine an `MachineAnalysisTest.kt`. Bleiben die Tests grün, ist die Geometrie unangetastet.
- Im Refill sitzt das Grid über den Fachkarten und ist antippbar: ein Tipp scrollt zur Karte des Fachs (oder hebt sie hervor), er ändert **keinen** Bestand.

- [ ] **Step 1:** Die drei Android-Composables und `MachineLayoutGrid.swift` lesen; Parametrisierung entwerfen und im Report festhalten, bevor du schreibst.
- [ ] **Step 2:** Extrahieren, Analyse umstellen, Refill anschließen.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` grün (`MachineAnalysisTest` unverändert).
- [ ] **Step 4: Commit** — `refactor(android): share the machine layout grid between analysis and refill`.

---

### Task 8: Lokalisierung, Abschluss

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

- `grep -rn 'Text("' android/app/src/main/java/xyz/vmflow/ui/refill/` und `grep -rn 'contentDescription = "' .../ui/refill/` müssen leer sein — dazu dieselben Greps für `ui/components/MachineLayoutGrid.kt`.
- Schlüssel-Parität beider Locale-Dateien prüfen; erlaubte Differenz sind nur `app_name`, `supabase_anon_key`, `supabase_url`.
- Deutsch an `Localizable.xcstrings` angelehnt, wo iOS denselben Begriff hat; die vier Grund-Badges gehören dazu.

- [ ] **Step 1:** Sweep-Greps, Treffer beheben.
- [ ] **Step 2:** Paritätsprüfung, Ergebnis in den Report.
- [ ] **Step 3:** `./gradlew assembleDebug assembleRelease testDebugUnitTest` grün, Testzahl nennen.
- [ ] **Step 4: Commit** — `feat(android): localize the review step and layout grid`.

---

## Abnahme der Phase

Orchestrator, nicht Umsetzer:

1. `assembleDebug`, `assembleRelease` (R8) und die volle Suite grün.
2. **Der Review-Schritt erscheint gar nicht**, wenn kein Fach Ersatz braucht — kein leerer Zwischenschritt.
3. Mit echten Daten am S10: ein Vorschlag je Grund, soweit vorhanden. Mindestens ein `UNASSIGNED` (Fach ohne Produkt) ist in den Testdaten wahrscheinlich; `EXPIRED` lässt sich notfalls durch Rückdatieren einer Charge im Lager-Tab herstellen.
4. Ein Ersatz wird gesetzt und angewandt: das Fach trägt danach das neue Produkt mit Bestand **0**, taucht im Pack-Schritt mit vollem Defizit auf, und es existiert eine `refill_review_swap`-Zeile (im Feed unsichtbar — das ist so gewollt; per PWA/`/history` oder DB prüfen).
5. „Alle überspringen" führt direkt ins Packen und ändert **nichts** an den Fächern.
6. Ein fehlgeschlagener Schreibvorgang (Flugmodus) hält den Fahrer im Review, mit Meldung — er läuft nicht mit halb angewandtem Review los.
7. Das Layout-Grid: **die Analyse-Ansicht sieht unverändert aus** (Screenshots vor/nach vergleichen), und im Befüllen-Schritt zeigt das Grid die Fächer des aktuellen Automaten mit hervorgehobenem Arbeitsfach.
8. Kein hart codierter Text in `ui/refill/` und in der neuen gemeinsamen Komponente; Schlüssel-Parität geprüft.

**Was auch nach 5b offen bleibt:** die Restrisiken aus 5a (siehe dortigen Plan, Abschnitt „Bekannte Restrisiken") — insbesondere die einseitige Lagerbilanz, die fehlende Paginierung in `fetchRefillMachines` und der Tour-Rücksprung mit Rückbuchung. Keines davon gehört in diese Phase; alle drei bleiben ausdrücklich benannt statt still zu verschwinden.
