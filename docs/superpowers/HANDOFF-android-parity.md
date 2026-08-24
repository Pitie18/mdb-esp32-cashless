# Übergabe: Android-Parität zur iOS-App

**Stand:** 2026-08-24 · alles bis einschließlich Phase 5a ist in `main` gemerged (lokal, noch nicht gepusht) · 300 Tests grün

## Wo anfangen

1. Diese Datei lesen.
2. `.superpowers/sdd/progress.md` lesen — das Ledger mit allen Task-Ergebnissen, Reviews und Befunden. Phase 3 ist darin vollständig protokolliert (Tasks 23–30), inklusive der vier offenen Nachfolgepunkte, die absichtlich nicht in dieser Phase erledigt wurden (siehe unten).
3. Phase 5b (Review-Schritt, Ersatzprodukt-Picker, Maschinen-Layout-Grid) braucht noch einen Plan. Phase 5a ist fertig und gemerged; ihr Plan [`plans/2026-08-24-android-phase-5-refill.md`](plans/2026-08-24-android-phase-5-refill.md) enthält am Ende zwei Abschnitte, die man vor 5b liest: die bekannten Abweichungen von iOS und die neun Restrisiken.

Vorgehen wie bisher: `superpowers:subagent-driven-development` — ein frischer Umsetzer je Task, unabhängiges Review, und der Orchestrator verifiziert Build, Tests und Bildschirm **selbst** nach. Bei kleinen, rein visuellen Fixes (Layout-Feintuning, das iterative Bildschirmzugriff braucht) hat es sich diese Sitzung bewährt, dass der Orchestrator sie direkt selbst macht statt einen Umsetzer zu beauftragen — ein Subagent kann den Bildschirm nicht sehen.

## Was fertig ist

| Phase | Inhalt | Zustand |
|---|---|---|
| 1 | Toolchain, Navigation, Theming, Serverauswahl mit QR | fertig, in `main` gemerged |
| 2 | Dashboard: Vergleichszeiträume, 30-Tage-Chart, Aktivitäts-Feed mit Nachladen, Barkassen-Karte, Deals-Banner | fertig, am S10 verifiziert |
| 3 | Maschinen-Tab: Analyse-Ansicht, unterdrückte Verkäufe, 3 Sheets (Guthaben/Einstellungen/Gerätegesundheit), Lagerverfügbarkeit, Lokalisierungs-Sweep (Tasks 23–30) | **fertig**, am S10 verifiziert |
| 4 | Lager: Bestand, Wareneingang (Barcode), FIFO-Chargen-Drilldown + Korrektur, Lokalisierung (Tasks 1–12) | **fertig**, am S10 verifiziert, in `main` gemerged |
| 5a | Refill-Tour auf iOS-Stand: lagerbewusstes Packen, FIFO-Abbuchung nur für gepackte Ware, atomare `refill_machine_trays`-Buchung mit Retry, `activity_log`, Tour fortsetzen, Lokalisierung | **fertig**, am S10 gegen den Testserver verifiziert, in `main` gemerged |
| 5b | Review-Schritt, Ersatzprodukt-Picker, Maschinen-Layout-Grid | kein Plan — hängt an 5a |

Tests: **300**, alle grün. Toolchain: AGP 9.3.1 / Gradle 9.5 / Kotlin 2.4.10 / compileSdk 36 — Debug, Tests und `assembleRelease` (R8) verifiziert.

## Offene Nachfolgepunkte aus Phase 3 (bewusst nicht erledigt)

Details und Fundstellen im Ledger (`.superpowers/sdd/progress.md`, Abschnitt "PHASE 3 COMPLETE"):

1. ~~**Dashboard-Absturzrisiko:** `MissingFieldException` im Stock-Health-Fetch riss über den gemeinsamen `coroutineScope` alle sieben Dashboard-Coroutinen mit.~~ **Behoben und gemerged (`ec21f56`)**, während Phase 4 lief; in Phase 4 mehrfach beiläufig auf dem S10 bestätigt. Der zweite Teil des damaligen Vorschlags — jeden Dashboard-Sub-Fetch einzeln zu kapseln, damit ein künftiger Fetch-Fehler nur sein eigenes Widget leerräumt statt den ganzen Screen — ist **nicht** umgesetzt und bleibt offen.
2. Device-Health-Sheet: Bei den "Automatisch entfernten Duplikaten" fehlt (anders als bei iOS) eine Wiederherstellen-Aktion — funktional redundant, da der Sales-Tab das bereits kann. Bewusst nicht nachgezogen.
3. Device-Health-Sheet: Fehler beim Laden von Neustart-/MDB-Log-Historie werden intern gesetzt, aber nie im UI angezeigt — ein Fehlschlag sieht aktuell wie "keine Daten" aus.

## Testumgebung

**Gerät:** Samsung Galaxy S10, `adb -s RF8M32CG58F`, Android 16 / API 36. Die App ist dort installiert und angemeldet.
**Der Emulator `Pixel_9a` ist unbrauchbar** — er ist in einer früheren Sitzung fünfmal gestorben (`system_server` ANR, überlebt sogar `-wipe-data`). Nicht wieder darauf setzen, das S10 nutzen.

**Bildschirm-Koordinaten:** `adb shell input tap/swipe` erwartet Gerätepixel (bei diesem S10 1440×3040), nicht die skalierten Koordinaten aus einem verkleinert angezeigten Screenshot. Bei Unsicherheit `adb shell uiautomator dump` + die `bounds="[x1,y1][x2,y2]"` des Zielelements nutzen statt zu schätzen — mehrere Fehltaps diese Sitzung kamen von der falschen Skalierung. Ein Swipe, der nahe am rechten Bildschirmrand startet, kann außerdem als System-Zurück-Geste interpretiert werden und die App verlassen.

