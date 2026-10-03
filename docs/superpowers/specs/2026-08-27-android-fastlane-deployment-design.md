# Android-Release-Pipeline mit Fastlane — Design

**Datum:** 2026-08-27
**Ziel:** Die Android-App bekommt dieselbe Ein-Klick-Release-Pipeline wie iOS —
GitHub-Actions-Dispatch, Fastlane, automatisch generierte Release-Notes,
Tagging und Changelog-Rückcommit. Kein Play-Console-Besuch für ein Release.

Referenz ist die bestehende iOS-Pipeline: `ios/fastlane/Fastfile`,
`.github/workflows/ios-release.yml`, `docs/ios/app-store-connect-setup.md`.

## Ausgangslage

Android hat heute **keinerlei** Release-Infrastruktur: kein Fastlane, kein
`signingConfig`, keinen Keystore, keinen Workflow, keinen Play-Console-Eintrag
und keinen Service-Account. Grüne Wiese — es gibt nichts, wozu wir
rückwärtskompatibel sein müssten.

## Entscheidungen

### applicationId: `xyz.vmflow.app` → `de.kerlhandel.app`

Der iOS-Identifier `de.kerl-handel.app` lässt sich **nicht** übernehmen:
Android und Play verlangen mindestens zwei Segmente, jedes mit einem Buchstaben
beginnend, und ausschließlich `[a-zA-Z0-9_]`. Bindestriche sind verboten, AGP
bricht mit „not a valid Java package name" ab. `de.kerlhandel.app` ist das
nächstliegende gültige Äquivalent.

Der Kotlin-Namespace `xyz.vmflow` (83 Dateien, bestimmt R- und
BuildConfig-Package) bleibt unangetastet — er ist von der `applicationId`
unabhängig, und ein Umbenennen wäre ein großer Diff ohne Gegenwert. Es hängt
nichts weiter am Package: kein `google-services.json`, keine Deep Links, keine
FileProvider-Authorities.

Nebenwirkung: auf dem S10-Testgerät installiert sich der neue Build als
separate App neben `xyz.vmflow.app`. Bewusst in Kauf genommen.

### versionCode: `yyMMdd` → `yyMMdd * 100 + Commits-des-Tages`

Der bisherige date-basierte Code (`260827`) kollidiert beim zweiten Release am
selben Tag; Play weist ihn ab. Neu: `26082703` für den dritten Commit des Tages.

- monoton innerhalb des Tages (Commit-Zahl wächst) und über Tage hinweg (Datum
  dominiert),
- 8-stellig, weit unter Plays Limit von 2 100 000 000,
- Commit-Zahl auf 99 geklemmt, Fallback 0 wenn Git fehlt.

`versionName` bleibt unverändert `${versionBase}.${yyMMdd}` — konsistent mit
[[project-date-based-versioning]] auf iOS und PWA.

### Signing: Keystore als base64-GitHub-Secret

Anders als iOS (fastlane match mit eigenem Cert-Repo) braucht Android genau
**eine** Datei. Ein separates Repo mit Deploy-Key wäre mehr Bewegungsteile als
Nutzen. Der Upload-Keystore wird lokal erzeugt und als base64-Secret abgelegt;
Play App Signing hält den eigentlichen App-Signing-Key.

`signingConfigs.release` wird **nur aktiviert, wenn die Keystore-Datei
existiert**. Ohne Secrets bleibt `./gradlew assembleRelease` wie bisher als
unsignierter Build lauffähig — lokale Entwicklung bricht nicht.

Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `PLAY_JSON_KEY_BASE64`.

## Komponenten

### `android/fastlane/Fastfile`

| Lane | Entspricht iOS | Wirkung |
|---|---|---|
| `internal` | `beta` | AAB → Internal-Testing-Track, kein Review |
| `release` | `release` | Notes stampen → AAB → Production, `release_status: completed` |
| `promote` | — | Hebt den Internal-Build ohne Rebuild nach Production |
| `metadata` | `metadata` | Nur Listing-Texte, kein Binary |
| `release_notes` | `release_notes` | Dry run, ohne Secrets |
| — | `screenshots` | **entfällt**, siehe unten |

`play_auth` dekodiert `PLAY_JSON_KEY_BASE64` in eine Temp-Datei — dasselbe
Muster wie `asc_auth` auf iOS.

`promote` existiert, weil Plays Track-Modell das hergibt und Apples nicht:
derselbe geprüfte Build wandert von `internal` nach `production`, statt neu
gebaut und neu hochgeladen zu werden.

