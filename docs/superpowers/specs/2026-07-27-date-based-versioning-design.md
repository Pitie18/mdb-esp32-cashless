# Datums-Versionierung (iOS + PWA) — Design

**Datum:** 2026-07-27
**Status:** Approved

## Ziel

Das Build-Datum soll Teil der Versionsnummer beider Client-Apps werden. Aus
Version `1.0`, gebaut am 27.07., wird die Anzeige `1.0.7.27`.

## Zwei Darstellungen derselben Version

| | Echte Version (Store / semver) | Anzeige in der App |
|---|---|---|
| Format | `MAJOR.MINOR.YYMMDD` | `MAJOR.MINOR.M.D` |
| Beispiel (27.07.2026) | `1.0.260727` | `1.0.7.27` |

- **Echte Version** hält Apples Format (max. 3 durch Punkte getrennte
  Ganzzahlen) und gültiges npm-semver ein. `YYMMDD` läuft über Jahresgrenzen
  hinweg **monoton** hoch, sodass der App Store neue Uploads nie ablehnt.
- **Anzeige-Version** wird aus der echten Version abgeleitet: das `YYMMDD`
  wird in `Monat.Tag` ohne führende Nullen zerlegt (`260727` → `7.27`).

Die Basis `MAJOR.MINOR` (`1.0`) wird pro App an genau **einer** Stelle manuell
gepflegt. Das Datum kommt beim Build automatisch dazu.

## Kernlogik (geteilte Format-Regeln)

Eine reine Funktion kapselt die Format-Regeln, damit iOS und PWA garantiert
identisch rechnen:

```
formatVersion(base: "1.0", date) -> { real: "1.0.260727", display: "1.0.7.27" }
```

Regeln:
- `real` = `base` + `.` + `YYMMDD` (zweistelliges Jahr, zweistelliger Monat,
  zweistelliger Tag; die Konkatenation ergibt eine einzelne Ganzzahl-Komponente).
- `display` = `base` + `.` + `Monat` + `.` + `Tag`, Monat und Tag **ohne**
  führende Null (`07` → `7`, `05` → `5`).
- Umkehrung (zum Anzeigen aus einer bereits gestempelten echten Version): die
  letzte Komponente `YYMMDD` in Jahr/Monat/Tag zerlegen, `base` = die ersten
  zwei Komponenten.

Implementiert einmal in TypeScript (mit Vitest-Test) und einmal als
Swift-Pendant. Beide teilen dieselben Regeln, aber nicht denselben Code
(unterschiedliche Sprachen / Buildsysteme).

## iOS

Dateien: `ios/project.yml`, `ios/VMflow/Resources/Info.plist`,
`ios/VMflow/Views/Settings/SettingsView.swift`, neuer Swift-Version-Helper.

- `MARKETING_VERSION = "1.0"` in `project.yml` bleibt die **einzige** Quelle
  für die Basis.
- Der bestehende preBuildScript (setzt bereits `CFBundleVersion` aus dem
  git-Commit-Count) wird erweitert: er setzt zusätzlich
  `CFBundleShortVersionString = ${MARKETING_VERSION}.$(date +%y%m%d)`
  via PlistBuddy → z.B. `1.0.260727`.
  - Gilt für **beide** Targets, die schon einen preBuildScript haben (VMflow +
    NotificationService), damit App und Extension dieselbe Kurzversion tragen.
- `CFBundleVersion` bleibt der git-Commit-Count (Build-Nummer; muss für mehrere
  Uploads am selben Tag streng steigen — erfüllt).
- **Neu:** eine Zeile in `SettingsView.swift`, die die Anzeige-Version
  `1.0.7.27` zeigt (bislang zeigt iOS gar keine Version). Der Wert wird aus
  `CFBundleShortVersionString` (`1.0.260727`) via Swift-Helper in die
  Anzeige-Form gebracht.
- **pbxproj-Hinweis:** `ios/VMflow.xcodeproj` hat keine synchronisierten
  Gruppen. Ein neuer `.swift` müsste an 4 Stellen in `project.pbxproj`
  registriert werden **oder** via `xcodegen generate` neu erzeugt werden. Um
  das zu vermeiden, wird der Swift-Version-Helper bevorzugt in eine bereits
  registrierte Datei gelegt (z.B. als kleine Utility neben `SettingsView` oder
  in einer bestehenden Helpers-Datei). Endgültige Platzierung im Plan.

## PWA

Dateien: `management-frontend/nuxt.config.ts`,
`management-frontend/app/components/AppSidebar.vue`, neuer TS-Helper + Test.

- `package.json` `version` bleibt gültiges semver (`1.0.0`). Die Basis wird aus
  den ersten zwei Komponenten abgeleitet (`1.0`).
- `nuxt.config.ts` berechnet beim Build aus Basis + `BUILD_DATE`
  (ISO-Timestamp aus CI; lokal Fallback auf „jetzt"):
  - `appVersion` (echt) = `1.0.260727`
  - `appVersionDisplay` = `1.0.7.27`
  Beide werden über `runtimeConfig.public` bereitgestellt.
- `AppSidebar.vue` zeigt `v1.0.7.27` (die bestehende Datum/Uhrzeit-Zeile
  dahinter bleibt unverändert, sie liefert zusätzlich Jahr + Uhrzeit).

## Bewusst weggelassen (YAGNI)

- Kein Jahr in der Anzeige (steckt für die Sortierung in `YYMMDD`).
- Keine automatische Major/Minor-Erhöhung.
- Keine Build-Uhrzeit in der iOS-Anzeige.

## Test / Verifikation

- **TS-Helper:** Vitest-Unit-Test (`management-frontend`, bestehende
  Vitest-Infrastruktur) für `formatVersion` inkl. führende-Null-Fälle
  (Jan/einstellige Tage) und Umkehrfunktion.
- **Swift-Helper:** kein Test-Target vorhanden → reine Logik via
  Wegwerf-`swift x.swift` verifizieren.
- **iOS-Build:**
  `cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO`
  und prüfen, dass `CFBundleShortVersionString` im gebauten Bundle die
  Datums-Form trägt.
- **PWA-Build:** `npm run build` mit gesetztem `BUILD_DATE`; prüfen, dass die
  Sidebar `v1.0.7.27` rendert.

## Rückwärtskompatibilität

Rein clientseitige Anzeige- und Build-Metadaten. Keine DB-Migration, keine
MQTT-/Edge-Function-/API-Änderung, kein Firmware-Bezug. Die echte Version bleibt
in Apple- und semver-konformem Format, sodass Store-Uploads und npm nicht
brechen.