Backend: lokaler Supabase-Stack auf dem Mac. **Die Edge-Runtime läuft nicht automatisch mit** — `supabase start` startet sie bei bereits laufendem Stack nicht nach. Prüfen und ggf. starten:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:54321/functions/v1/get-my-organization   # 401 = gesund, 503 = fehlt
cd Docker/supabase && nohup supabase functions serve --env-file .env > /tmp/functions-serve.log 2>&1 &
```

Falls Docker zu Sitzungsbeginn nicht läuft: `open -a Docker` und warten, bis `docker info` erfolgreich ist, dann die Container hochfahren lassen (sie starten meist automatisch mit). **Falls die Sitzung Docker selbst neu gestartet hat**, mit Vorsicht bei manuellen Test-Inserts direkt in die DB sein — diese Sitzung gab es einen reproduzierbaren, aber letztlich ungeklärten Fall, in dem die App über den authentifizierten Client Zeilen, die per `docker exec psql INSERT` eingefügt wurden, konsequent nicht sah, während `psql`, ein Service-Role-`curl` und ein `curl` mit manuell signiertem User-JWT sie alle korrekt sahen (RLS, Query und PostgREST-Cache wurden alle ausgeschlossen). Am ehesten ein Docker-Netzwerk-Artefakt vom Neustart, kein Code-Fehler — aber es bedeutet, dass frisch per Raw-SQL eingefügte Testdaten auf dieser Sitzung nicht zuverlässig über die App sichtbar waren.

Build für das Gerät (LAN-IP des Macs prüfen mit `ipconfig getifaddr en0`):

```bash
cd android && ./gradlew assembleDebug \
  -PSUPABASE_URL=http://10.0.1.146:54321 \
  -PSUPABASE_ANON_KEY=<anon key aus `supabase status`>
adb -s RF8M32CG58F install -r app/build/outputs/apk/debug/app-debug.apk
```

Die committete Vorgabe bleibt dabei unverändert — es wird nichts im Repo angefasst.

## Fallstricke, die bisherige Sitzungen Zeit gekostet haben

- **`git commit -- <pfade>`** committet den Arbeitsbaum und umgeht den Index — es hebt ein vorheriges `git rm --cached` wieder auf. Immer `git add` und dann `git commit` ohne Pathspec.
- **`core-ktx` und `lifecycle` sind absichtlich eine Version unter dem Neuesten** (1.18.0 / 2.10.0). Die neueren verlangen `minCompileSdk 37`. Steht als Kommentar im Versionskatalog. Nicht „hilfreich" hochziehen.
- **Android trimmt führende Leerzeichen in `<string>`-Werten**, wenn sie nicht in Anführungszeichen stehen.
- **Tier-/Statusfarben nicht aus `MaterialTheme.colorScheme` ableiten.** `primary`, `secondary` und `tertiary` liegen in dieser Markenpalette zu dicht beieinander; im Dunkelmodus werden daraus identische Töne. Feste, trennbare Farbtöne mit eigenen Hell/Dunkel-Werten verwenden (siehe `ui/theme/Color.kt`, Abschnitt „Analysis slot tiers").
- **Umsetzer immer die iOS-Quelldatei lesen lassen.** Sie haben dadurch mehrere echte Fehler in Plan-Prosa gefunden (falsche Wire-Keys, ein nicht existierender Enum-Fall, eine fälschlich als "read-only" beschriebene iOS-Funktion, die eigentlich eine Wiederherstellen-Aktion hatte). Das ist erwünscht und gehört in deren Report.
- **Die großen UI-Umsetzer-Agenten sind teuer** (bis 450k Token) und haben zweimal am Ende nicht committet. Aufträge klein schneiden; UI-Arbeit notfalls selbst erledigen. Wenn ein Plan-Task mehrere unabhängige, aber im selben Screen/Toolbar überlappende Features bündelt (z. B. drei Sheets auf demselben Detail-Screen), lohnt es sich, ihn in mehrere sequenzielle Umsetzer-Aufträge zu splitten statt einen riesigen zu beauftragen — sie müssen ohnehin nacheinander laufen (dieselbe Datei), aber jeder bleibt dadurch klein und beherrschbar.
- **Vor größeren Kartenbreiten-/Padding-Layout-Tweaks:** Compose-Zeilen ohne führenden Icon-Offset (z. B. reine Zahlen-/Betrags-Zeilen direkt unter einer Icon+Titel-Zeile) haben keinen "sicheren" Peek-Bereich — jeder sichtbare Ausschnitt über die Card-eigene `padding()` hinaus schneidet zwangsläufig in eine Ziffer. Nur direkt am Gerät iterativ verifizierbar, nicht rein rechnerisch.
- **Android Studio wendet wiederholt den AGP-Upgrade an.** Inzwischen übernommen (AGP 9.3.1), damit erledigt.

## Offene Entscheidungen

- **Merge:** Jetzt, wo Phase 3 komplett und grün ist (214 Tests, alle On-Device-Pfade verifiziert), ist dies ein guter Zeitpunkt zum Mergen nach `main`, bevor die Divergenz zu groß wird (ist schon einmal passiert: `main` lief 29 Commits weiter, während der Branch stand).
- **Klartext-HTTP im Release-Build.** Aktuell nur im Debug erlaubt. iOS erlaubt es per `NSAllowsArbitraryLoads`. Ohne Entscheidung funktioniert die Serverauswahl in der ausgelieferten App nur mit HTTPS-Servern — selbstgehostete LAN-Instanzen über HTTP sind ausgeschlossen.
