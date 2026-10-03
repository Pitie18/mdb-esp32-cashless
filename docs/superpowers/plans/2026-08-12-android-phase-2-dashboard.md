# Android Phase 2: Dashboard-Tiefe — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Android-Dashboard auf den inhaltlichen Stand des iOS-Dashboards bringen — Vergleichszeiträume, 30-Tage-Chart, zusammengeführter Aktivitäts-Feed mit Nachladen, Barkassen-Einträge und Deals-Banner.

**Architecture:** Die Fachlogik wandert in reine, testbare Kotlin-Funktionen (`ActivityFeedBuilder`, `DashboardKpis`, `DailyChart`), die Datenbeschaffung in ein `DashboardRepository` nach dem Muster der bestehenden Repositories, und die Oberfläche in Compose nach Material-3-Konventionen. Kein Backend-Anteil.

**Tech Stack:** Kotlin 2.4.10 · Compose BOM 2026.06.01 · Material 3 · Supabase Kotlin SDK 3.1.4 · JUnit 4

## Referenz-Implementierung

Diese Phase portiert bestehendes Verhalten. Die **maßgeblichen** Quellen sind:

| iOS-Datei | Was daraus portiert wird |
|---|---|
| [`ViewModels/DashboardViewModel.swift`](../../../ios/VMflow/ViewModels/DashboardViewModel.swift) | KPI-Eimer, Chart-Fenster, Feed-Laden, Nachlade- und Erschöpfungslogik |
| [`Models/ActivityFeed.swift`](../../../ios/VMflow/Models/ActivityFeed.swift) | Feed-Modelle und die reinen Builder |
| [`Views/Dashboard/DashboardView.swift`](../../../ios/VMflow/Views/Dashboard/DashboardView.swift) | Abschnitte, Reihenfolge, Zeilendarstellung |
| [`Views/Dashboard/CashBookCard.swift`](../../../ios/VMflow/Views/Dashboard/CashBookCard.swift) | Barkassen-Karte |

**Umsetzer lesen die genannte iOS-Datei, bevor sie den jeweiligen Task beginnen.** Für die reine Logik steht der vollständige Kotlin-Code unten im Plan; für die Oberfläche ist die iOS-Datei die Vorlage, umgesetzt nach den Android-Regeln in den Global Constraints.

## Global Constraints

- **Keine Backend-Änderungen.** Keine Migrationen, nichts unter `Docker/`. Alle Daten kommen aus vorhandenen Tabellen und RPCs.
- **`activity_log.metadata` tolerant dekodieren** — `ignoreUnknownKeys = true`, jedes Feld optional mit Default. Es ist ein Vertrag zwischen PWA, iOS und Android; ein iOS-Feld von Objekt auf Array zu ändern hat den Feed schon einmal zerlegt.
- **Warehouse-Intakes tragen ZWEI Typ-Strings:** die PWA bucht `incoming`, iOS `intake`. Immer **beide** abfragen.
- **Alle neuen Nutzertexte als Ressourcen**, in `values/strings.xml` *und* `values-de/strings.xml`. Mengen über `<plurals>`, niemals `"tray(s)"`.
- **Android-Konventionen** wie in Phase 1: Material 3, `stringResource`, `contentDescription` an Icon-Buttons, 48 dp Touch-Targets, keine festen Zeilenhöhen (Schriftskalierung bis 200 %).
- **Währung** über `NumberFormat.getCurrencyInstance` mit EUR, wie bereits in `DashboardScreen.kt`.
- **`sales.item_price` ist EUR, nicht Cent.** Niemals durch 100 teilen.
- compileSdk/targetSdk bleiben 36, AGP 8.13.2, Gradle 8.13. Keine neuen Abhängigkeiten ohne Prüfung des `minCompileSdk` gegen 36.
- Commits: `git add <pfade>` und dann `git commit` **ohne** Pathspec. Niemals `--amend`, `reset`, `rebase`.

## Testumgebung

Ein lokaler Supabase-Stack läuft und ist mit echten Daten befüllt. Der Debug-Build zeigt über Build-Vorgaben darauf:

```bash
cd android && ./gradlew assembleDebug \
  -PSUPABASE_URL=http://10.0.2.2:54321 \
  -PSUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0
```

