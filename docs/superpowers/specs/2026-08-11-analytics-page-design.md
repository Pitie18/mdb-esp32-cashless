# Analytics-Seite (iOS + PWA)

**Datum:** 2026-08-11
**Status:** Design abgenommen, Implementierung ausstehend
**Umfang:** neue SQL-RPCs + neue Seite in `ios/VMflow` + neue Seite in `management-frontend`

---

## 1. Problem

Die App beantwortet heute zwei Fragen gut und eine gar nicht.

Gut beantwortet: „Wie lief heute/diese Woche?" (Dashboard) und „Welches Produkt trägt sich in *diesem einen* Automaten?" (`MachineAnalysisView` mit `get_machine_product_kpis`).

Gar nicht beantwortet: alles, was quer über den Bestand geht. Wie viele Stück eines Artikels über alle Automaten in den letzten 90 Tagen? Welche Kategorie trägt den Umsatz? Ist der Umsatz-Bestseller auch der Gewinn-Bestseller? Wann wird überhaupt gekauft? Für jede dieser Fragen gibt es heute keinen Ort in der App.

Der bestehende Analyse-Tab bleibt unberührt — er ist Entscheidungshilfe für einen Automaten („was fliegt raus"), nicht Auswertung. Die neue Seite ist Auswertung.

## 2. Grundform

Report-Rahmen mit umschaltbarem Aufschlüsselungs-Block:

1. **Filterleiste** (sticky): Zeitraum · Automaten · Kategorien
2. **KPI-Zeile**: Umsatz · Stück · Rohertrag, jeweils mit Delta zur Vorperiode
3. **Metrik-Segment**: Stück · Umsatz · Rohertrag
4. **Verlaufschart** der gewählten Metrik
5. **Aufschlüsselung**: Dimensions-Segment (Artikel · Kategorie · Automat) + sortierte Liste
6. **Stoßzeiten** (Heatmap Wochentag × Stunde)
7. **Zahlungsarten** (bar / bargeldlos)

Metrik-Segment (3) und das Metrik-Segment der Aufschlüsselung sind **derselbe Zustand** — Umschalten an einer Stelle schaltet die andere mit. Deshalb gibt es genau drei Metriken; „Ø/Tag" ist keine vierte Metrik, sondern erscheint als Unterzeile jeder Listenzeile („Ø 13,7/Tag") und bezieht sich immer auf die gerade gewählte Metrik. Im Tagesverlauf wäre eine eigene Ø/Tag-Metrik bedeutungslos, weil der Durchschnitt je Tag mit dem Tageswert identisch ist.

Antippen einer Zeile öffnet ein Detail-Sheet.

Mockups: `.superpowers/brainstorm/*/content/combined-v1.html` (nicht versioniert).

## 3. Datenschicht

Eine neue, rein additive Migration `Docker/supabase/migrations/20260811000000_sales_analytics.sql`. Kein Schema-Eingriff, keine bestehende Migration wird angefasst.

### 3.1 Gemeinsame Filterparameter

Beide Funktionen nehmen dieselben Filter:

| Parameter | Typ | Bedeutung |
|---|---|---|
| `p_company_id` | `uuid` | Mandant; wird gegen `my_company_id()` geprüft |
| `p_from`, `p_to` | `timestamptz` | Fenstergrenzen, vom Client als lokale Tagesgrenzen mit Offset gesendet |
| `p_machine_ids` | `uuid[]` | `NULL` oder leer = alle Automaten der Company |
| `p_category_ids` | `uuid[]` | `NULL` oder leer = alle Kategorien |
| `p_timezone` | `text` | IANA-Zone, Default `'UTC'` |

Beide `LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public`, `GRANT EXECUTE` an `authenticated` und `service_role`. Stimmt `p_company_id` nicht mit `my_company_id()` überein, wird abgebrochen.

### 3.2 `get_sales_analytics_summary(...)` → `json`