**Kein `screenshots`-Lane.** Es gibt kein `androidTest`-Sourceset; Screengrab
bräuchte zuerst eine vollständige UI-Test-Suite. Play-Screenshots bleiben
Handarbeit unter `metadata/android/<locale>/images/phoneScreenshots/` — analog
zur Altersfreigabe, die auf iOS auch von Hand in ASC gesetzt wird.

### Release-Notes: gemeinsamer Kern

Plays „Was ist neu" erlaubt **500 Zeichen**, der App Store 4000. Die
Bucketing- und Trailer-Logik (`Release-Note-DE:`, Conventional-Commit-Typen,
Verb-Heuristik, Dedup) sind ~250 Zeilen subtiler Regeln, die zwischen den
Plattformen nicht auseinanderdriften dürfen.

Deshalb wandert der Kern nach `scripts/lib/release_notes_core.rb`.
`ios/scripts/release_notes.rb` und das neue `android/scripts/release_notes.rb`
werden dünne Konfigurations-Frontends:

| | iOS | Android |
|---|---|---|
| Tag-Glob | `ios-v*` | `android-v*` |
| Pathspec | `:(top)ios/` | `:(top)android/` |
| Zeichenlimit | 4000 | 500 |
| Default-Bullets | 20 | 8 |
| Plumbing-Filter | `CFBundle`, `pbxproj`, `xcconfig`, … | `build.gradle`, `proguard`, `keystore`, `versionCode`, … |
| Präfix-Strip | `iOS …` | `Android …` |
| Ausgabe | `metadata/<locale>/release_notes.txt` | `metadata/android/<locale>/changelogs/default.txt` |

`default.txt` statt `<versionCode>.txt`, weil der versionCode erst zur Build-Zeit
feststeht; `supply` fällt genau darauf zurück.

**Risiko und Absicherung:** Der Refactor fasst den funktionierenden iOS-Pfad an.
iOS-CLI und -Defaults bleiben identisch, und die generierte iOS-Ausgabe wird
über zehn Ranges der echten Repo-Historie vor/nach dem Refactor gediffed. Der
Refactor gilt nur als fertig, wenn dieser Diff leer ist.

### `.github/workflows/android-release.yml`

`workflow_dispatch` mit `lane`, `notes` (auto|keep), `notes_since`, `rollout`.
Läuft auf `ubuntu-latest` — Android braucht kein macOS. JDK 21,
`fetch-depth: 0` (die Commit-Zahl im versionCode und die Notes-Tags brauchen
die volle Historie), Gradle-Cache.

Nach Erfolg dieselbe Zwei-Präfix-Tag-Semantik wie iOS:

| Tag | Bedeutung |
|---|---|
| `android-v<version>-<code>` | ging nach Production |
| `android-build-<version>-<code>` | nur hochgeladen (Internal-Track) |

Nur `android-v*` verankert die Notes — sonst verschwänden die Commits eines
Internal-Builds aus den Notes des nächsten echten Releases. Changelog-Rückcommit
wie auf iOS, damit ein späterer `metadata`-Lauf die Live-Notes nicht mit
veralteten überschreibt.

Das gebaute `.aab` wird **immer** als Workflow-Artefakt hochgeladen.

### `docs/android/play-console-setup.md`

Die einmaligen Schritte, die die Pipeline nicht kann: Keystore erzeugen,
App-Eintrag anlegen, Play App Signing, Service-Account samt JSON-Key und
Play-Console-Einladung, Secrets setzen — und der Stolperstein: **das allererste
AAB muss von Hand über die Play-Console-UI hoch**, die Publishing-API akzeptiert
erst danach Uploads. Dafür liefert der Workflow das Artefakt.

## Verifikation

1. `diff` der iOS-Notes-Ausgabe über zehn Ranges vor/nach dem Refactor — leer.
2. `bundle exec fastlane release_notes` (Android) erzeugt plausible Dateien.
3. `./gradlew :app:assembleRelease` läuft ohne Keystore weiterhin durch.
4. `./gradlew :app:testDebugUnitTest` bleibt grün (applicationId-Wechsel).
5. `versionCode`-Berechnung gegen die echte Git-Historie geprüft.
6. Workflow-YAML und Fastfile syntaktisch validiert.

Der erste echte Play-Upload ist nicht automatisiert verifizierbar — er hängt an
Konto und Keystore, die es noch nicht gibt.