Der Emulator `Pixel_9a` erreicht den Host unter `10.0.2.2`; Klartext-HTTP ist im Debug-Build erlaubt. Anmeldung erfolgt durch den Orchestrator, nicht durch Umsetzer.

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `models/ActivityFeed.kt` | `ActivityLogRow`, `ActivityLogMetadata`, `IntakeTransactionRow`, `RefillActivity`, `TourActivity`, `CashBookActivity`, `IntakeGroup`, `ActivityFeedItem` |
| `data/ActivityFeedBuilder.kt` | Reine Builder: `groupIntakes`, `makeActivityItems`, `mergeFeed` |
| `data/DashboardKpis.kt` | Reine KPI-Eimer und Chart-Vorbelegung |
| `data/DashboardRepository.kt` | Alle Abfragen des Dashboards |
| `ui/dashboard/DashboardChart.kt` | 30-Tage-Balkenchart |
| `ui/dashboard/ActivityFeedRow.kt` | Zeilendarstellung je Feed-Typ |
| `ui/dashboard/CashBookCard.kt` | Barkassen-Karte |
| Tests zu allen `data/`-Dateien | |

**Geändert:** `ui/dashboard/DashboardScreen.kt`, `ui/dashboard/DashboardViewModel.kt`, `ui/dashboard/KpiCard.kt`, `ui/navigation/TopLevelDestination.kt`, beide `strings.xml`

---

### Task 16: Feed-Modelle und reine Builder

Der Kern der Phase: die Logik, die aus drei Datenquellen eine Zeitleiste macht. Vollständig testbar, kein Android, keine Netzwerkzugriffe.

**Vorher lesen:** [`ios/VMflow/Models/ActivityFeed.swift`](../../../ios/VMflow/Models/ActivityFeed.swift) — vollständig. Der Kotlin-Code unten ist die Übersetzung; bei Abweichungen gilt die Swift-Datei.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/models/ActivityFeed.kt`
- Create: `android/app/src/main/java/xyz/vmflow/data/ActivityFeedBuilder.kt`
- Create: `android/app/src/test/java/xyz/vmflow/data/ActivityFeedBuilderTest.kt`

**Interfaces:**
- Produces:
  - `ActivityFeedBuilder.groupIntakes(rows: List<IntakeTransactionRow>): List<IntakeGroup>`
  - `ActivityFeedBuilder.makeActivityItems(rows: List<ActivityLogRow>): List<ActivityFeedItem>`
  - `ActivityFeedBuilder.mergeFeed(sales: List<SaleWithMachine>, activityRows: List<ActivityLogRow>, intakeGroups: List<IntakeGroup>): List<ActivityFeedItem>`
  - `const val INTAKE_SESSION_GAP_MS = 15 * 60 * 1000L`

Die drei Regeln, an denen die Tests hängen und die iOS exakt so umsetzt:

1. **Sitzungsgrenze:** Eine neue Intake-Gruppe beginnt, wenn Benutzer *oder* Lager wechselt, **oder** der Abstand zur vorigen Buchung 15 Minuten überschreitet. Genau 15 Minuten gehören noch zur selben Sitzung (`<=`).
2. **Gruppen-Identität:** `id` ist die ID der **ältesten** Transaktion der Gruppe (stabil über Neuladen hinweg), `date` der Zeitstempel der **neuesten** (für Sortierung und Tagesgruppierung).
3. **Produktaggregation:** Mengen je Produktname summieren, **Reihenfolge des ersten Auftretens** bewahren.

- [ ] **Step 1: Fehlschlagende Tests schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/data/ActivityFeedBuilderTest.kt`:

```kotlin
package xyz.vmflow.data

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.vmflow.models.ActivityFeedItem
import xyz.vmflow.models.ActivityLogMetadata
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.NameOnly

class ActivityFeedBuilderTest {

    private val base = Instant.parse("2026-08-12T10:00:00Z")
    private fun t(minutes: Long) = base.plus(kotlin.time.Duration.parse("${minutes}m"))

    private fun intake(
        id: String,
        minutes: Long,
        user: String? = "u1",
        warehouse: String? = "w1",
        qty: Int = 1,
        product: String? = "Cola",
    ) = IntakeTransactionRow(
        id = id,
        createdAt = t(minutes),
        warehouseId = warehouse,
        userId = user,
        quantityChange = qty,
        products = product?.let { NameOnly(it) },
        warehouses = NameOnly("Hauptlager"),
    )

    @Test
    fun `bookings within the session gap form one group`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0), intake("b", 10), intake("c", 14))
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].totalUnits)
    }

    @Test
    fun `exactly fifteen minutes still counts as the same session`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0), intake("b", 15)))
        assertEquals(1, groups.size)
    }

    @Test
    fun `a gap beyond fifteen minutes starts a new group`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0), intake("b", 16)))
        assertEquals(2, groups.size)
    }

    @Test
    fun `a different user starts a new group even within the gap`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0, user = "u1"), intake("b", 5, user = "u2"))
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `a different warehouse starts a new group even within the gap`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("a", 0, warehouse = "w1"), intake("b", 5, warehouse = "w2"))
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `the group id is the oldest transaction and the date is the newest`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("newest", 10), intake("oldest", 0), intake("middle", 5))
        )
        assertEquals(1, groups.size)
        assertEquals("oldest", groups[0].id)
        assertEquals(t(10), groups[0].date)
    }

    @Test
    fun `rows arriving out of order are sorted before grouping`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(intake("c", 40), intake("a", 0), intake("b", 5))
        )
        assertEquals(2, groups.size)
        assertEquals("a", groups[0].id)
        assertEquals("c", groups[1].id)
    }

    @Test
    fun `quantities are summed per product in first-seen order`() {
        val groups = ActivityFeedBuilder.groupIntakes(
            listOf(
                intake("a", 0, product = "Cola", qty = 2),
                intake("b", 1, product = "Fanta", qty = 5),
                intake("c", 2, product = "Cola", qty = 3),
            )
        )
        assertEquals(listOf("Cola" to 5, "Fanta" to 5), groups[0].products.map { it.name to it.quantity })
        assertEquals(10, groups[0].totalUnits)
    }

    @Test
    fun `a missing product name falls back to a dash`() {
        val groups = ActivityFeedBuilder.groupIntakes(listOf(intake("a", 0, product = null)))
        assertEquals("—", groups[0].products.first().name)
    }

    @Test
    fun `an empty input yields no groups`() {
        assertTrue(ActivityFeedBuilder.groupIntakes(emptyList()).isEmpty())
    }

    private fun logRow(id: String, action: String, minutes: Long, meta: ActivityLogMetadata? = null) =
        ActivityLogRow(id = id, createdAt = t(minutes), action = action, metadata = meta)

    @Test
    fun `a refill row becomes a machineRefilled item`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("r", "stock_refill_tour", 0, ActivityLogMetadata(machineName = "Automat 1", traysRefilled = 3, totalAdded = 12)))
        )
        assertEquals(1, items.size)
        val item = items.first() as ActivityFeedItem.MachineRefilled
        assertEquals("Automat 1", item.activity.machineName)
        assertEquals(3, item.activity.traysRefilled)
    }

    @Test
    fun `a tour row falls back to the machine name count when machineCount is absent`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("t", "tour_started", 0, ActivityLogMetadata(machineNames = listOf("A", "B"))))
        )
        val item = items.first() as ActivityFeedItem.TourStarted
        assertEquals(2, item.activity.machineCount)
    }

    @Test
    fun `a cash book row keeps its amount and type`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("c", "cash_book_entry_created", 0, ActivityLogMetadata(cashType = "deposit", amount = 42.5)))
        )
        val item = items.first() as ActivityFeedItem.CashBookEntry
        assertEquals(42.5, item.activity.amount, 0.001)
    }

    @Test
    fun `unknown actions are skipped rather than crashing the feed`() {
        val items = ActivityFeedBuilder.makeActivityItems(
            listOf(logRow("x", "something_new_from_the_pwa", 0), logRow("r", "stock_refill_tour", 1))
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `missing metadata degrades to defaults instead of throwing`() {
        val items = ActivityFeedBuilder.makeActivityItems(listOf(logRow("r", "stock_refill_tour", 0, null)))
        val item = items.first() as ActivityFeedItem.MachineRefilled
        assertEquals("—", item.activity.machineName)
        assertEquals(0, item.activity.traysRefilled)
    }

    @Test
    fun `the merged feed is ordered newest first across all sources`() {
        val merged = ActivityFeedBuilder.mergeFeed(
            sales = emptyList(),
            activityRows = listOf(logRow("old", "stock_refill_tour", 0), logRow("new", "stock_refill_tour", 30)),
            intakeGroups = ActivityFeedBuilder.groupIntakes(listOf(intake("mid", 15))),
        )
        assertEquals(listOf(t(30), t(15), t(0)), merged.map { it.date })
    }

    @Test
    fun `feed item ids are unique and prefixed per type`() {
        val merged = ActivityFeedBuilder.mergeFeed(
            sales = emptyList(),
            activityRows = listOf(logRow("shared-id", "stock_refill_tour", 0)),
            intakeGroups = ActivityFeedBuilder.groupIntakes(listOf(intake("shared-id", 5))),
        )
        assertEquals(2, merged.map { it.id }.toSet().size)
        assertTrue(merged.any { it.id.startsWith("refill-") })
        assertTrue(merged.any { it.id.startsWith("intake-") })
    }
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.data.ActivityFeedBuilderTest"
```

Erwartet: `BUILD FAILED` mit `Unresolved reference` auf die Modelle.

- [ ] **Step 3: Modelle anlegen**

