# Android Phase 3: Maschinen-Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Maschinen-Tab auf den iOS-Stand bringen — Analyse-Ansicht, unterdrückte Verkäufe, Bestandskorrektur, Guthaben senden, Geräte-Gesundheit, Maschinen-Einstellungen und Lagerverfügbarkeit in der Liste.

**Architecture:** Wie Phase 2. Reine Logik in testbare Kotlin-Objekte, Abfragen in Repositories, Oberfläche in Compose nach Material 3. Kein Backend-Anteil.

**Tech Stack:** Kotlin 2.4.10 · AGP 9.3.1 · Gradle 9.5 · Compose BOM 2026.06.01 · compileSdk 36

## Ausgangslage

| | iOS | Android heute |
|---|---:|---:|
| `MachineDetailViewModel` | 261 LOC | 109 |
| `MachineListViewModel` | 287 LOC | 69 |
| `MachineAnalysisViewModel` | 523 LOC | **0** |
| Views | 2.474 LOC über 7 Dateien | 744 über 3 |

## Referenz-Implementierung

| iOS-Datei | Was daraus portiert wird |
|---|---|
| [`ViewModels/MachineAnalysisViewModel.swift`](../../../ios/VMflow/ViewModels/MachineAnalysisViewModel.swift) | Rasterlayout, Bewertung, Vorschlagspool, Analyse-Ablauf |
| [`Views/Machines/MachineAnalysisView.swift`](../../../ios/VMflow/Views/Machines/MachineAnalysisView.swift) | Analyse-Oberfläche |
| [`Views/Refill/MachineLayoutGrid.swift`](../../../ios/VMflow/Views/Refill/MachineLayoutGrid.swift) | Rasterdarstellung (wird von Analyse **und** Refill genutzt) |
| [`ViewModels/MachineDetailViewModel.swift`](../../../ios/VMflow/ViewModels/MachineDetailViewModel.swift) | Unterdrückte Verkäufe, Bestandskorrektur, Guthaben, Einstellungen |
| [`Views/Machines/DeviceHealthSheet.swift`](../../../ios/VMflow/Views/Machines/DeviceHealthSheet.swift), [`MachineSettingsSheet.swift`](../../../ios/VMflow/Views/Machines/MachineSettingsSheet.swift) | die beiden Sheets |
| [`ViewModels/MachineListViewModel.swift`](../../../ios/VMflow/ViewModels/MachineListViewModel.swift) | Lagerverfügbarkeit je Automat |

**Umsetzer lesen die jeweilige iOS-Datei, bevor sie beginnen.** In Phase 2 haben zwei Umsetzer dadurch echte Fehler in meiner Plan-Prosa gefunden; das ist erwünscht und gehört in den Report.

## Global Constraints

- Keine Backend-Änderungen. Alle Daten aus vorhandenen Tabellen und RPCs (`get_machine_product_kpis`, `get_product_sales_velocity`, `machine_product_offerings`).
- `sales.item_price` ist **EUR**, nie Cent.
- Alle neuen Nutzertexte als Ressourcen in `values/strings.xml` **und** `values-de/strings.xml`; Mengen über `<plurals>`.
- Material 3, `stringResource`, `contentDescription` an Icon-Buttons, keine festen Zeilenhöhen.
- `SupabaseService.client` nur über Getter, nie in einem `val` festgehalten — der Server-Picker tauscht den Client aus.
- Nur JUnit 4 im Testpfad. Keine neuen Abhängigkeiten ohne Prüfung des `minCompileSdk` gegen 36.
- Commits: `git add <pfade>`, dann `git commit` **ohne** Pathspec. Niemals `--amend`, `reset`, `rebase`.

## Testumgebung

Physisches Gerät: Samsung Galaxy S10, `adb -s RF8M32CG58F`, Android 16 / API 36. Der lokale Supabase-Stack läuft auf dem Mac unter `http://10.0.1.146:54321` und ist vom Telefon erreichbar.

```bash
cd android && ./gradlew assembleDebug \
  -PSUPABASE_URL=http://10.0.1.146:54321 \
  -PSUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0
adb -s RF8M32CG58F install -r app/build/outputs/apk/debug/app-debug.apk
```

Die App ist dort bereits angemeldet. **Umsetzer installieren nicht selbst** — Screenshots und Anmeldung macht der Orchestrator.

---

### Task 23: Reine Analyse-Logik

Der Kern der Phase. Fünf Funktionen, kein Android, keine Netzwerkzugriffe — und die Stelle, an der Android und iOS auseinanderlaufen würden, wenn man sie falsch übersetzt.

