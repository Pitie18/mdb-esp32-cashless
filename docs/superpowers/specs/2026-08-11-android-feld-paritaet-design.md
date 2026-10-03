# Android-App: Feld-Parität zur nativen iOS-App

**Datum:** 2026-08-11
**Status:** Design approved (user), pending spec review
**Plattform:** Android (`android/`). Backend unverändert. iOS und PWA unverändert.

## Problem

Es gibt bereits eine native Android-App, aber sie ist stehengeblieben. Ihr
letzter Feature-Commit ist `64b0b1c` — derselbe Commit, der auch die iOS-App
eingeführt hat. Seitdem kam nur noch `56e9f74` (Branding) dazu. Die iOS-App ist
in derselben Zeit auf zwölf Module gewachsen.

Der Abstand in Zahlen:

| | Dateien | LOC | Stand |
|---|---:|---:|---|
| [`ios/VMflow`](../../../ios/VMflow) | 96 | 27.001 | gepflegt |
| [`android/`](../../../android) | 35 | 4.974 | zwei Commits alt |
| [`management-frontend`](../../../management-frontend) | 39 Seiten | — | Obermenge von iOS |

Android hat heute: Auth, Dashboard, Maschinen, Trays, einen Refill-Rumpf.
Es fehlen gegenüber iOS: Lager, Kassenbuch, Deals, Inbox, Produkte,
Einstellungen, Maschinen-Analyse, Einkaufspreise, Lieferanten, Push, Realtime,
Multi-Server. Grob 80 % des iOS-Funktionsumfangs.

Die naive Lesart wäre, 22.000 Zeilen Swift nach Kotlin zu übersetzen. Die
Messung sagt etwas anderes.

## Warum das keine Neuentwicklung ist

Die iOS-App ist keine 27.000 Zeilen Fachlogik. Sie ist eine dünne Schicht über
der Datenbank:

| Schicht | LOC | Anteil | Portierbarkeit |
|---|---:|---:|---|
| Views (SwiftUI) | 16.571 | 61 % | muss neu — SwiftUI→Compose ist Handarbeit |
| ViewModels | 6.593 | 24 % | ~1:1, reine Supabase-Queries + State |
| Models | 1.782 | 7 % | ~1:1, generierbar |
| Services | 1.386 | 5 % | ~1:1 (Supabase Kotlin SDK kann dasselbe) |
| Navigation/Utilities | 384 | 1 % | trivial |

Die eigentliche Fachlogik — Steuersätze, FIFO-Abbuchung, KPIs, Velocity —
liegt nicht im Client. iOS ruft dafür elf Postgres-RPCs auf
(`add_purchase_price`, `get_machine_product_kpis`, `get_product_detail_kpis`,
`get_product_purchase_summary`, `get_product_sales_velocity`,
`get_theoretical_cash`, `get_new_deal_keys`, `get_new_deals_count`,
`resolve_product_tax_rate`, `restore_suppressed_sale`, `update_purchase_price`)
und eine Edge Function (`get-my-organization`), sonst spricht es direkt 29
Tabellen über PostgREST an.

**Diese Logik teilt sich Android automatisch.** Der Backend-Anteil dieses
Vorhabens ist null Zeilen.

## Entscheidung: Feld-Parität statt Voll-Parität

Voll-Parität würde rund 20–22k LOC Kotlin kosten und — der wichtigere Punkt —
ab dann jedes neue Feature dreifach fällig machen: PWA, iOS, Android.

Stattdessen wird nativ nur gebaut, was am Automaten und im Lager wirklich am
Handy gebraucht wird. Der Büro-Teil bleibt auf der PWA, die auf Android ohnehin
installierbar ist (Manifest + eigener Service Worker unter
[`management-frontend/public`](../../../management-frontend/public)) und heute
schon mehr kann als die iOS-App.

**Nativ (Feld):** Dashboard · Maschinen (Liste, Detail, Trays) · Refill-Wizard ·
Lager inkl. Barcode · Push · Login und Server-Auswahl