Neue Datei `android/app/src/main/java/xyz/vmflow/models/ActivityFeed.kt`. Übersetze die Swift-Modelle aus `ios/VMflow/Models/ActivityFeed.swift` Zeile 1–172 nach Kotlin, mit diesen Vorgaben:

- Alle Serialisierungs-Datenklassen `@Serializable`, IDs als `String` (nicht `UUID` — die bestehenden Android-Modelle nutzen durchgehend `String`).
- `ActivityLogMetadata`: **jedes** Feld nullable mit Default `null`. Die Wire-Keys stehen in den `CodingKeys` von `ActivityFeed.swift` und sind teilweise **nicht** die naheliegenden — verifiziert am Original:
  `tour_id`, `machine_name`, `trays_refilled`, `total_added`, `machine_count`, `machine_names`, `warehouse_name`, sowie `products`, `amount`, `category` unverändert — **aber** `userDisplay` heißt auf der Leitung `_user_display` (mit führendem Unterstrich), `cashType` heißt `type`, und `note` heißt `description`.
- `ActivityFeedItem` wird eine `sealed interface` mit den Varianten `Sale`, `MachineRefilled`, `TourStarted`, `StockIntake`, `CashBookEntry`, jeweils mit `val id: String` und `val date: Instant`. Die ID-Präfixe (`sale-`, `refill-`, `tour-`, `intake-`, `cashentry-`) sind Pflicht — zwei Quellen können dieselbe Roh-ID tragen.
- `CashBookEntryType` als Enum mit exakt den Fällen aus `ios/VMflow/Models/CashBook.swift`: `initial`, `withdrawal`, `correction`, `payout`, `expense`, `reversal`, `unknown`. Ein `deposit` gibt es **nicht**. Unbekannte Strings werden zu `unknown`, niemals zu einer Ausnahme.
- Zeitstempel als `kotlinx.datetime.Instant`.

- [ ] **Step 4: Builder implementieren**

Neue Datei `android/app/src/main/java/xyz/vmflow/data/ActivityFeedBuilder.kt`:

```kotlin
package xyz.vmflow.data

import xyz.vmflow.models.ActivityFeedItem
import xyz.vmflow.models.ActivityLogRow
import xyz.vmflow.models.CashBookActivity
import xyz.vmflow.models.CashBookEntryType
import xyz.vmflow.models.IntakeGroup
import xyz.vmflow.models.IntakeTransactionRow
import xyz.vmflow.models.ProductLine
import xyz.vmflow.models.RefillActivity
import xyz.vmflow.models.SaleWithMachine
import xyz.vmflow.models.TourActivity

/**
 * Pure builders for the dashboard timeline — no I/O, so they are unit
 * testable. Mirrors ActivityFeedBuilder in ios/VMflow/Models/ActivityFeed.swift;
 * both clients must group and order identically or the same tour looks
 * different on each phone.
 */
object ActivityFeedBuilder {

    /** Longest gap between two bookings that still counts as one intake session. */
    const val INTAKE_SESSION_GAP_MS = 15 * 60 * 1000L

    /**
     * Group incoming warehouse transactions into intake sessions.
     *
     * Rows may arrive in any order and are sorted ascending first. A new
     * group starts when the user or the warehouse changes, or when the gap
     * to the previous booking exceeds [INTAKE_SESSION_GAP_MS].
     */
    fun groupIntakes(rows: List<IntakeTransactionRow>): List<IntakeGroup> {
        val sorted = rows.sortedBy { it.createdAt }
        val groups = mutableListOf<IntakeGroup>()
        val current = mutableListOf<IntakeTransactionRow>()

        fun flush() {
            val first = current.firstOrNull() ?: return
            val last = current.last()
            // Aggregate per product name, preserving first-seen order.
            val order = mutableListOf<String>()
            val qty = mutableMapOf<String, Int>()
            for (row in current) {
                val name = row.products?.name ?: "—"
                if (name !in qty) order += name
                qty[name] = (qty[name] ?: 0) + row.quantityChange
            }
            groups += IntakeGroup(
                // Oldest row's id: stable across reloads, so row expansion survives.
                id = first.id,
                // Newest timestamp: drives sorting and day grouping.
                date = last.createdAt,
                userId = first.userId,
                userDisplay = null,
                warehouseName = first.warehouses?.name,
                totalUnits = current.sumOf { it.quantityChange },
                products = order.map { ProductLine(name = it, quantity = qty[it] ?: 0) },
            )
            current.clear()
        }

        for (row in sorted) {
            val prev = current.lastOrNull()
            if (prev != null) {
                val sameSession = prev.userId == row.userId &&
                    prev.warehouseId == row.warehouseId &&
                    (row.createdAt.toEpochMilliseconds() - prev.createdAt.toEpochMilliseconds()) <= INTAKE_SESSION_GAP_MS
                if (!sameSession) flush()
            }
            current += row
        }
        flush()
        return groups
    }

    /** Map activity_log rows to feed items. Unknown actions are skipped. */
    fun makeActivityItems(rows: List<ActivityLogRow>): List<ActivityFeedItem> =
        rows.mapNotNull { row ->
            val meta = row.metadata
            when (row.action) {
                "stock_refill_tour" -> ActivityFeedItem.MachineRefilled(
                    RefillActivity(
                        id = row.id,
                        createdAt = row.createdAt,
                        machineName = meta?.machineName ?: "—",
                        traysRefilled = meta?.traysRefilled ?: 0,
                        totalAdded = meta?.totalAdded ?: 0,
                        userDisplay = meta?.userDisplay,
                        tourId = meta?.tourId,
                        products = meta?.products.orEmpty().mapNotNull { line ->
                            line.productName?.let { ProductLine(it, line.quantity ?: 0) }
                        },
                    )
                )
                "tour_started" -> {
                    val names = meta?.machineNames.orEmpty()
                    ActivityFeedItem.TourStarted(
                        TourActivity(
                            id = row.id,
                            createdAt = row.createdAt,
                            userDisplay = meta?.userDisplay,
                            machineCount = meta?.machineCount ?: names.size,
                            machineNames = names,
                            warehouseName = meta?.warehouseName,
                            tourId = meta?.tourId,
                        )
                    )
                }
                "cash_book_entry_created" -> ActivityFeedItem.CashBookEntry(
                    CashBookActivity(
                        id = row.id,
                        createdAt = row.createdAt,
                        type = CashBookEntryType.fromRaw(meta?.cashType),
                        amount = meta?.amount ?: 0.0,
                        category = meta?.category,
                        note = meta?.note,
                        userDisplay = meta?.userDisplay,
                    )
                )
                else -> null
            }
        }

    /** Merge all sources into one timeline, newest first. */
    fun mergeFeed(
        sales: List<SaleWithMachine>,
        activityRows: List<ActivityLogRow>,
        intakeGroups: List<IntakeGroup>,
    ): List<ActivityFeedItem> =
        (sales.map { ActivityFeedItem.Sale(it) } +
            makeActivityItems(activityRows) +
            intakeGroups.map { ActivityFeedItem.StockIntake(it) })
            .sortedByDescending { it.date }
}
```