**Vorher lesen:** `ios/VMflow/ViewModels/MachineAnalysisViewModel.swift`, Zeilen 111–213.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/MachineAnalysis.kt`
- Create: `android/app/src/test/java/xyz/vmflow/data/MachineAnalysisTest.kt`

**Interfaces:**
- Produces:
  - `enum class SlotTier { DEAD, WEAK, TESTING, OK, STRONG, EMPTY }`
  - `data class ScoreOpts(val gracePeriodDays: Int = 14, val weakSellThrough: Double = 15.0, val strongSellThrough: Double = 40.0)`
  - `MachineAnalysis.slotRowCol(itemNumber: Int): SlotPosition` mit `data class SlotPosition(val row: Int, val column: Int)`
  - `MachineAnalysis.computeSlotWidths(items: List<Int>): Map<Int, Int>`
  - `MachineAnalysis.scoreProduct(unitsSold: Int, sellThroughPct: Double, tenureDays: Int?, opts: ScoreOpts = ScoreOpts()): SlotTier`
  - `MachineAnalysis.buildSuggestionPool(products, velocity, productsInMachine, maxBestsellers = 5, maxNewcomers = 5): SuggestionPool`
  - `MachineAnalysis.buildGridSlots(trays, tierByProduct): List<AnalysisGridSlot>`
  - `const val COLUMNS_PER_ROW = 10`

Die Regeln, wörtlich aus dem Swift:

```
slotRowCol(item)      row = max(0, item / 10 - 1)
                      column = ((item % 10) + 10) % 10

computeSlotWidths     je Zeile aufsteigend sortieren; Breite = nächstes Item − dieses Item,
                      beim letzten der Zeile = COLUMNS_PER_ROW − column;
                      dann auf [1, COLUMNS_PER_ROW − column] begrenzen

scoreProduct          unitsSold <= 0            -> DEAD
                      sellThroughPct < 15       -> WEAK
                      sellThroughPct < 40       -> OK
                      sonst                     -> STRONG
                      danach: ist das Ergebnis DEAD oder WEAK und tenureDays != null
                      und tenureDays < 14, dann stattdessen TESTING

buildSuggestionPool   eligible = Produkte, die NICHT im Automaten sind
                      bestsellers = eligible mit velocity > 0, absteigend nach velocity, max 5
                      newcomers   = eligible mit velocity <= 0, alphabetisch (Groß/Klein egal), max 5

buildGridSlots        tier = EMPTY, wenn der Tray kein productId hat
```

- [ ] **Step 1: Fehlschlagende Tests schreiben**

Mindestens diese Fälle, je ein eigener `@Test` mit Namen in Backticks:

*slotRowCol* — Item 0 → (0,0); Item 5 → (0,5); Item 11 → (0,1); Item 15 → (0,5); Item 20 → (1,0); Item 25 → (1,5); Item 99 → (8,9). Ein Item unter 10 darf **nie** eine negative Zeile ergeben.

*computeSlotWidths* — lückenlose Zeile 10..19 → jedes 1 breit; Lücke: Items 10, 12, 15 in einer Zeile → Breiten 2, 3, 5; einzelnes Item 15 allein in seiner Zeile → Breite 5 (bis Zeilenende); Item 19 allein → Breite 1; Items in verschiedenen Zeilen beeinflussen sich **nicht**; leere Eingabe → leere Map.

*scoreProduct* — 0 verkauft ohne Tenure → DEAD; 0 verkauft mit tenureDays 5 → TESTING; 0 verkauft mit tenureDays 20 → DEAD; 10 verkauft bei 10 % → WEAK; 10 verkauft bei 10 % mit tenureDays 5 → TESTING; 10 verkauft bei 25 % → OK; 10 verkauft bei 25 % mit tenureDays 5 → **OK** (die Schonfrist rettet nur DEAD und WEAK); 10 verkauft bei 60 % → STRONG; genau 15 % → OK (Grenze ist `<`); genau 40 % → STRONG; tenureDays genau 14 → keine Schonfrist mehr.

*buildSuggestionPool* — Produkte im Automaten tauchen in keiner Liste auf; Bestseller sind nach Velocity absteigend; höchstens 5 Bestseller und 5 Neulinge; Produkte mit Velocity 0 sind Neulinge, nicht Bestseller; Neulinge sind alphabetisch und case-insensitiv sortiert; ein Produkt ohne Namen fällt auf `"Unknown"` zurück.

*buildGridSlots* — ein Tray ohne `productId` bekommt Tier EMPTY; ein Tray mit Produkt ohne Eintrag in `tierByProduct` bekommt ebenfalls EMPTY; Breiten stammen aus `computeSlotWidths`; Zeile und Spalte stammen aus `slotRowCol`.

- [ ] **Step 2: Fehlschlag bestätigen, implementieren, grün.**

- [ ] **Step 3: Gesamtlauf und Commit**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Commit-Nachricht: erklären, *warum* die Schonfrist nur DEAD und WEAK rettet (ein gut laufendes neues Produkt soll nicht als „im Test" verharmlost werden) und warum Lücken in der Item-Nummerierung den vorherigen Slot verbreitern (die Nummern bilden die physische Breite der Schächte ab).

---

### Task 24: Analyse-Daten und ViewModel

**Vorher lesen:** `MachineAnalysisViewModel.swift` ab Zeile 215 vollständig.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/MachineAnalysisRepository.kt`
- Create: `android/app/src/main/java/xyz/vmflow/ui/machines/MachineAnalysisViewModel.kt`
- Create: Tests für die Zusammenführung (Tenure → Tier je Produkt) mit einem Fake-Repository