**PWA (Büro):** Produkte-CRUD · Deals · Kassenbuch · Einkaufspreise und
Lieferanten · Einstellungen · Berichte · Firmware · Geräte · Mitglieder

Die App bekommt für die Büro-Module sichtbare Menüeinträge, die einen Custom Tab
auf die PWA öffnen — kein Sackgassen-Menü, das Einträge zeigt und nichts tut.

Kotlin Multiplatform wurde erwogen und verworfen: Es würde die
funktionierende iOS-App zum Umbau zwingen und den Xcode-Build von Gradle
abhängig machen, und rechnet sich erst über viele zukünftige Features.

## Architektur

Der bestehende Stack bleibt unverändert. Kotlin + Compose + Material 3,
[Supabase Kotlin SDK](../../../android/gradle/libs.versions.toml) 3.1.4,
MVVM mit `data/*Repository` + `ui/*/ViewModel`, manuelles DI über Singletons
(`SupabaseService`). Kein DI-Framework, keine Umstellung der Struktur — es wird
in das hineingebaut, was da ist.

Als Muster gilt, was [`TrayRepository.kt`](../../../android/app/src/main/java/xyz/vmflow/data/TrayRepository.kt)
und [`TrayEditDialog.kt`](../../../android/app/src/main/java/xyz/vmflow/ui/trays/TrayEditDialog.kt)
bereits vorgeben — der sauberste Teil des vorhandenen Rumpfs.

Die jeweilige iOS-ViewModel-Datei dient je Modul als ausführbare
Spezifikation für ihr Kotlin-Pendant. Nicht die iOS-View: die wird nach
Android-Konventionen neu gedacht, siehe nächster Abschnitt.

## UI/UX: Android-Konventionen, nicht übersetztes iOS

Die App soll sich wie eine Android-App anfühlen, nicht wie eine portierte
iOS-App. Konkret heißt das:

### Navigation

- `NavigationSuiteScaffold` als Rahmen: `NavigationBar` am Telefon,
  `NavigationRail` auf Foldables und Tablets — statt der iOS-`TabView`/
  `Sidebar`-Aufteilung aus
  [`AdaptiveRootView.swift`](../../../ios/VMflow/Navigation/AdaptiveRootView.swift).
- Vier Top-Level-Ziele: Dashboard, Maschinen, Refill, Lager. Nicht mehr als
  fünf, wie von Material vorgegeben.
- **Predictive Back** aktiviert (`android:enableOnBackInvokedCallback="true"`
  im Manifest). Systemgeste ist der primäre Rückweg; keine iOS-artigen
  „‹ Zurück"-Textlabels in der TopAppBar, nur das Standard-Navigationsicon.
- Bildschirmwechsel mit Material-Motion (Shared-Axis im NavHost,
  Container-Transform von Maschinenkarte zu Detail).

### Komponenten-Abbildung

| iOS | Android |
|---|---|
| `.sheet` | `ModalBottomSheet` |
| Segmented Control | `PrimaryTabRow` bzw. `SingleChoiceSegmentedButtonRow` |
| Swipe Actions | `SwipeToDismissBox` |
| `Alert` | `AlertDialog` |
| Toast/Banner | `Snackbar` über `SnackbarHostState`, mit Aktion statt nur Text |
| `List` mit Disclosure | `ListItem` mit `trailingContent` |
| `.refreshable` | `PullToRefreshBox` |
| `.searchable` | `SearchBar` |
| Primäraktion in der Toolbar | `FloatingActionButton` bzw. Extended FAB |

Der letzte Punkt ist der wichtigste Unterschied: iOS legt die Primäraktion
oben rechts in die Toolbar, Android unten rechts in den FAB. Das wird nicht
gespiegelt, sondern konvertiert.

### Adaptive Layouts

`WindowSizeClass` steuert die Darstellung. Die iOS-`MachinesSplitView` wird auf
Android zu einem `ListDetailPaneScaffold` ab `WindowWidthSizeClass.Expanded`.
Am Telefon bleibt es eine Liste mit Detail-Navigation.

### Theming