`SaleWithMachine` gibt es auf Android noch nicht — lege es in `models/ActivityFeed.kt` mit an: `data class SaleWithMachine(val sale: Sale, val machineName: String?, val productName: String?, val productImagePath: String?)`, mit `val id: String get() = sale.id`.

- [ ] **Step 5: Tests laufen lassen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.data.ActivityFeedBuilderTest"
```

Erwartet: `BUILD SUCCESSFUL`, 17 Tests grün.

- [ ] **Step 6: Gesamtlauf und Commit**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`, 54 Tests (37 bestehende + 17 neue), keine Fehlschläge.

```bash
git add android/app/src/main/java/xyz/vmflow/models/ActivityFeed.kt android/app/src/main/java/xyz/vmflow/data/ActivityFeedBuilder.kt android/app/src/test/java/xyz/vmflow/data/ActivityFeedBuilderTest.kt
git commit -m "feat(android): pure builders for the dashboard activity feed

Ports ActivityFeedBuilder from iOS: 15-minute intake sessions keyed on
the oldest row, per-product aggregation in first-seen order, and one
merged timeline across sales, refills, tours, cash-book entries and
warehouse intakes.

activity_log.metadata is decoded leniently on purpose - it is a contract
shared with the PWA and iOS, and a stricter decoder has broken the feed
before."
```

---

### Task 17: KPI-Eimer und Chart-Vorbelegung

**Vorher lesen:** `ios/VMflow/ViewModels/DashboardViewModel.swift`, Abschnitte „Sales KPIs" (Z. 120–196) und „Daily Chart" (Z. 238–273).

Zwei Fallstricke, die iOS bewusst so löst und die Tests festhalten:

1. **Ein einziger Abruf** deckt alle sechs Zeiträume ab — die Abfrage beginnt beim frühesten Zeitraumbeginn (`min(startOfLastMonth, startOfLastWeek, startOfYesterday)`), die Eimer werden clientseitig gefüllt.
2. Der Chart legt **31** Tageseimer an (`0..<31` ab `heute − 30 Tage`), nicht 30. Der Durchschnitt teilt durch die tatsächliche Eimerzahl, damit er zu den sichtbaren Balken passt.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/DashboardKpis.kt`
- Create: `android/app/src/test/java/xyz/vmflow/data/DashboardKpisTest.kt`

**Interfaces:**
- Produces:
  - `data class SalesKpis(todayRevenue, todayCount, yesterdayRevenue, yesterdayCount, weekRevenue, weekCount, lastWeekRevenue, lastWeekCount, monthRevenue, monthCount, lastMonthRevenue, lastMonthCount)` — alle `Double`/`Int`
  - `DashboardKpis.bucketSales(sales: List<Sale>, now: LocalDateTime, zone: TimeZone): SalesKpis`
  - `DashboardKpis.queryLowerBound(now: LocalDateTime, zone: TimeZone): Instant`
  - `data class DailySales(val date: LocalDate, val revenue: Double, val count: Int)`
  - `DashboardKpis.buildDailyChart(sales: List<Sale>, now: LocalDateTime, zone: TimeZone): List<DailySales>`
  - `DashboardKpis.averageOf(daily: List<DailySales>): Double`, `DashboardKpis.totalOf(daily: List<DailySales>): Double`

Die Funktionen nehmen `now` als Parameter statt die Uhr zu lesen — sonst sind sie nicht testbar.

- [ ] **Step 1: Fehlschlagende Tests schreiben**

Die Tests müssen mindestens abdecken:

- Ein Verkauf von heute zählt in `today`, `week` und `month`, aber nicht in `yesterday`.
- Ein Verkauf von gestern zählt in `yesterday`, nicht in `today`.
- Ein Verkauf aus der Vorwoche zählt in `lastWeek`, nicht in `week`.
- Ein Verkauf aus dem Vormonat zählt in `lastMonth`, nicht in `month`.
- Wochengrenze ist die ISO-Woche (Montag), nicht rollende sieben Tage.
- Monatsgrenze ist der Kalendermonat, nicht rollende 30 Tage.
- `item_price` wird als EUR summiert, nicht durch 100 geteilt — ein Verkauf zu 2.50 ergibt Umsatz 2.50.
- `buildDailyChart` liefert **genau 31** Einträge, aufsteigend nach Datum, auch wenn es null Verkäufe gibt.
- Tage ohne Verkäufe haben Umsatz 0 und Anzahl 0, fehlen aber nicht.
- Ein Verkauf außerhalb des 30-Tage-Fensters landet in keinem Eimer.
- `averageOf` teilt durch die Eimerzahl (31), nicht durch die Zahl der Tage mit Umsatz.
- `queryLowerBound` ist das Minimum der drei Zeitraumgrenzen.

Schreibe für jeden Punkt einen eigenen `@Test` mit sprechendem Namen in Backticks.

- [ ] **Step 2: Fehlschlag bestätigen, implementieren, Tests grün**

Implementiere `DashboardKpis.kt` nach der Swift-Vorlage. Verwende `kotlinx.datetime` (`LocalDate`, `LocalDateTime`, `TimeZone`, `Instant`), das bereits im Projekt ist. ISO-Woche über `DayOfWeek.MONDAY` als Wochenbeginn.

- [ ] **Step 3: Gesamtlauf und Commit**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

```bash
git add android/app/src/main/java/xyz/vmflow/data/DashboardKpis.kt android/app/src/test/java/xyz/vmflow/data/DashboardKpisTest.kt
git commit -m "feat(android): pure KPI bucketing and 30-day chart binning

One fetch fills all six comparison periods client-side, as on iOS. Week
boundaries are ISO weeks and month boundaries calendar months, not
rolling windows - a rolling week would silently disagree with the iOS
figure for the same data.

The chart pre-populates 31 day buckets so days without sales render as
gaps rather than vanishing, and the average divides by the bucket count
so it matches the bars actually drawn."
```

---

### Task 18: `DashboardRepository`

**Vorher lesen:** `ios/VMflow/ViewModels/DashboardViewModel.swift`, Z. 82–117 und 277–445.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/data/DashboardRepository.kt`

**Interfaces:**
- Consumes: `SupabaseService.client`, `DashboardKpis`, `ActivityFeedBuilder`
- Produces:
  - `suspend fun fetchSalesSince(since: Instant): List<Sale>`
  - `suspend fun fetchMachineStockHealth(): MachineStockHealth` mit `data class MachineStockHealth(val total: Int, val online: Int, val criticalMachines: Int, val lowMachines: Int)`
  - `suspend fun fetchRecentSaleItems(windowStart: Instant): Pair<List<SaleWithMachine>, Int>`
  - `suspend fun fetchActivityRows(windowStart: Instant): List<ActivityLogRow>`
  - `suspend fun fetchIntakeRows(windowStart: Instant): List<IntakeTransactionRow>`
  - `suspend fun resolveUserNames(ids: List<String>): Map<String, String>`
  - `suspend fun fetchNewDealsCount(): Int`