```jsonc
{
  "range": { "from": "...", "to": "...", "days": 31, "timezone": "Europe/Berlin" },
  "totals":   { "units": 3104, "revenue_gross": 4812.00, "revenue_net": 4043.70,
                "gross_profit": 1930.12, "cost_net": 2113.58,
                "avg_daily_units": 100.1, "avg_daily_revenue": 155.2,
                "avg_ticket": 1.55 },
  "previous": { /* gleiche Felder, Fenster [from - days, from) */ },
  "daily":    [ { "day": "2026-07-01", "units": 96, "revenue_gross": 148.50,
                  "gross_profit": 61.20 } ],
  "heatmap":  [ { "dow": 1, "hour": 9, "units": 87, "revenue_gross": 131.20 } ],
  "channels": [ { "channel": "cashless", "units": 1930, "revenue_gross": 2983.10,
                  "avg_ticket": 1.71 } ],
  "missing_cost_products": 6,
  "unknown_product_units": 12
}
```

`daily` ist lückenlos: Tage ohne Verkauf erscheinen mit Nullwerten, damit der Chart keine Löcher zusammenschiebt. `dow` ist `1 = Montag … 7 = Sonntag` (ISO, `extract(isodow …)`).

`previous` ist das unmittelbar davorliegende Fenster gleicher Länge, also `[p_from − Länge, p_from)`. Bei einem Zeitraum vom 1.–31. Juli (31 Tage) ist die Vorperiode damit der 31. Mai bis 30. Juni — **nicht** „der Vormonat". Die Clients zeigen die berechneten Grenzen unter der KPI-Zeile an („vs. 31.05.–30.06."), damit der Vergleich nachvollziehbar bleibt und niemand ihn für einen Kalendermonat hält.

### 3.3 `get_sales_analytics_breakdown(..., p_dimension text)` → `json`

`p_dimension ∈ {'product', 'category', 'machine'}`; alles andere wirft.

Array, absteigend nach Umsatz sortiert (die Clients sortieren nach der gewählten Metrik selbst um):

```jsonc
[ {
  "key": "uuid", "label": "Coca-Cola 0,5", "image_path": "…/abc.png",
  "units": 412, "revenue_gross": 618.00, "revenue_net": 519.33, "gross_profit": 281.40,
  "prev_units": 361, "prev_revenue_gross": 541.50, "prev_gross_profit": 246.10,
  "share_pct": 12.8, "cumulative_share_pct": 12.8, "abc_class": "A",
  "avg_daily_units": 13.3, "avg_daily_revenue": 19.9, "avg_daily_gross_profit": 9.1,
  "total_capacity": 96, "total_stock": 51,
  "sell_through_pct": 43.1, "days_of_supply": 3.8,
  "machine_count": 6, "has_cost": true
} ]
```

Dimensionsabhängig:

- `product` — `image_path`, `machine_count`, Kapazität/Bestand aus `machine_trays` über die gefilterten Automaten
- `category` — `product_count` statt `machine_count`, kein `image_path`
- `machine` — `product_count`, Kapazität/Bestand des Automaten; `has_cost` ist dann true, wenn für alle verkauften Artikel EK-Preise vorliegen

`share_pct` und `cumulative_share_pct` sind **immer umsatzbasiert** — sie tragen die ABC-Klassifizierung, die sich nicht ändern darf, nur weil der Nutzer die Metrik umschaltet. `abc_class` wird über die kumulierte Umsatzanteilskurve vergeben: ≤ 80 % → `A`, ≤ 95 % → `B`, Rest → `C`. Berechnet wird sie für jede Dimension, **angezeigt** wird sie nur bei `product` — bei Kategorien und Automaten ist eine Pareto-Klasse nicht aussagekräftig genug, um Platz zu rechtfertigen.

Der Anteilsbalken hinter jeder Listenzeile ist davon unabhängig und wird clientseitig aus der **gerade gewählten** Metrik berechnet (Zeilenwert relativ zum größten Zeilenwert). Bei Metrik „Stück" bildet der Balken also Stückanteile ab, nicht Umsatzanteile.

Das Array wird **nicht** gekürzt. Auch bei mehreren hundert Artikeln kommt alles zurück; die Aufschlüsselung wäre sonst nicht summenkonsistent mit der KPI-Zeile darüber. Die Clients rendern die Liste ab 50 Zeilen mit „mehr anzeigen".

### 3.4 Rechenregeln

**Zeitzone.** Tagesgrenzen und Heatmap-Stunden werden über `created_at AT TIME ZONE p_timezone` gebildet, nicht über die Session-Zone. Ohne das läge die Heatmap in Deutschland um ein bis zwei Stunden daneben und die Tagesbalken wären um Mitternacht falsch geschnitten. Ergänzend gilt die bekannte Falle aus `project_date_range_filter_utc_shift`: die Clients senden `p_from`/`p_to` als vollständige ISO-8601-Zeitstempel **mit Offset**, nie als nackte `YYYY-MM-DDT00:00:00`-Strings.