Dynamic Color (Material You) bleibt eingeschaltet, wie in
[`Theme.kt`](../../../android/app/src/main/java/xyz/vmflow/ui/theme/Theme.kt)
bereits angelegt, mit dem Markenschema als Fallback unter Android 12. Das ist
eine Android-Stärke und wird nicht zugunsten von iOS-Markenfarben abgeschaltet.
Wer die Markenfarben erzwingen will, kann das in den Einstellungen tun.

Edge-to-edge wird über `enableEdgeToEdge()` in `MainActivity` gesetzt und die
Insets über `Scaffold` durchgereicht. Ab `targetSdk 35` ist das ohnehin
erzwungen, und die App steht heute auf `targetSdk 36` — der aktuelle Zustand
ist also vermutlich schon fehlerhaft und wird hier mitkorrigiert.

Statt eines selbstgebauten Compose-Splashscreens wird
`androidx.core:core-splashscreen` verwendet.

### Feld-Ergonomie

Hier treffen sich Android-Richtlinien und der konkrete Einsatz: Ein Fahrer
bedient das Gerät einhändig, im Stehen, oft mit Handschuhen.

- Primäraktionen im unteren Bildschirmdrittel, in FAB-Reichweite.
- Touch-Targets mindestens 48 dp, Trayzeilen im Refill deutlich größer.
- Während einer laufenden Refill-Tour bleibt der Bildschirm an
  (`keepScreenOn`).
- Haptisches Feedback bei erfolgreichem Scan und bestätigtem Tray über
  `HapticFeedbackConstants`.

### Barrierefreiheit

- `contentDescription` an jedem Icon-Button; die Bestandsbalken
  ([`StockBar.kt`](../../../android/app/src/main/java/xyz/vmflow/ui/components/StockBar.kt))
  bekommen eine `semantics`-Beschreibung statt nur Farbe.
- Schriftskalierung bis 200 % ohne abgeschnittene Inhalte: keine festen
  Zeilenhöhen.
- `testTag` an den Elementen, die die UI-Tests ansteuern.

### Benachrichtigungen

Getrennte Notification Channels je Kategorie (Lagerbestand, Touren, Geräte),
damit einzeln stummgeschaltet werden kann — Android-Konvention, die iOS so
nicht kennt. `POST_NOTIFICATIONS` wird ab API 33 zur Laufzeit angefragt, und
zwar im Kontext (beim ersten Einrichten von Alarmen), nicht beim App-Start.

### Sprache

Statt eines app-internen Sprachwählers wie auf iOS wird `android:localeConfig`
gesetzt, sodass de/en über die Systemeinstellungen pro App umgeschaltet werden
kann (Android 13+). Texte über `stringResource`, Mengen über `<plurals>`,
Währung über `NumberFormat.getCurrencyInstance`, Datum locale-abhängig.

## Arbeitspakete

| Paket | Android heute | Ziel | grob LOC |
|---|---|---|---:|
| Lager | 54 LOC, nur lesend | Wareneingang, FIFO-Chargen, Buchungen, Mindestbestand, Positionen | 2.500 |
| Refill | 53 LOC Repo, 3 Schritte | iOS-Stand: Review-Schritt, Layout-Grid, Ersatzprodukt-Picker | 2.000 |
| Maschinen-Detail | Liste + Verkäufe | Analyse-Tab, Device-Health, Maschinen-Einstellungen | 800 |
| Produkte (nur Feld) | – | Lookup und Picker für Barcode und Tray-Zuweisung, kein CRUD | 500 |
| Barcode | – | CameraX + ML Kit, Hardware-Scanner als Tastatur-Wedge | 400 |
| Push | – | Firebase-Client, Token-Registrierung, Channels | 300 |
| Server/Login | fest verdrahtet | Multi-Server + QR-Scan analog iOS `ServerStore` | 400 |
| UI/UX-Umbau | — | NavigationSuite, Edge-to-edge, Predictive Back, Adaptive | 600 |
| i18n de/en | 6 Zeilen `strings.xml` | vollständige Extraktion + deutsche Übersetzung | quer durch |