Vorgaben, die aus der iOS-Datei kommen und nicht abgewandelt werden dürfen:

- `fetchActivityRows` filtert auf genau diese Aktionen: `stock_refill_tour`, `tour_started`, `cash_book_entry_created`.
- `fetchIntakeRows` filtert auf **beide** Typen: `incoming` **und** `intake`.
- `fetchRecentSaleItems` bevorzugt das per FK verknüpfte `products` des Verkaufs und greift nur für Altverkäufe ohne `product_id` auf `machine_trays` zurück. Der Rückgabewert enthält zusätzlich die **rohe** Zeilenzahl der Verkäufe — die Erschöpfungserkennung in Task 19 hängt daran.
- `resolveUserNames` liest `users` (`id, first_name, last_name, email`), setzt den Anzeigenamen aus Vor- und Nachname zusammen und fällt auf die E-Mail zurück. Fehler degradieren zu einem leeren Ergebnis, sie werfen nicht — ein fehlender Name darf den Feed nicht kippen.
- `fetchNewDealsCount` ruft die RPC `get_new_deals_count` und liefert bei jedem Fehler `0`. Backends ohne diese RPC dürfen das Dashboard nicht zerlegen.
- **Stock-Semantik:** `criticalMachines` zählt Automaten mit mindestens einem leeren Tray; `lowMachines` zählt Automaten mit mindestens einem Tray unter Mindestbestand, **die nicht schon kritisch sind**. Das sind Automaten-Zahlen, keine Tray-Zahlen — die heutige Android-Anzeige zählt anders und wird in Task 20 angepasst.

- [ ] **Step 1: Repository schreiben**, Muster von `MachineRepository.kt` folgen (`private val postgrest get() = SupabaseService.client.postgrest`).

- [ ] **Step 2: Build**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/data/DashboardRepository.kt
git commit -m "feat(android): dashboard queries in one repository

Mirrors the iOS dashboard's fetches, including the two details that are
easy to get wrong: warehouse intakes carry two type strings ('incoming'
from the PWA, 'intake' from iOS) and must both be read, and the recent
sales fetch returns its raw row count because the feed's exhaustion
check compares raw rows, not merged items."
```

---

### Task 19: `DashboardViewModel` neu

**Vorher lesen:** `ios/VMflow/ViewModels/DashboardViewModel.swift` vollständig — besonders `loadMoreRecentActivity` (Z. 452–489).

Die Nachladelogik ist der subtilste Teil der Phase:

- Fenster: `start_of_today − activityDaysBack` Tage. `activityDaysBack` beginnt bei 0 (nur heute) und geht 0 → 6 → 13 → 20 → … (erster Schritt +6, danach +7).
- **Erschöpfung** wird an der **rohen** Zeilenzahl gemessen (Verkäufe + Aktivitätszeilen + Intake-Transaktionen), nicht an der Zahl der zusammengeführten Einträge. Grund: Neue Transaktionen, die in eine bestehende Intake-Gruppe fallen, ändern die zusammengeführte Zahl nicht und würden fälschlich „nichts mehr da" signalisieren.
- Bringt ein Neuladen **mehr** Rohzeilen als zuvor, wird `hasMoreActivity` wieder auf `true` gesetzt.
- Bei echtem Fehler wird das Fenster **zurückgedreht** und `loadMoreFailed` gesetzt (die UI zeigt dann „Erneut versuchen" statt eines Spinners, der nie wieder feuert).
- Bei Abbruch (`CancellationException`) wird das Fenster **nicht** zurückgedreht.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/dashboard/DashboardViewModel.kt`
- Create: `android/app/src/test/java/xyz/vmflow/ui/dashboard/DashboardLoadMoreTest.kt`

**Interfaces:**
- Produces: `DashboardUiState` erweitert um `kpis: SalesKpis`, `dailySales: List<DailySales>`, `activity: List<ActivityFeedItem>`, `activityDaysBack: Int`, `hasMoreActivity: Boolean`, `isLoadingMoreActivity: Boolean`, `loadMoreFailed: Boolean`, `newDealsCount: Int`, `stockCriticalCount: Int`, `stockLowCount: Int`
- Produces: `fun loadMoreActivity()`

Die Fensterfortschreibung wird als **reine Funktion** herausgezogen, damit sie ohne Netzwerk testbar ist:

```kotlin
/** 0 (today only) -> 6 -> 13 -> 20 -> ... The first expansion adds 6 days, later ones 7. */
fun nextDaysBack(current: Int): Int = if (current == 0) 6 else current + 7
```

