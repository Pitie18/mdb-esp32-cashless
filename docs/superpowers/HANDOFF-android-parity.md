# Übergabe: Android-Parität zur iOS-App

**Stand:** 2026-08-13 · Branch `claude/android-app-ios-parity-54dce1` · 15 Commits vor `main`

## Wo anfangen

1. Diese Datei lesen.
2. `.superpowers/sdd/progress.md` lesen — das Ledger mit allen Task-Ergebnissen, Reviews und Befunden.
3. Weitermachen mit **Task 26** aus [`plans/2026-08-12-android-phase-3-machines.md`](plans/2026-08-12-android-phase-3-machines.md).

Vorgehen wie bisher: `superpowers:subagent-driven-development` — ein frischer Umsetzer je Task, unabhängiges Review, und der Orchestrator verifiziert Build, Tests und Bildschirm **selbst** nach.

## Was fertig ist

| Phase | Inhalt | Zustand |
|---|---|---|
| 1 | Toolchain, Navigation, Theming, Serverauswahl mit QR | fertig, in `main` gemerged |
| 2 | Dashboard: Vergleichszeiträume, 30-Tage-Chart, Aktivitäts-Feed mit Nachladen, Barkassen-Karte, Deals-Banner | fertig, am S10 verifiziert |
| 3 | Maschinen-Tab: Analyse-Ansicht (Tasks 23–25 + Layout-Fixes) | **teilweise** — Tasks 26–30 offen |
| 4 | Lager-Schreibpfade | nicht begonnen |
| 5 | Refill-Wizard | nicht begonnen |

Tests: **135**, alle grün. Toolchain: AGP 9.3.1 / Gradle 9.5 / Kotlin 2.4.10 / compileSdk 36 — Debug, Tests und `assembleRelease` (R8) verifiziert.

## Was als Nächstes ansteht

Aus [`plans/2026-08-12-android-phase-3-machines.md`](plans/2026-08-12-android-phase-3-machines.md):

- **Task 26** — Maschinendetail: unterdrückte Verkäufe mit Wiederherstellen-Dialog, Tagesgruppierung der Verkaufsliste, Bestandskorrektur, Tray füllen
- **Task 27** — drei Sheets: Guthaben senden (ruft `send-credit`, braucht Bestätigungsdialog), Maschinen-Einstellungen, Geräte-Gesundheit
- **Task 28** — Maschinenliste: Lagerverfügbarkeit je Automat
- **Task 29** — Nachzügler aus Phase 2: **Produktbilder im Aktivitäts-Feed fehlen** (links jeder Zeile eine leere Spalte), KPI-Karten-Peek sitzt mitten im Text
- **Task 30** — Texte in beide Sprachdateien

Danach Phase 4 (Lager-Schreibpfade — Voraussetzung für Phase 5) und Phase 5 (Refill).

## Testumgebung

**Gerät:** Samsung Galaxy S10, `adb -s RF8M32CG58F`, Android 16 / API 36. Die App ist dort installiert und angemeldet.
**Der Emulator `Pixel_9a` ist unbrauchbar** — er ist in dieser Sitzung fünfmal gestorben (`system_server` ANR, überlebt sogar `-wipe-data`). Nicht wieder darauf setzen, das S10 nutzen.

Backend: lokaler Supabase-Stack auf dem Mac. **Die Edge-Runtime läuft nicht automatisch mit** — `supabase start` startet sie bei bereits laufendem Stack nicht nach. Prüfen und ggf. starten:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:54321/functions/v1/get-my-organization   # 401 = gesund, 503 = fehlt
cd Docker/supabase && nohup supabase functions serve --env-file .env > /tmp/functions-serve.log 2>&1 &
```

Build für das Gerät (LAN-IP des Macs prüfen mit `ipconfig getifaddr en0`):

```bash
cd android && ./gradlew assembleDebug \
  -PSUPABASE_URL=http://10.0.1.146:54321 \
  -PSUPABASE_ANON_KEY=<anon key aus `supabase status`>
adb -s RF8M32CG58F install -r app/build/outputs/apk/debug/app-debug.apk
```

Die committete Vorgabe bleibt dabei unverändert — es wird nichts im Repo angefasst.

## Fallstricke, die diese Sitzung Zeit gekostet haben

- **`git commit -- <pfade>`** committet den Arbeitsbaum und umgeht den Index — es hebt ein vorheriges `git rm --cached` wieder auf. Immer `git add` und dann `git commit` ohne Pathspec.
- **`core-ktx` und `lifecycle` sind absichtlich eine Version unter dem Neuesten** (1.18.0 / 2.10.0). Die neueren verlangen `minCompileSdk 37`. Steht als Kommentar im Versionskatalog. Nicht „hilfreich" hochziehen.
- **Android trimmt führende Leerzeichen in `<string>`-Werten**, wenn sie nicht in Anführungszeichen stehen.
- **Tier-/Statusfarben nicht aus `MaterialTheme.colorScheme` ableiten.** `primary`, `secondary` und `tertiary` liegen in dieser Markenpalette zu dicht beieinander; im Dunkelmodus werden daraus identische Töne. Feste, trennbare Farbtöne mit eigenen Hell/Dunkel-Werten verwenden (siehe `ui/theme/Color.kt`, Abschnitt „Analysis slot tiers").
- **Umsetzer immer die iOS-Quelldatei lesen lassen.** Sie haben dadurch drei echte Fehler in meiner Plan-Prosa gefunden (falsche Wire-Keys `_user_display`/`type`/`description`, ein nicht existierender `deposit`-Enum-Fall). Das ist erwünscht und gehört in deren Report.
- **Die großen UI-Umsetzer-Agenten sind teuer** (bis 450k Token) und haben zweimal am Ende nicht committet. Aufträge klein schneiden; UI-Arbeit notfalls selbst erledigen.
- **Android Studio wendet wiederholt den AGP-Upgrade an.** Inzwischen übernommen (AGP 9.3.1), damit erledigt. `stash@{0}` im Haupt-Repo enthält noch die alte, überholte Variante und kann verworfen werden.

## Offene Entscheidungen

- **Merge:** Die 15 Commits sind in sich abgeschlossen und grün. Sie jetzt nach `main` zu mergen verhindert die Divergenz, die schon einmal aufgetreten ist (`main` lief 29 Commits weiter, während der Branch stand). Alternativ nach Abschluss von Phase 3.
- **Klartext-HTTP im Release-Build.** Aktuell nur im Debug erlaubt. iOS erlaubt es per `NSAllowsArbitraryLoads`. Ohne Entscheidung funktioniert die Serverauswahl in der ausgelieferten App nur mit HTTPS-Servern — selbstgehostete LAN-Instanzen über HTTP sind ausgeschlossen.