Zusammen rund **8.000 Zeilen Kotlin**, plus die Extraktion der heute hart
codierten englischen Texte.

## Push kostet fast nichts mehr

Die Backend-Seite ist bereits vollständig:

- [`register-push`](../../../Docker/supabase/functions/register-push/index.ts)
  akzeptiert `fcm_token` und `platform: 'ios' | 'android'` und schreibt sie in
  `push_subscriptions`.
- [`web-push.ts`](../../../Docker/supabase/functions/_shared/web-push.ts)
  filtert `androidSubs` heraus und versendet über `sendFcmNotification`
  (FCM HTTP v1).
- `FCM_SERVICE_ACCOUNT_JSON` steht in
  [`Docker/.env.example`](../../../Docker/.env.example) und in
  [`config.toml`](../../../Docker/supabase/config.toml).

Zu tun bleibt nur der Client: Firebase-SDK einbinden, `google-services.json`
hinterlegen, Token an `register-push` schicken, Channels anlegen. Migrationen
sind keine nötig.

## Barcode

CameraX für die Vorschau, ML Kit Barcode Scanning für die Erkennung. Die
Kameraberechtigung wird im Moment des Scannens mit Begründung angefragt, nicht
beim Start.

Hardware-Scanner mit Pistolengriff melden sich als Tastatur an. Sie werden
unterstützt, indem der Scan-Screen ein unsichtbares, fokussiertes Eingabefeld
hält, das Zeilenende-terminierte Eingaben entgegennimmt — parallel zur Kamera,
ohne dass der Nutzer eine Betriebsart wählen muss.

Als Referenz für die fachliche Seite dienen
[`BarcodeScanner.vue`](../../../management-frontend/app/components/BarcodeScanner.vue)
und die Barcode-Pfade in
[`WarehouseViewModel.swift`](../../../ios/VMflow/ViewModels/WarehouseViewModel.swift).

## Ein Korrektheitsrisiko, das nicht wiederholt wird

Der iOS-Refill hat das Lager über 53 Touren um rund 334 Einheiten zu viel
belastet, weil für Ware abgebucht wurde, die der Fahrer nie eingefüllt hat.
Das ist auf iOS behoben.

Androids `applyRefill` in
[`RefillRepository.kt`](../../../android/app/src/main/java/xyz/vmflow/data/RefillRepository.kt)
stammt aus der Zeit davor und hat mit 53 Zeilen ohnehin nicht die Tiefe der
iOS-Implementierung.

**Die Portierung übernimmt ausdrücklich die korrigierte iOS-Logik, nicht die
eigene alte.** Dazu kommt ein Unit-Test, der genau diesen Fall abdeckt:
reduzierte und übersprungene Füllungen dürfen den Lagerbestand nicht belasten.

## Schema-Drift zwischen drei Clients

`activity_log.metadata` ist bereits ein typisierter Vertrag zwischen PWA und
iOS. Eine Änderung eines Feldes von Objekt auf Array hat den iOS-Feed schon
einmal zerlegt. Mit einem dritten Client wächst das Risiko.

Die Android-Seite dekodiert `metadata` deshalb **tolerant**: unbekannte
Schlüssel werden ignoriert (`ignoreUnknownKeys = true`), fehlende Felder sind
optional mit Default. Kein striktes Spiegeln des iOS-Structs.

Analog gilt für die Activity-Feed-Darstellung: iOS führt eine Whitelist
bekannter Actions und ignoriert den Rest. Android macht dasselbe und stellt
Unbekanntes generisch dar, statt zu scheitern.

## Toolchain-Lücken

Der aktuelle Katalog
([`libs.versions.toml`](../../../android/gradle/libs.versions.toml)) steht auf
Compose BOM 2025.03.00 bei `compileSdk`/`targetSdk` 36. Für die oben
beschriebene UI werden ergänzt:

- `androidx.compose.material3:material3-adaptive-navigation-suite` und
  `androidx.compose.material3.adaptive:adaptive-layout` — NavigationSuite und
  ListDetailPaneScaffold