Tests: `nextDaysBack` für 0, 6, 13, 20; Erschöpfung bei gleicher Rohzeilenzahl; Wiederbelebung bei gestiegener Rohzeilenzahl. Für die Zustandsübergänge reicht eine kleine Testdoppel-Fassade über dem Repository — kein Robolectric.

- [ ] **Step 1: Tests schreiben, Fehlschlag bestätigen, implementieren, grün.**
- [ ] **Step 2: Gesamtlauf und Commit** mit Nachricht, die erklärt, *warum* Rohzeilen und nicht zusammengeführte Einträge verglichen werden.

---

### Task 20: Dashboard-Oberfläche

**Vorher lesen:** `ios/VMflow/Views/Dashboard/DashboardView.swift` vollständig.

Abschnitte in dieser Reihenfolge, wie auf iOS: Deals-Banner (falls `newDealsCount > 0`) · KPI-Karten · Chart · Barkassen-Karte · „Braucht Aufmerksamkeit" · Aktivitäts-Feed.

Android-Konvertierungsregeln:

| iOS | Android |
|---|---|
| Dichte KPI-Karten in `LazyHGrid` | `LazyRow` mit `contentPadding` **und** `Modifier.width(IntrinsicSize)` je Karte, sodass keine Karte am Rand abgeschnitten wird |
| Swift Charts `BarMark` | eigenes Compose-`Canvas`-Balkendiagramm (keine neue Abhängigkeit) |
| `List` mit `.task` am Sentinel | `LazyColumn` mit einem letzten `item { }`, das bei Sichtbarkeit `loadMoreActivity()` auslöst |
| `ProgressView` im Sentinel | `CircularProgressIndicator`, bei `loadMoreFailed` stattdessen ein `TextButton` „Erneut versuchen" |

**Der heutige Fehler, der behoben werden muss:** Die dritte KPI-Karte wird am rechten Rand abgeschnitten. Die Karten dürfen nicht mit fester Breite in einer `LazyRow` sitzen, ohne dass `contentPadding` und Kartenbreite zusammenpassen.

**Zwingend:** Jeder Feed-Typ bekommt eine eigene Zeilendarstellung mit passendem Icon und Farbe (Verkauf, Nachfüllung, Tourstart, Wareneingang, Barkasse), gruppiert nach Tagen mit Datums-Kopfzeilen — wie iOS.

- [ ] Schrittfolge: Chart-Composable → Feed-Zeilen → Zusammenbau im Screen → Build → Screenshots.

---

### Task 21: Texte und Mengenformen

Alle in dieser Phase entstandenen Texte nach `values/strings.xml` und `values-de/strings.xml`. Zusätzlich der **bestehende** Fehler: `"11 tray(s) need refill"` ist hart codiertes Englisch mit `(s)`-Plural und wird zu einem `<plurals>`-Eintrag in beiden Sprachen.

---

### Task 22: Navigationsleiste im Maschinendetail vereinheitlichen

Am Gerät bestätigt: Öffnet man eine Maschine aus dem Maschinen-Tab, bleibt die Navigationsleiste stehen (die Route bleibt `machines`); kommt man vom Dashboard, verschwindet sie (Route `machines/{machineId}`). Dasselbe Ziel, zwei Verhalten.

**Entscheidung:** Die Leiste bleibt künftig **überall sichtbar außer in Login und Registrierung**. Das ist Android-üblich — die Leiste ist ein dauerhafter Anker, kein Detail-Overlay — und beseitigt den Widerspruch in die konsistentere Richtung.

Umsetzung: `TopLevelDestination.fromRoute` bleibt exakt, aber der Scaffold in `MainActivity` entscheidet nicht mehr über `currentTopLevel == null`, sondern über eine eigene Prüfung „ist das eine Auth-Route?". Der markierte Eintrag richtet sich nach dem **Stamm** der aktuellen Route, sodass `machines/{id}` den Eintrag „Automaten" markiert.

Tests für die Stamm-Zuordnung: `machines/abc` → MACHINES, `machines` → MACHINES, `login` → null, `register` → null, `dashboard` → DASHBOARD.

---

## Abnahme der Phase

```bash
cd android && ./gradlew clean assembleDebug testDebugUnitTest
```

Danach auf dem Emulator gegen den lokalen Stack anmelden und prüfen: alle KPI-Karten vollständig sichtbar, Chart mit 31 Tagen, Feed mit gemischten Einträgen und funktionierendem Nachladen, Barkassen-Einträge sichtbar, keine englischen Resttexte, Navigationsleiste im Maschinendetail sichtbar und „Automaten" markiert.

## Nicht in dieser Phase

- Maschinen-Tab, Lager-Schreibpfade, Refill — eigene Phasen
- Extraktion der übrigen bestehenden hart codierten Texte