**Produktzuordnung.** Primär `sales.product_id` (seit 2026-04-12 beim Insert gestempelt). Ist die Spalte `NULL` (Altbestand), wird über `machine_trays(machine_id, item_number)` nachgeschlagen — dieselbe Fallback-Logik wie in `get_machine_product_kpis`. Was auch dann nichts trifft, fließt in `totals`/`daily`/`heatmap`/`channels` normal ein, erscheint in der Aufschlüsselung aber gesammelt als Zeile „Unbekannt" und wird zusätzlich als `unknown_product_units` ausgewiesen. Stillschweigend verschwinden darf nichts, sonst passt die Summe der Liste nicht zum KPI darüber.

**Kategoriefilter.** Wirkt über `products.category`. Verkäufe ohne auflösbares Produkt haben keine Kategorie und fallen bei gesetztem Kategoriefilter heraus — das ist gewollt und der Grund, warum `unknown_product_units` sichtbar bleibt.

**Rohertrag.** Netto gegen Netto:

```
gross_profit = Σ ( price_net_je_verkauf − ek_netto_zum_verkaufstag )
```

- `price_net` kommt aus `sales.price_net`. Fehlt sie (Verkäufe vor der Steuer-Migration), wird sie über `resolve_product_tax_rate(product_id, sale_date)` aus `item_price` heruntergerechnet. Lässt sich auch das nicht auflösen, gilt `price_net = item_price`.
- Der EK-Preis ist die Notierung aus `product_purchase_prices` mit dem größten `observed_on ≤ Verkaufsdatum` (`LATERAL JOIN`). Liegt der Verkauf vor jeder Notierung, wird die älteste vorhandene genommen — besser eine leicht falsche Bezugsgröße als ein Loch in der Reihe.
- Artikel **ohne jede** Notierung fließen weder in `gross_profit` noch in `cost_net` ein. Sie zählen in `missing_cost_products` und werden unter der KPI-Zeile als Hinweis angezeigt („Rohertrag netto, 6 Artikel ohne EK-Preis"). In der Aufschlüsselung hat ihre Zeile `has_cost: false` und statt eines Rohertrags einen Platzhalter.

Der Umsatz-KPI bleibt **brutto** (`Σ item_price`), damit er mit Dashboard und Kassenbuch zusammenpasst. Der Netto-Umsatz steht zusätzlich in `totals`, wird aber nur im Detail-Sheet gezeigt.

**Sell-Through / Reichweite.** `sell_through_pct = units / (total_capacity × Wochen im Fenster) × 100`, gedeckelt bei 100 — dieselbe Formel wie in `MachineAnalysisViewModel`, damit beide Seiten nicht unterschiedliche Prozentwerte für dasselbe Produkt zeigen. `days_of_supply = total_stock / (units / Tage im Fenster)`, `null` bei `units = 0`.

**Nicht behandelt.** `suppressed_sales` braucht keine Sonderbehandlung — diese Zeilen stehen nie in `sales`. Die bekannten Doppelverkäufe (siehe `project_duplicate_sales_root_cause`) werden **nicht** dedupliziert; die Analytics-Seite zeigt denselben Datenbestand wie Dashboard und Kassenbuch, alles andere würde die Zahlen auseinanderlaufen lassen.

### 3.5 Vorgesehen, aber nicht gebaut

Die Rückgabe von `get_sales_analytics_summary` bekommt kein `visitors`-Feld, solange es keine Daten dafür gibt. Der Andockpunkt ist aber festgelegt: eine erweiterte Personen-/Besucherauswertung ergänzt `totals` um Besucherzahlen und `daily` um eine Besucherreihe, plus einen eigenen Block unter „Zahlungsarten". Weil beide Clients unbekannte JSON-Felder ignorieren, ist das später additiv nachrüstbar, ohne die RPC-Signatur zu ändern.

## 4. iOS (`ios/VMflow`)

### 4.1 Einordnung

Neuer Fall `analytics` in `SidebarItem` (`Navigation/AppNavigation.swift`), Icon `chart.xyaxis.line`, `compactTab` → `nil` (lebt unter „More"). Erste Zeile im ersten `Section` von `MoreView` (`VMflowApp.swift`), zusätzlicher Fall im `navigationDestination`-Switch, Eintrag in `SidebarNavigationView` für iPad.

### 4.2 Neue Dateien

| Datei | Inhalt |
|---|---|
| `Models/Analytics.swift` | DTOs (`AnalyticsSummary`, `AnalyticsBreakdownRow`, `HeatmapCell`, `ChannelSplit`) + reine Helfer: `abcClass`, `deltaPct`, `bucketing(for:)`, `heatmapIntensity` |
| `ViewModels/AnalyticsViewModel.swift` | Filter-State, `loadSummary()` / `loadBreakdown()`, Fehler- und Backend-Version-Behandlung |
| `Views/Analytics/AnalyticsView.swift` | Seitengerüst, Scroll-Aufbau |
| `Views/Analytics/AnalyticsFilterBar.swift` | Chips + drei Auswahl-Sheets |
| `Views/Analytics/AnalyticsBreakdownList.swift` | Dimensions-/Metrik-Segment + Zeilen mit Anteilsbalken |
| `Views/Analytics/HeatmapCard.swift` | Wochentag × Stunde |
| `Views/Analytics/ChannelSplitCard.swift` | bar / bargeldlos |
| `Views/Analytics/ProductAnalyticsSheet.swift` | Detail-Sheet |

Alle acht Dateien müssen von Hand in `ios/VMflow.xcodeproj/project.pbxproj` registriert werden — vier Stellen je Datei (`PBXBuildFile`, `PBXFileReference`, Gruppen-`children`, Sources-Phase). Das Projekt hat keine synchronized groups; ohne die Einträge kompiliert nichts. `xcodegen` wird **nicht** benutzt (löscht das xcscheme).

### 4.3 Interaktion

**Zeitraum-Sheet:** 7 Tage · 30 Tage · 90 Tage · dieser Monat · letzter Monat · eigener Zeitraum (zwei `DatePicker`, `p_to` wird auf das Tagesende gesetzt). Auswahl bleibt über `@AppStorage` erhalten.

**Automaten- und Kategorie-Sheet:** Mehrfachauswahl mit „Alle" als Sonderzustand. Der Chip zeigt „Alle Automaten" bzw. „3 Automaten".

**Metrik.** Ein `@Published var metric: AnalyticsMetric` treibt Chart *und* Liste. Die KPI-Karten sind Anzeige, kein Bedienelement; die zur gewählten Metrik gehörende Karte ist hervorgehoben.

**Chart.** Tagesbalken; ab einem Fenster > 60 Tagen Wochenbalken (`bucketing(for:)`). Wochenenden heller eingefärbt (`Calendar.current.isDateInWeekend`, wie `DailySales.isWeekend`). Gestrichelte Linie auf dem Tagesdurchschnitt der Vorperiode.

**Detail-Sheet.** Nur für die Dimension `product`: Kopf mit Bild und VK, KPI-Zeile, Verlauf des Artikels, Verteilung auf die Automaten (mit Bestand und „leer seit N Tagen"), Einkaufspreis-Spanne aus `get_product_purchase_summary`. Buttons „Produkt öffnen" und „Automat öffnen". Für `category` und `machine` navigiert die Zeile stattdessen direkt (Kategorie → Aufschlüsselung mit gesetztem Kategoriefilter, Automat → `MachineDetailView`).

### 4.4 Fallstricke, die hier vorab abgeräumt werden

- **Pull-to-Refresh:** `.dataRefreshable` aus `Utilities/View+DataRefreshable.swift`, nie das nackte `.refreshable`. SwiftUI bricht dessen Task mitten im Laden ab, der `CancellationError` wird geschluckt und die Ansicht bleibt still auf altem Stand (`project_ios_pull_to_refresh_cancellation`).
- **Tab-Root-`.task`:** über ein `didRunInitialLoad`-Flag im ViewModel gegen erneutes Feuern beim Tabwechsel absichern (`project_ios_task_refires_on_tab_switch`).
- **Lokalisierung:** deutsche Strings als `de`-Einträge in `Resources/Localizable.xcstrings`, Schlüssel = aufgelöstes `String(localized:)`-Literal, du-Ton, chirurgisch eingefügt — die Datei **nie** per Skript neu serialisieren (`reference_ios_xcstrings_editing`).

## 5. PWA (`management-frontend`)

| Datei | Inhalt |
|---|---|
| `app/pages/analytics.vue` | Seite |
| `app/components/analytics/*.vue` | Filterleiste, KPI-Zeile, Chart, Aufschlüsselung, Heatmap, Zahlungsarten, Detail-Dialog |
| `app/composables/useAnalytics.ts` | Filter-State (`useState`-geteilt), beide RPC-Aufrufe |
| `app/lib/analytics.ts` | reine Funktionen: ABC-Klassifizierung, Delta, Bucket-Wahl, Heatmap-Normalisierung, Sortierung nach Metrik |
| `AppSidebar.vue` | Navigationseintrag |
| `i18n/locales/{en,de}.json` | Texte |

Die rechnende Logik gehört nach `app/lib/analytics.ts`, nicht in Komponenten — nur so ist sie unter Vitest prüfbar. Das ist dasselbe Muster wie `app/lib/printSheet.ts`.

Layout identisch, nur breiter: ab `lg` stehen KPI-Zeile und Verlauf nebeneinander, Heatmap und Zahlungsarten in zwei Spalten. Die Aufschlüsselung bleibt volle Breite und zeigt auf Desktop zusätzliche Spalten (Vorperiode, Sell-Through), die auf dem Handy in die Unterzeile wandern.

## 6. Verhalten gegenüber älteren Backends

Der iOS-Client verbindet sich über `ServerStore` mit beliebigen selbstgehosteten Installationen. Ein Server ohne die neue Migration antwortet auf die RPC mit `404 PGRST202`. Dieser Fall wird gezielt erkannt und als eigener Zustand dargestellt: „Dieser Server unterstützt Analytics noch nicht — bitte Backend aktualisieren", statt einer rohen PostgREST-Meldung. Anders als bei `get_new_deals_count` im Dashboard (dort still auf 0) muss es hier sichtbar sein, weil die gesamte Seite an der RPC hängt.

Die Migration selbst ist für alte Firmware und alte Clients folgenlos: sie legt nur Funktionen an und ändert keine Tabelle.

## 7. Tests

**SQL** — `Docker/supabase/tests/analytics.test.sql`, über das vorhandene Harness (`run-sql-tests.sh`, Plain-ASSERT in zurückgerollter Transaktion, gefälschtes JWT):

- Mandantentrennung: eine fremde Company sieht keine Zeilen
- Fenstergrenzen: ein Verkauf exakt auf `p_from` zählt, einer exakt auf `p_to` zählt, einer eine Sekunde danach nicht
- Vorperiode: gleiches Fenster rückwärts, korrekt bei ungeraden Längen
- Rohertrag: mit EK-Notierung, ohne Notierung (`missing_cost_products` steigt, `gross_profit` bleibt unbeeinflusst), mit Notierung erst *nach* dem Verkauf (älteste greift)
- Fallback: Verkauf ohne `product_id`, aber mit passendem Tray → korrekt zugeordnet; ohne beides → „Unbekannt" plus `unknown_product_units`
- Zeitzone: derselbe Verkauf landet unter `Europe/Berlin` und `UTC` in unterschiedlichen Tages-/Stunden-Buckets
- ABC-Grenzen bei 80 % und 95 %
- `p_dimension` mit ungültigem Wert wirft

**PWA** — Vitest für alle Funktionen in `app/lib/analytics.ts`.

**iOS** — es gibt kein Test-Target. Die reinen Helfer aus `Models/Analytics.swift` werden per Wegwerf-`swift`-Skript geprüft, wie im Projekt üblich. Kompilierprüfung:

```
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

## 8. Bewusst nicht in v1

- **Besucher-/Conversion-Auswertung.** Andockpunkt festgelegt (§3.5), Umsetzung später.
- **CSV-Export.** Später über das iOS-Share-Sheet bzw. Download in der PWA.
- **`/api/v1`-Endpoint.** Die RPCs sind nicht über die API-Gateway-Routen erreichbar.
- **Entgangener Umsatz durch leere Slots.** Bräuchte eine belastbare Bestandshistorie; die Schätzung wäre zu unsicher, um sie als Zahl darzustellen.
- **KI-Auswertung der Analytics-Daten.** Der bestehende `machine-insights`-Pfad bleibt, wo er ist.