- `androidx.core:core-splashscreen`
- `androidx.camera:camera-camera2` / `camera-lifecycle` / `camera-view`
- `com.google.mlkit:barcode-scanning`
- Firebase BOM + `firebase-messaging`, Google-Services-Plugin
- `androidx.datastore:datastore-preferences` — Serverliste und
  UI-Einstellungen
- `org.jetbrains.kotlinx:kotlinx-coroutines-test` und `app.cash.turbine` für
  ViewModel-Tests

Der Compose BOM wird dabei auf einen zu `compileSdk 36` passenden Stand
gehoben. **Risiko:** Der Sprung von 2025.03 ist groß und kann bestehende
Screens brechen. Er passiert deshalb als eigener, isolierter erster Schritt mit
grünem Build als Abnahmekriterium, nicht nebenbei in einem Feature-Paket.

## Versionierung

Die App steht auf `versionName "v2.0.0"`, `versionCode 7` und folgt damit nicht
dem datumsbasierten Schema, das PWA und iOS seit
[`2026-07-27-date-based-versioning-design.md`](2026-07-27-date-based-versioning-design.md)
verwenden (real `MAJOR.MINOR.YYMMDD`, Anzeige `MAJOR.MINOR.M.D`). Android
schließt sich an: `versionName` wird beim Build aus dem Datum gestempelt,
`versionCode` bleibt monoton steigend.

## Tests

Es gibt heute keinen einzigen Test in `android/`. Das wird nicht flächendeckend
nachgeholt, aber die Stellen, an denen Fehler Geld kosten, bekommen welche:

- **Refill-Abbuchung** — reduzierte und übersprungene Füllungen belasten das
  Lager nicht (siehe oben). Unit-Test auf dem ViewModel.
- **FIFO-Abbuchung im Lager** — Chargen werden in Ablaufreihenfolge geleert.
- **`activity_log.metadata`-Dekodierung** — unbekannte Schlüssel und fehlende
  Felder brechen den Feed nicht.
- **Barcode-Auflösung** — bekannter Code trifft das richtige Produkt,
  unbekannter Code führt zum Anlege-Pfad statt zu einem Fehler.

Dazu ein Compose-UI-Test je Modul, der den Hauptpfad durchklickt.

## Reihenfolge

1. Toolchain und UI-Fundament — BOM-Bump, NavigationSuite, Edge-to-edge,
   Predictive Back, Splashscreen. Grüner Build, bestehende Screens intakt.
2. Lager inklusive Barcode. Größte Lücke und der genannte Nativ-Bedarf.
3. Refill auf iOS-Stand, inklusive des Abbuchungs-Fixes.
4. Push über FCM.
5. i18n de/en.
6. Maschinen-Detail (Analyse, Device-Health, Einstellungen).
7. Multi-Server und QR-Login.
8. PWA-Brücke für die Büro-Module.

Jedes Paket ist einzeln lauffähig und einzeln abnehmbar.

## Offene Punkte

- **Getrennte PWA-Anmeldung.** Der Custom Tab auf die Büro-Module teilt keine
  Session mit der App; der Nutzer meldet sich dort einmal separat an. Reibung,
  für die ich keine Lösung sehe, die den Aufwand wert wäre. Wird bewusst
  hingenommen.
- **Analyse-Tab.** Ob `MachineAnalysisView` wirklich ins Feld gehört oder
  Büro ist, ist nicht entschieden. Steht deshalb weit hinten in der
  Reihenfolge und kann ohne Folgen gestrichen werden.

## Nicht in v1

- Offline-Betrieb mit Schreib-Queue. Wurde explizit nicht gefordert; der
  Aufwand (lokale Datenbank, Konfliktauflösung, Sync) wäre größer als alle
  übrigen Pakete zusammen.
- Voll-Parität zu iOS. Bewusst vertagt, siehe Entscheidung oben.
- Kotlin Multiplatform.
- Wear OS, Widgets, Play-Store-Veröffentlichung.