Datenquellen, unverändert aus iOS: RPC `get_machine_product_kpis(machine_id, company_id, days)`, RPC `get_product_sales_velocity(company_id, days)`, Tabelle `machine_product_offerings` für `offered_since`, plus der Produktkatalog.

**Wichtig:** `offered_since` zählt, wie lange ein Produkt **im Automaten** angeboten wird — unabhängig davon, in welchem Schacht. Ein Produktwechsel zwischen Schächten setzt die Frist **nicht** zurück. Daraus ergibt sich `tenureDays` für `scoreProduct`.

---

### Task 25: Analyse-Oberfläche

**Vorher lesen:** `MachineAnalysisView.swift` und `MachineLayoutGrid.swift` vollständig.

Ein Springboard-artiges Raster, in dem jeder Schacht nach Tier eingefärbt ist, darunter eine Liste „Produkte prüfen" mit kombinierten Kennzahlen und Automaten-Tenure, und ein Ein-Klick-Austausch gegen Bestseller oder Neulinge.

Android-Regeln: Das Raster als `LazyVerticalGrid` mit `GridCells.Fixed(10)` und `span` gemäß der berechneten Breite. Tier-Farben aus dem Material-Schema ableiten, nicht hart codieren. Der Austausch bestätigt vorher per `AlertDialog` — er setzt den Bestand auf 0 und schreibt ins `activity_log`.

---

### Task 26: Maschinendetail — unterdrückte Verkäufe und Bestandseingriffe

**Vorher lesen:** `MachineDetailViewModel.swift` vollständig, besonders `loadSuppressedSales`, `restoreSuppressed`, `adjustStock`, `fillTray`.

Die Verkaufsliste bekommt Tagesgruppierung mit Datums-Kopfzeilen und zeigt unterdrückte Verkäufe als markierte, **nicht mitzählende** Zeilen mit Wiederherstellen-Dialog.

---

### Task 27: Guthaben senden, Einstellungen, Geräte-Gesundheit

Drei `ModalBottomSheet`s nach den iOS-Sheets. **Guthaben senden** ruft die Edge Function `send-credit` — das ist die einzige Stelle der Phase, die etwas an ein Gerät im Feld schickt, und bekommt deshalb einen Bestätigungsdialog mit Betrag und Automatenname im Text.

---

### Task 28: Maschinenliste — Lagerverfügbarkeit

**Vorher lesen:** `MachineListViewModel.swift`, besonders `warehouseAvail`.

Die Karte zeigt zusätzlich, wie viel des Bedarfs dieses Automaten aus dem Lager gedeckt werden kann.

---

### Task 29: Nachzügler aus Phase 2

Am Gerät gefunden, gehört inhaltlich hierher:

1. **Produktbilder im Aktivitäts-Feed fehlen.** Links jeder Zeile ist eine leere Spalte; iOS zeigt dort das Produktbild. `SaleWithMachine.productImagePath` wird geladen, aber nicht gerendert — oder die URL wird falsch gebaut. Ursache feststellen, beheben, am Gerät prüfen.
2. **KPI-Karten-Peek.** Die Reihe scrollt korrekt und alle sechs Karten sind erreichbar, aber der Anschnitt sitzt mitten im Text. Kartenbreite und `contentPadding` so wählen, dass der Peek als Peek erkennbar ist.

---

### Task 30: Texte

Alle neuen Nutzertexte der Phase in beide Sprachdateien, Mengen als `<plurals>`.

---

## Abnahme der Phase

Build grün, alle Tests grün, und am S10 angemeldet geprüft: Analyse-Raster mit eingefärbten Schächten, Produktliste mit Tiers, Austausch funktioniert, unterdrückte Verkäufe sichtbar und wiederherstellbar, die drei Sheets öffnen und tun was sie sagen, Produktbilder im Feed sichtbar.
