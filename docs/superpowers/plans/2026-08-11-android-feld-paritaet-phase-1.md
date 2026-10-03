# Android Feld-Parität, Phase 1: Toolchain und UI-Fundament — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die bestehende Android-App auf eine aktuelle Toolchain heben und ihr das Android-typische Navigations- und Theming-Fundament geben, auf dem die Feature-Pakete der Phasen 2–8 aufsetzen.

**Architecture:** Es wird nichts neu strukturiert. Der vorhandene Aufbau — Compose + Material 3, MVVM mit `data/*Repository` und `ui/*/ViewModel`, manuelles DI über `SupabaseService` — bleibt. Diese Phase tauscht die Versionsbasis, ersetzt die flache `NavHost`-Navigation durch ein `NavigationSuiteScaffold` mit Top-Level-Zielen, korrigiert Theme und Splashscreen und richtet eine JVM-Testinfrastruktur ein, die es heute überhaupt nicht gibt.

**Tech Stack:** Kotlin 2.4.10 · AGP 8.13.2 · Gradle 8.13 · Compose BOM 2026.06.01 · Material 3 Adaptive 1.2.0 · Navigation Compose 2.9.8 · Supabase Kotlin SDK 3.1.4 · Robolectric 4.16.1 · Turbine 1.2.1

## Scope

Der Spec [2026-08-11-android-feld-paritaet-design.md](../specs/2026-08-11-android-feld-paritaet-design.md) umfasst acht Arbeitspakete. Dieser Plan deckt **ausschließlich Paket 1** ab ("Toolchain und UI-Fundament"). Die Pakete 2–8 — Lager mit Barcode, Refill, Push, i18n, Maschinen-Detail, Multi-Server, PWA-Brücke — bekommen je einen eigenen Plan, weil jedes für sich lauffähige, testbare Software ergibt.

Abnahmekriterium dieser Phase: grüner Build, alle bestehenden Screens funktionieren unverändert, Navigation erfolgt über eine Navigationsleiste statt über Buttons im Dashboard.

## Global Constraints

- **Sprache im Code:** Bezeichner und Kommentare auf Englisch, passend zum vorhandenen Code. Nutzertexte niemals hart codieren.
- **Neue Nutzertexte ab sofort ausschließlich über `stringResource`**, mit Eintrag in `values/strings.xml` *und* `values-de/strings.xml`. Die vollständige Extraktion der bestehenden hart codierten Texte ist Paket 5 und **nicht** Teil dieser Phase — es wird nur verhindert, dass neue Schuld entsteht.
- **AGP bleibt auf der 8er-Linie.** AGP 9.x erzwingt Gradle 9 und bringt Breaking Changes; das ist hier ausdrücklich nicht Teil des Vorhabens.
- **Coil bleibt auf 2.7.0.** Der Sprung auf Coil 3 ist eine Paketumbenennung (`coil3.*`) und gehört nicht in diese Phase.
- **Kein Backend-Anteil.** Keine Migrationen, keine Edge-Function-Änderungen, keine Änderung an `Docker/`.
- **Keine Verhaltensänderung an bestehenden Screens** außer den hier ausdrücklich beschriebenen.
- **Minimale Touch-Targets 48 dp**, `contentDescription` an jedem Icon-Button.
- Alle Kommandos werden aus dem Verzeichnis `android/` ausgeführt.

## Voraussetzung: Android SDK

Auf dem Rechner, auf dem dieser Plan ausgeführt wird, muss ein Android SDK installiert sein (Android Studio oder `cmdline-tools`), inklusive Platform 36. Java 21 ist vorhanden und ausreichend.

Prüfen mit:

```bash
ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms"
```

Erwartet: eine Zeile `android-36`. Fehlt das SDK, lässt sich kein Schritt dieses Plans verifizieren — dann zuerst Android Studio installieren und einmal öffnen.

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `android/app/src/main/java/xyz/vmflow/ui/navigation/TopLevelDestination.kt` | Aufzählung der Top-Level-Ziele mit Route, Label-Ressource und Icon-Paar. Einzige Quelle der Wahrheit dafür, was in der Navigationsleiste steht. |
| `android/app/src/main/java/xyz/vmflow/ui/navigation/NavigationExtensions.kt` | `NavHostController.navigateToTopLevel` — Wechsel zwischen Top-Level-Zielen mit Zustandserhalt. |
| `android/app/src/main/res/values-de/strings.xml` | Deutsche Texte. Neu angelegt. |
| `android/app/src/test/java/xyz/vmflow/RoutesTest.kt` | Test für den Routen-Aufbau. |
| `android/app/src/test/java/xyz/vmflow/ui/navigation/TopLevelDestinationTest.kt` | Test für die Routen-Zuordnung der Navigationsleiste. |

**Geändert:**

| Datei | Änderung |
|---|---|
| `android/.gitignore` | Tippfehler `.local.properties` → `local.properties` |
| `android/gradle/libs.versions.toml` | Versionen und neue Bibliotheken |
| `android/gradle/wrapper/gradle-wrapper.properties` | Gradle 8.13 |
| `android/app/build.gradle` | Neue Abhängigkeiten, JVM-Target 17, Testoptionen, Versionsstempel |
| `android/app/src/main/AndroidManifest.xml` | Predictive Back, Splashscreen-Theme |
| `android/app/src/main/res/values/themes.xml` | Splashscreen-Parent, hart gesetztes `windowLightStatusBar` entfernt |
| `android/app/src/main/res/values/strings.xml` | Navigationslabels |
| `android/app/src/main/java/xyz/vmflow/MainActivity.kt` | `installSplashScreen`, `NavigationSuiteScaffold` |
| `android/app/src/main/java/xyz/vmflow/Navigation.kt` | Material-Motion statt Slide, Top-Level-Ziele ohne Rückwärtsanimation |
| `android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt` | Rückpfeil optional, `ListDetailPaneScaffold` |
| `android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt` | Rückpfeil optional |
| `android/app/src/main/java/xyz/vmflow/ui/dashboard/DashboardScreen.kt` | Navigationsbuttons entfallen |
| `android/app/src/main/java/xyz/vmflow/ui/components/StockBar.kt` | Semantik für TalkBack |

---

### Task 1: Build-Hygiene — `local.properties` aus der Versionskontrolle nehmen

`android/local.properties` ist versioniert und enthält `sdk.dir=/home/leonardo/Android/Sdk` — einen Pfad einer fremden Maschine. Die Datei sagt in ihrem eigenen Kopf, dass sie nicht eingecheckt werden darf. Sie ist nur durchgerutscht, weil `android/.gitignore` `.local.properties` mit führendem Punkt schreibt.

Das muss zuerst weg: Solange die Datei versioniert ist, überschreibt jeder `git checkout` den korrekten lokalen SDK-Pfad des Entwicklers.

**Files:**
- Modify: `android/.gitignore:12`
- Delete (nur aus dem Index): `android/local.properties`

**Interfaces:**
- Consumes: nichts
- Produces: nichts — reine Build-Hygiene

- [ ] **Step 1: Tippfehler in der `.gitignore` korrigieren**

In `android/.gitignore` die Zeile `.local.properties` ersetzen durch:

```gitignore
local.properties
```

- [ ] **Step 2: Datei aus dem Index nehmen, lokal behalten**

```bash
cd android && git rm --cached local.properties
```

Erwartet: `rm 'android/local.properties'`

- [ ] **Step 3: Verifizieren, dass die Datei jetzt ignoriert wird**

```bash
cd android && git check-ignore -v local.properties
```

Erwartet: eine Zeile, die auf `.gitignore` und das Muster `local.properties` verweist. Kein Treffer heißt, Step 1 hat nicht gegriffen.

- [ ] **Step 4: Verifizieren, dass die Datei nicht mehr im Index ist**

```bash
cd android && git ls-files | grep -c local.properties
```

Erwartet: `0`

- [ ] **Step 5: Commit**

```bash
git add android/.gitignore
git rm --cached android/local.properties
git commit -m "build(android): stop tracking local.properties

The file carried sdk.dir from a foreign machine and overwrote every
developer's local SDK path on checkout. It slipped past .gitignore
because the pattern was written with a leading dot."
```

**Dieser Commit darf keine `-- <pfade>`-Angabe bekommen.** `git commit -- <pfade>` committet den *Arbeitsbaum*-Zustand der genannten Pfade und ignoriert den Index — es würde die gerade entfernte Datei aus dem Arbeitsbaum wieder eintragen und `git rm --cached` damit exakt aufheben. Hier wird stattdessen gezielt gestaged und der Index committet.

Danach zur Sicherheit erneut prüfen:

```bash
cd android && git ls-files | grep -c local.properties
```

Erwartet: `0`. Steht dort `1`, hat der Commit die Datei wieder eingetragen.

---

### Task 2: Toolchain-Bump

Der isolierte Risikoschritt aus dem Spec. Compose BOM springt von 2025.03.00 auf 2026.06.01 und Kotlin von 2.0.21 auf 2.4.10. Der Kotlin-Sprung ist nicht optional: Ein 2.0-Compiler kann die Metadaten von Bibliotheken, die mit Kotlin 2.2+ gebaut wurden, nicht lesen, und die neue Compose-BOM enthält solche.

Nichts anderes passiert in diesem Task. Wenn der Build bricht, ist die Ursache eindeutig.

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/gradle/wrapper/gradle-wrapper.properties:3`
- Modify: `android/app/build.gradle`

**Interfaces:**
- Consumes: nichts
- Produces: Versionsaliase `libs.androidx.material3.adaptive.navigation.suite`, `libs.androidx.material3.adaptive`, `libs.androidx.material3.adaptive.layout`, `libs.androidx.material3.adaptive.navigation`, `libs.androidx.core.splashscreen` für die Tasks 4–9

- [ ] **Step 1: Gradle-Wrapper anheben**

In `android/gradle/wrapper/gradle-wrapper.properties` die Zeile `distributionUrl` ersetzen durch:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

- [ ] **Step 2: Versionskatalog aktualisieren**

In `android/gradle/libs.versions.toml` den `[versions]`-Block ersetzen durch:

```toml
[versions]
agp = "8.13.2"
kotlin = "2.4.10"
# NOT the newest: core 1.19.0 and lifecycle 2.11.0's compose artifacts
# declare minCompileSdk=37, which would force compileSdk 37 and AGP 9.1+.
# 1.18.0 and 2.10.0 are the last releases that build against compileSdk 36.
coreKtx = "1.18.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
lifecycleRuntimeKtx = "2.10.0"
activityCompose = "1.13.0"
composeBom = "2026.06.01"
navigationCompose = "2.9.8"
material3Adaptive = "1.2.0"
coreSplashscreen = "1.2.0"
supabase = "3.1.4"
ktor = "3.1.3"
coil = "2.7.0"
kotlinxSerialization = "1.11.0"
kotlinxCoroutines = "1.11.0"
kotlinxDatetime = "0.6.1"
serialization = "2.4.10"
```

- [ ] **Step 3: Neue Bibliotheken eintragen**

In `android/gradle/libs.versions.toml` am Ende des `[libraries]`-Blocks ergänzen:

```toml
# Adaptive navigation (Material 3)
androidx-material3-adaptive-navigation-suite = { group = "androidx.compose.material3", name = "material3-adaptive-navigation-suite" }
androidx-material3-adaptive = { group = "androidx.compose.material3.adaptive", name = "adaptive", version.ref = "material3Adaptive" }
androidx-material3-adaptive-layout = { group = "androidx.compose.material3.adaptive", name = "adaptive-layout", version.ref = "material3Adaptive" }
androidx-material3-adaptive-navigation = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation", version.ref = "material3Adaptive" }

# Splashscreen
androidx-core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "coreSplashscreen" }
```

- [ ] **Step 3b: Nicht existierendes Supabase-Artefakt korrigieren**

Der Katalog verweist auf `gotrue-kt`, das es in 3.x nicht gibt — die Bibliothek heißt seit 3.0 `auth-kt`. `gotrue-kt` wurde nie über 2.6.1 hinaus veröffentlicht. Da `supabase = "3.1.4"` zusammen mit diesem Verweis im allerersten Android-Commit landete, **hat dieses Modul noch nie gebaut**.

In `android/gradle/libs.versions.toml` die Zeile

```toml
supabase-gotrue = { group = "io.github.jan-tennert.supabase", name = "gotrue-kt", version.ref = "supabase" }
```

ersetzen durch:

```toml
supabase-auth = { group = "io.github.jan-tennert.supabase", name = "auth-kt", version.ref = "supabase" }
```

und in `android/app/build.gradle` die Zeile `implementation libs.supabase.gotrue` durch `implementation libs.supabase.auth`.

Der Alias wird mit umbenannt, nicht nur das Artefakt — ein Alias namens `gotrue`, der auf `auth-kt` zeigt, führt den nächsten Leser in die Irre. Die Kotlin-Quellen importieren bereits die `auth`-Paketnamen und brauchen keine Änderung.

Hinweis: `material3-adaptive-navigation-suite` bekommt bewusst **keine** `version.ref` — die Version verwaltet die Compose BOM. Die drei `androidx.compose.material3.adaptive`-Artefakte liegen in einer anderen Gruppe, die die BOM nicht abdeckt, und werden deshalb gepinnt.

- [ ] **Step 4: JVM-Target anheben und Abhängigkeiten ergänzen**

In `android/app/build.gradle` den `compileOptions`- und `kotlinOptions`-Block ersetzen durch:

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
    testOptions {
        unitTests {
            returnDefaultValues = true
        }
    }
```

Im `dependencies`-Block den Compose-Abschnitt um die Adaptive-Artefakte und den Splashscreen ergänzen:

```groovy
    implementation libs.androidx.material3.adaptive.navigation.suite
    implementation libs.androidx.material3.adaptive
    implementation libs.androidx.material3.adaptive.layout
    implementation libs.androidx.material3.adaptive.navigation
    implementation libs.androidx.core.splashscreen
```

Der Test-Abschnitt bleibt unverändert. Die Tests dieser Phase sind reines JUnit; Robolectric, Turbine und `coroutines-test` kommen in Paket 2 dazu, wenn der erste Test sie wirklich braucht.

- [ ] **Step 5: Gradle-Version verifizieren**

```bash
cd android && ./gradlew --version
```

Erwartet: `Gradle 8.13`. Der erste Lauf lädt die Distribution herunter und dauert entsprechend.

- [ ] **Step 6: Build laufen lassen**

```bash
cd android && ./gradlew clean assembleDebug
```

Erwartet: `BUILD SUCCESSFUL`.

Bricht der Build hier, liegt es fast immer an einer der drei Stellen: einer Compose-API, die in der neuen BOM umbenannt wurde, einem `kotlinOptions`-Block, den Kotlin 2.4 nicht mehr akzeptiert (dann auf `compilerOptions { jvmTarget = JvmTarget.JVM_17 }` umstellen), oder einer AGP-8.13-Warnung, die als Fehler durchschlägt. Die Fehlermeldung ist in allen drei Fällen eindeutig — reparieren und erneut bauen, bevor der Task abgeschlossen wird.

- [ ] **Step 7: Aufgelöste Versionen gegenprüfen**

```bash
cd android && ./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E "material3|compose-bom|adaptive" | head -20
```

Erwartet: `compose-bom:2026.06.01` und `material3-adaptive-*:1.2.0` in der Ausgabe, keine Zeile mit `FAILED`.

- [ ] **Step 8: Commit**

```bash
git add android/gradle/libs.versions.toml android/gradle/wrapper/gradle-wrapper.properties android/app/build.gradle
git commit -m "build(android): raise toolchain to AGP 8.13 / Kotlin 2.4 / Compose BOM 2026.06

Kotlin had to move too: a 2.0 compiler cannot read metadata from
libraries built with 2.2+, which the new Compose BOM contains.

Adds the Material 3 adaptive artifacts and core-splashscreen that the
following tasks build on."
```

---

### Task 3: Testinfrastruktur beweisen

In `android/` existiert heute kein einziger Test. Bevor irgendetwas umgebaut wird, muss belegt sein, dass der Testzyklus überhaupt läuft — sonst schreiben die Folgetasks Tests, die nie ausgeführt werden.

Das Testobjekt ist bewusst winzig: `Routes.machineDetail`. Es geht hier nicht um Testabdeckung, sondern um den Nachweis, dass `testDebugUnitTest` echte Tests findet, ausführt und bei einem Fehler rot wird.

**Files:**
- Create: `android/app/src/test/java/xyz/vmflow/RoutesTest.kt`

**Interfaces:**
- Consumes: `Routes.machineDetail(machineId: String): String` aus `android/app/src/main/java/xyz/vmflow/Navigation.kt`
- Produces: einen laufenden `testDebugUnitTest`-Zyklus für alle Folgetasks

- [ ] **Step 1: Fehlschlagenden Test schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/RoutesTest.kt`:

```kotlin
package xyz.vmflow

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `machineDetail builds a route the NavHost pattern matches`() {
        assertEquals("machines/abc-123", Routes.machineDetail("abc-123"))
    }

    @Test
    fun `machineDetail route matches the declared MACHINE_DETAIL pattern`() {
        val built = Routes.machineDetail("abc-123")
        val pattern = Routes.MACHINE_DETAIL.replace("{machineId}", "abc-123")
        assertEquals(pattern, built)
    }
}
```

- [ ] **Step 2: Test laufen lassen — er muss grün sein, aber nachweislich laufen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.RoutesTest"
```

Erwartet: `BUILD SUCCESSFUL`, und in `app/build/reports/tests/testDebugUnitTest/index.html` stehen **2 Tests**.

Prüfen, dass die Tests wirklich gelaufen sind und nicht nur nichts gefunden wurde:

```bash
cd android && grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-xyz.vmflow.RoutesTest.xml
```

Erwartet: `tests="2" skipped="0" failures="0" errors="0"`. Meldet Gradle `no tests found` oder fehlt die XML-Datei, ist die Verzeichnisstruktur falsch — der Pfad muss exakt `app/src/test/java/xyz/vmflow/` lauten.

- [ ] **Step 3: Belegen, dass ein Fehlschlag auch rot wird**

Die erste Assertion vorübergehend auf einen falschen Wert ändern:

```kotlin
        assertEquals("machines/WRONG", Routes.machineDetail("abc-123"))
```

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.RoutesTest"
```

Erwartet: `BUILD FAILED` mit `expected:<machines/[WRONG]> but was:<machines/[abc-123]>`.

- [ ] **Step 4: Assertion zurückdrehen und erneut laufen lassen**

Die Zeile wieder auf den korrekten Wert setzen:

```kotlin
        assertEquals("machines/abc-123", Routes.machineDetail("abc-123"))
```

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.RoutesTest"
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add android/app/src/test/java/xyz/vmflow/RoutesTest.kt
git commit -m "test(android): add the first unit test and prove the harness runs

There was no test source set at all. This verifies that
testDebugUnitTest discovers, runs and fails on real assertions before
later tasks start relying on it."
```

---

### Task 4: Splashscreen und Theme-Korrektur

`android/app/src/main/res/values/themes.xml` erbt heute von `android:Theme.Material.Light.NoActionBar` und setzt `android:windowLightStatusBar` hart auf `true`. Im Dunkelmodus bedeutet das dunkle Statusleisten-Icons auf dunklem Grund — unlesbar. Die beiden Farbattribute daneben sind ab API 35 wirkungslos.

Gleichzeitig kommt die App ohne Splashscreen-API aus, was ab API 31 einen weißen Blitz beim Start erzeugt.

`enableEdgeToEdge()` steht bereits korrekt in `MainActivity` und bleibt unangetastet.

**Files:**
- Modify: `android/app/src/main/res/values/themes.xml`
- Modify: `android/app/src/main/java/xyz/vmflow/MainActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml:16`

**Interfaces:**
- Consumes: `libs.androidx.core.splashscreen` aus Task 2
- Produces: nichts, worauf Folgetasks zugreifen

- [ ] **Step 1: Theme umstellen**

`android/app/src/main/res/values/themes.xml` vollständig ersetzen durch:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Applied at launch, then swapped to Theme.VMflow by installSplashScreen(). -->
    <style name="Theme.VMflow.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/splash_background</item>
        <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>
        <item name="postSplashScreenTheme">@style/Theme.VMflow</item>
    </style>

    <!-- No windowLightStatusBar here: enableEdgeToEdge() derives the bar
         icon appearance from the active light/dark scheme at runtime.
         Hardcoding it made status bar icons unreadable in dark mode. -->
    <style name="Theme.VMflow" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 2: Splash-Hintergrundfarbe ergänzen**

In `android/app/src/main/res/values/colors.xml` innerhalb von `<resources>` ergänzen:

```xml
    <color name="splash_background">#FFFFFF</color>
```

Und neu anlegen: `android/app/src/main/res/values-night/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="splash_background">#101418</color>
</resources>
```

- [ ] **Step 3: Startthema im Manifest setzen**

In `android/app/src/main/AndroidManifest.xml` im `<application>`-Element das Attribut

```xml
android:theme="@style/Theme.VMflow"
```

ersetzen durch:

```xml
android:theme="@style/Theme.VMflow.Starting"
```

- [ ] **Step 4: Splashscreen in der Activity installieren**

In `android/app/src/main/java/xyz/vmflow/MainActivity.kt` den Import ergänzen:

```kotlin
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
```

und `onCreate` ersetzen durch:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VMflowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VMflowAppRoot()
                }
            }
        }
    }
```

`installSplashScreen()` muss **vor** `super.onCreate` stehen, sonst greift der Theme-Wechsel nicht.

- [ ] **Step 5: Build verifizieren**

```bash
cd android && ./gradlew assembleDebug
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 6: Manuell auf einem Gerät oder Emulator prüfen**

App installieren und starten, einmal im Hellmodus und einmal im Dunkelmodus (Systemeinstellung umschalten, App neu starten).

Erwartet: kein weißer Blitz beim Start; die Statusleisten-Icons sind in beiden Modi lesbar (dunkle Icons auf hellem Grund, helle Icons auf dunklem Grund).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/values/themes.xml android/app/src/main/res/values/colors.xml android/app/src/main/res/values-night/colors.xml android/app/src/main/AndroidManifest.xml android/app/src/main/java/xyz/vmflow/MainActivity.kt
git commit -m "fix(android): proper splash screen and readable status bar in dark mode

themes.xml hardcoded windowLightStatusBar=true, which left dark status
bar icons on a dark background. Removing it lets enableEdgeToEdge()
derive the appearance from the active scheme.

Also adopts the API 31 splash screen instead of the white flash."
```

---

### Task 5: Predictive Back aktivieren

Android 13+ zeigt beim Zurückwischen eine Vorschau des Ziels — aber nur, wenn die App das ausdrücklich anmeldet. Ohne das Flag verhält sich die App wie eine Portierung, nicht wie eine Android-App.

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml:16`

**Interfaces:**
- Consumes: nichts
- Produces: nichts

- [ ] **Step 1: Flag im Manifest setzen**

In `android/app/src/main/AndroidManifest.xml` im `<application>`-Element ergänzen, direkt nach `android:supportsRtl="true"`:

```xml
        android:enableOnBackInvokedCallback="true"
```

- [ ] **Step 2: Build verifizieren**

```bash
cd android && ./gradlew assembleDebug
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manuell prüfen**

Auf einem Gerät mit Android 14 oder neuer: In der App zu einem Maschinendetail navigieren, dann vom linken Bildschirmrand nach rechts wischen und den Finger **halten**.

Erwartet: Der Detailbildschirm schrumpft und gibt den darunterliegenden Bildschirm frei. Ohne das Flag passiert nichts, bis der Finger losgelassen wird.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): opt into predictive back gestures"
```

---

### Task 6: Top-Level-Ziele modellieren

Die Navigationsleiste braucht eine einzige Quelle der Wahrheit dafür, welche Ziele oberste Ebene sind. Ohne dieses Modell landet die Zuordnung „aktuelle Route → markierter Eintrag" verstreut in der UI.

Drei Ziele: Dashboard, Maschinen, Refill. **Lager kommt erst in Paket 2 dazu**, wenn es den Screen wirklich gibt — kein Platzhalter.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/navigation/TopLevelDestination.kt`
- Create: `android/app/src/test/java/xyz/vmflow/ui/navigation/TopLevelDestinationTest.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values-de/strings.xml`

**Interfaces:**
- Consumes: `Routes.DASHBOARD`, `Routes.MACHINES`, `Routes.REFILL` aus `Navigation.kt`
- Produces:
  - `enum class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)` mit den Einträgen `DASHBOARD`, `MACHINES`, `REFILL`
  - `TopLevelDestination.Companion.fromRoute(route: String?): TopLevelDestination?`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/ui/navigation/TopLevelDestinationTest.kt`:

```kotlin
package xyz.vmflow.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.vmflow.Routes

class TopLevelDestinationTest {

    @Test
    fun `every top level route resolves to its destination`() {
        assertEquals(TopLevelDestination.DASHBOARD, TopLevelDestination.fromRoute(Routes.DASHBOARD))
        assertEquals(TopLevelDestination.MACHINES, TopLevelDestination.fromRoute(Routes.MACHINES))
        assertEquals(TopLevelDestination.REFILL, TopLevelDestination.fromRoute(Routes.REFILL))
    }

    @Test
    fun `a detail route is not a top level destination`() {
        assertNull(TopLevelDestination.fromRoute(Routes.machineDetail("abc-123")))
        assertNull(TopLevelDestination.fromRoute(Routes.MACHINE_DETAIL))
    }

    @Test
    fun `auth routes are not top level destinations`() {
        assertNull(TopLevelDestination.fromRoute(Routes.LOGIN))
        assertNull(TopLevelDestination.fromRoute(Routes.REGISTER))
    }

    @Test
    fun `a null route resolves to nothing`() {
        assertNull(TopLevelDestination.fromRoute(null))
    }

    @Test
    fun `destinations are declared in navigation bar order`() {
        assertEquals(
            listOf(
                TopLevelDestination.DASHBOARD,
                TopLevelDestination.MACHINES,
                TopLevelDestination.REFILL,
            ),
            TopLevelDestination.entries.toList(),
        )
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.ui.navigation.TopLevelDestinationTest"
```

Erwartet: `BUILD FAILED` mit `Unresolved reference: TopLevelDestination`

- [ ] **Step 3: Navigationslabels als Ressourcen anlegen**

In `android/app/src/main/res/values/strings.xml` innerhalb von `<resources>` ergänzen:

```xml
    <!-- Navigation bar -->
    <string name="nav_dashboard">Dashboard</string>
    <string name="nav_machines">Machines</string>
    <string name="nav_refill">Refill</string>
```

Neue Datei `android/app/src/main/res/values-de/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Navigation bar -->
    <string name="nav_dashboard">Übersicht</string>
    <string name="nav_machines">Automaten</string>
    <string name="nav_refill">Befüllen</string>
</resources>
```

`values-de/strings.xml` enthält bewusst nur die Schlüssel, die übersetzt sind — für alles andere greift automatisch `values/strings.xml`.

- [ ] **Step 4: Minimale Implementierung schreiben**

Neue Datei `android/app/src/main/java/xyz/vmflow/ui/navigation/TopLevelDestination.kt`:

```kotlin
package xyz.vmflow.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.vmflow.R
import xyz.vmflow.Routes

/**
 * The destinations reachable from the navigation bar / rail.
 *
 * Declaration order is display order. Material allows at most five
 * entries; the warehouse joins in a later package.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    DASHBOARD(
        route = Routes.DASHBOARD,
        labelRes = R.string.nav_dashboard,
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
    ),
    MACHINES(
        route = Routes.MACHINES,
        labelRes = R.string.nav_machines,
        selectedIcon = Icons.Filled.Storefront,
        unselectedIcon = Icons.Outlined.Storefront,
    ),
    REFILL(
        route = Routes.REFILL,
        labelRes = R.string.nav_refill,
        selectedIcon = Icons.Filled.Inventory2,
        unselectedIcon = Icons.Outlined.Inventory2,
    );

    companion object {
        /** Exact match only — `machines/{id}` is a detail screen, not a tab. */
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.ui.navigation.TopLevelDestinationTest"
```

Erwartet: `BUILD SUCCESSFUL`, 5 Tests grün.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/ui/navigation/TopLevelDestination.kt android/app/src/test/java/xyz/vmflow/ui/navigation/TopLevelDestinationTest.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-de/strings.xml
git commit -m "feat(android): model the top-level navigation destinations

Single source of truth for what the navigation bar shows and which
entry the current route selects. Detail routes deliberately resolve to
null so the bar does not highlight a tab while a detail screen is open.

Introduces values-de and the rule that new user-facing strings are
resources from now on."
```

---

### Task 7: NavigationSuiteScaffold einbauen

Heute erreicht man Maschinen und Refill nur über Buttons im Dashboard — ein iOS-Muster ohne Entsprechung auf Android. Ab hier gibt es eine Navigationsleiste, die auf Tablets und Foldables automatisch zur seitlichen Rail wird.

Damit ändert sich auch die Bedeutung des Rückpfeils: Auf einem Top-Level-Ziel gibt es kein Zurück mehr. `onNavigateBack` wird deshalb nullbar.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/navigation/NavigationExtensions.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/MainActivity.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/Navigation.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt:42-56`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt:39-50`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/dashboard/DashboardScreen.kt`

**Interfaces:**
- Consumes: `TopLevelDestination`, `TopLevelDestination.fromRoute` aus Task 6
- Produces:
  - `fun NavHostController.navigateToTopLevel(destination: TopLevelDestination)`
  - `MachineListScreen(onNavigateBack: (() -> Unit)?, onNavigateToMachine: (String) -> Unit, viewModel: MachinesViewModel)`
  - `RefillWizardScreen(onNavigateBack: (() -> Unit)?, onDone: () -> Unit, viewModel: RefillViewModel)`
  - `DashboardScreen(onNavigateToMachine: (String) -> Unit, onLogout: () -> Unit, viewModel: DashboardViewModel)` — die Parameter `onNavigateToMachines` und `onNavigateToRefill` entfallen

- [ ] **Step 1: Navigations-Erweiterung schreiben**

Neue Datei `android/app/src/main/java/xyz/vmflow/ui/navigation/NavigationExtensions.kt`:

```kotlin
package xyz.vmflow.ui.navigation

import androidx.navigation.NavHostController

/**
 * Switches between navigation-bar destinations.
 *
 * Pops back to the dashboard while saving each tab's back stack, so
 * returning to a tab restores where the user was — the behaviour
 * Material specifies for bottom navigation.
 *
 * The anchor is deliberately the dashboard route and NOT
 * `graph.findStartDestination()`. On a cold start without a session the
 * graph's start destination is `login`, and the sign-in handler removes
 * it from the back stack with `popUpTo(LOGIN) { inclusive = true }`.
 * Popping to an id that is no longer on the stack is a silent no-op, so
 * nothing would ever be popped or saved and the stack would grow with
 * every tab tap. The dashboard is on the stack in both entry paths.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(TopLevelDestination.DASHBOARD.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

- [ ] **Step 2: Rückpfeile auf Top-Level-Zielen optional machen**

In `android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt` die Signatur ändern:

```kotlin
fun MachineListScreen(
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToMachine: (String) -> Unit,
    viewModel: MachinesViewModel = viewModel()
) {
```

und den `navigationIcon`-Block der `TopAppBar` ersetzen durch:

```kotlin
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
```

Dazu die Importe ergänzen:

```kotlin
import androidx.compose.ui.res.stringResource
import xyz.vmflow.R
```

- [ ] **Step 3: Denselben Umbau im Refill-Wizard**

In `android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt` die Signatur ändern:

```kotlin
fun RefillWizardScreen(
    onNavigateBack: (() -> Unit)? = null,
    onDone: () -> Unit,
    viewModel: RefillViewModel = viewModel()
) {
```

Der `navigationIcon`-Block prüft dort bereits den Wizard-Schritt. Die Bedingung wird erweitert statt verschachtelt:

```kotlin
                navigationIcon = {
                    if (onNavigateBack != null && uiState.step != RefillStep.SUMMARY) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
```

Importe ergänzen:

```kotlin
import androidx.compose.ui.res.stringResource
import xyz.vmflow.R
```

- [ ] **Step 4: Textressource für den Rückpfeil ergänzen**

In `android/app/src/main/res/values/strings.xml`:

```xml
    <string name="action_back">Back</string>
```

In `android/app/src/main/res/values-de/strings.xml`:

```xml
    <string name="action_back">Zurück</string>
```

- [ ] **Step 5: Navigationsbuttons aus dem Dashboard entfernen**

In `android/app/src/main/java/xyz/vmflow/ui/dashboard/DashboardScreen.kt` die ersten beiden Parameter aus der Signatur streichen, sodass sie lautet:

```kotlin
@Composable
fun DashboardScreen(
    onNavigateToMachine: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
```

Dann den kompletten Schnellzugriff-Block entfernen — er beginnt beim Kommentar `// Quick Actions` und endet vor dem darauffolgenden `Spacer`:

```kotlin
                    // Quick Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onNavigateToRefill,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Refill")
                        }
                        FilledTonalButton(
                            onClick = onNavigateToMachines,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Icon(Icons.Default.Computer, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Machines")
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
```

Genau **einer** der beiden `Spacer(modifier = Modifier.height(24.dp))` — der vor dem Kommentar oder der danach — bleibt stehen, sonst klaffen die KPI-Karten und die Maschinenliste doppelt weit auseinander.

Die Importe `Button`, `FilledTonalButton`, `Icons.Default.LocalShipping` und `Icons.Default.Computer` werden dadurch ungenutzt. Der Compiler warnt darauf hin — entfernen.

`onNavigateToMachine` (Sprung auf eine einzelne Maschine aus der Liste darunter) und `onLogout` bleiben unverändert. Die Ziele sind ab jetzt über die Navigationsleiste erreichbar; zwei Wege zum selben Ort sind Ballast.

- [ ] **Step 6: Scaffold in `MainActivity` einbauen**

In `android/app/src/main/java/xyz/vmflow/MainActivity.kt` die Funktion `VMflowAppRoot` ersetzen durch (sie hieß bis Task 2 `VMflowApp` und wurde dort umbenannt, weil der Name mit der Klasse `VMflowApp : Application` kollidierte):

```kotlin
@Composable
fun VMflowAppRoot() {
    val navController = rememberNavController()
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val authState = AuthRepository.authState.first { it !is AuthState.Loading }
        startDestination = when (authState) {
            is AuthState.Authenticated -> Routes.DASHBOARD
            else -> Routes.LOGIN
        }
    }

    val start = startDestination ?: return

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTopLevel = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)

    NavigationSuiteScaffold(
        // Hidden on login, register and every detail screen.
        layoutType = if (currentTopLevel == null) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        },
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == currentTopLevel
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = {
                        Icon(
                            imageVector = if (selected) {
                                destination.selectedIcon
                            } else {
                                destination.unselectedIcon
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        VMflowNavHost(
            navController = navController,
            startDestination = start
        )
    }
}
```

Die Importe in derselben Datei ergänzen:

```kotlin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import xyz.vmflow.ui.navigation.TopLevelDestination
import xyz.vmflow.ui.navigation.navigateToTopLevel
```

`contentDescription = null` am Icon ist Absicht: Das Label daneben trägt bereits denselben Text, und TalkBack würde ihn sonst doppelt vorlesen.

- [ ] **Step 7: Aufrufer im NavHost anpassen**

In `android/app/src/main/java/xyz/vmflow/Navigation.kt`:

Im `composable(Routes.DASHBOARD)`-Block die beiden Zeilen `onNavigateToMachines = ...` und `onNavigateToRefill = ...` entfernen.

Im `composable(Routes.MACHINES)`-Block die Zeile `onNavigateBack = { navController.popBackStack() },` entfernen — Maschinen sind jetzt Top-Level.

Im `composable(Routes.REFILL)`-Block ebenfalls `onNavigateBack = { navController.popBackStack() },` entfernen.

- [ ] **Step 8: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 9: Manuell prüfen**

Auf einem Telefon: Anmelden. Erwartet: unten eine Navigationsleiste mit drei Einträgen. Zwischen Übersicht, Automaten und Befüllen wechseln; eine Maschine öffnen. Erwartet: Auf dem Detailbildschirm verschwindet die Leiste, der Rückpfeil ist da. Zurück, dann auf einen anderen Eintrag und wieder zurück auf Automaten. Erwartet: die Scrollposition der Liste ist erhalten.

Auf einem Tablet oder dem Emulator mit aufgeklapptem Foldable: Erwartet: dieselben Einträge als seitliche Rail links statt als Leiste unten.

Auf dem Anmeldebildschirm: Erwartet: keine Navigationsleiste.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/ui/navigation/NavigationExtensions.kt android/app/src/main/java/xyz/vmflow/MainActivity.kt android/app/src/main/java/xyz/vmflow/Navigation.kt android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt android/app/src/main/java/xyz/vmflow/ui/refill/RefillWizardScreen.kt android/app/src/main/java/xyz/vmflow/ui/dashboard/DashboardScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-de/strings.xml
git commit -m "feat(android): navigate via NavigationSuiteScaffold instead of dashboard buttons

Reaching machines and refill through buttons on the dashboard was an
iOS pattern with no Android equivalent. The suite scaffold gives a
navigation bar on phones and a rail on tablets and foldables for free,
and per-tab back stacks are preserved.

Top-level screens lose their back arrow, so onNavigateBack is nullable."
```

---

### Task 8: Material-Motion statt Richtungs-Slide

Der `NavHost` schiebt heute jeden Übergang seitwärts — auch den Wechsel zwischen gleichrangigen Tabs, wo Material ein Überblenden vorsieht. Seitwärts gehört zur Hierarchie, nicht zum Wechsel zwischen Geschwistern.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/Navigation.kt:31-63`

**Interfaces:**
- Consumes: `TopLevelDestination.fromRoute` aus Task 6
- Produces: nichts

- [ ] **Step 1: Übergänge ersetzen**

In `android/app/src/main/java/xyz/vmflow/Navigation.kt` die vier Transition-Parameter des `NavHost` ersetzen durch:

```kotlin
        enterTransition = {
            if (isTopLevelSwitch()) {
                fadeIn(tween(animDuration))
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(animDuration)
                ) + fadeIn(tween(animDuration))
            }
        },
        exitTransition = {
            if (isTopLevelSwitch()) {
                fadeOut(tween(animDuration))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(animDuration)
                ) + fadeOut(tween(animDuration))
            }
        },
        popEnterTransition = {
            if (isTopLevelSwitch()) {
                fadeIn(tween(animDuration))
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(animDuration)
                ) + fadeIn(tween(animDuration))
            }
        },
        popExitTransition = {
            if (isTopLevelSwitch()) {
                fadeOut(tween(animDuration))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(animDuration)
                ) + fadeOut(tween(animDuration))
            }
        }
```

- [ ] **Step 2: Hilfsfunktion ergänzen**

In derselben Datei, unterhalb von `VMflowNavHost`:

```kotlin
/**
 * True when both sides of the transition are navigation-bar destinations.
 *
 * Sideways motion expresses hierarchy; switching between siblings should
 * cross-fade instead.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTopLevelSwitch(): Boolean =
    TopLevelDestination.fromRoute(initialState.destination.route) != null &&
        TopLevelDestination.fromRoute(targetState.destination.route) != null
```

Und die Importe ergänzen:

```kotlin
import androidx.navigation.NavBackStackEntry
import xyz.vmflow.ui.navigation.TopLevelDestination
```

- [ ] **Step 3: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manuell prüfen**

Zwischen den drei Navigationsleisten-Einträgen wechseln. Erwartet: Überblenden, kein seitliches Schieben.
Eine Maschine aus der Liste öffnen. Erwartet: seitliches Hereinschieben von rechts.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/Navigation.kt
git commit -m "feat(android): cross-fade between tabs, slide only for hierarchy

Sideways motion signals going deeper. Applying it to sibling tabs made
every switch feel like a drill-down."
```

---

### Task 9: Adaptive Maschinenliste mit ListDetailPaneScaffold

Auf einem Tablet verschenkt eine bildschirmfüllende Liste die halbe Fläche. `ListDetailPaneScaffold` zeigt ab ausreichender Breite Liste und Detail nebeneinander und fällt am Telefon automatisch auf reine Navigation zurück — das Android-Gegenstück zu `MachinesSplitView` auf iOS.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/Navigation.kt` (Aufrufstelle `Routes.MACHINES`)

**Interfaces:**
- Consumes: `MachineListScreen(onNavigateBack: (() -> Unit)?, onNavigateToMachine: (String) -> Unit, viewModel: MachinesViewModel)` aus Task 7, `MachineDetailScreen(machineId: String, onNavigateBack: () -> Unit)`
- Produces: `MachinesPane()` — parameterloser Einstiegspunkt für `Routes.MACHINES`

`NavigableListDetailPaneScaffold` übernimmt den Bruchpunkt **und** das Zurück-Verhalten selbst: Am Telefon ist die Detailseite eine eigene Ansicht mit funktionierender Systemgeste, auf breiten Fenstern liegt sie daneben. Deshalb wird immer `navigateTo` aufgerufen und nicht fallweise über den `NavHost` navigiert — der Screen braucht gar nicht zu wissen, auf welchem Format er läuft.

Die Route `machines/{machineId}` bleibt bestehen und wird weiter vom Dashboard aus angesprungen.

- [ ] **Step 1: Adaptive Hülle schreiben**

Am Ende von `android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt` ergänzen:

```kotlin
/**
 * Machines as a list on phones, list plus detail side by side once the
 * window is wide enough. The scaffold owns the breakpoint and the back
 * behaviour of the detail pane, so this screen never branches on form
 * factor itself.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MachinesPane() {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                MachineListScreen(
                    onNavigateToMachine = { machineId ->
                        navigator.navigateTo(
                            pane = ListDetailPaneScaffoldRole.Detail,
                            contentKey = machineId,
                        )
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                navigator.currentDestination?.contentKey?.let { machineId ->
                    // Every selection needs its own composition scope. Without
                    // key(), all machines share the one ViewModelStoreOwner of
                    // the "machines" back stack entry, so switching machines
                    // keeps the previous one's stats on screen until the new
                    // fetch resolves.
                    key(machineId) {
                        MachineDetailScreen(
                            machineId = machineId,
                            onNavigateBack = { navigator.navigateBack() },
                        )
                    }
                }
            }
        },
    )
}
```

Die Importe in derselben Datei ergänzen:

```kotlin
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
```

`navigateTo` ist in Adaptive 1.2.0 eine `suspend`-Funktion. Meldet der Compiler „Suspend function should be called only from a coroutine body", den Aufruf in einen `rememberCoroutineScope().launch { }` einfassen:

```kotlin
    val scope = rememberCoroutineScope()
    // ...
                    onNavigateToMachine = { machineId ->
                        scope.launch {
                            navigator.navigateTo(
                                pane = ListDetailPaneScaffoldRole.Detail,
                                contentKey = machineId,
                            )
                        }
                    },
```

mit den Importen `androidx.compose.runtime.rememberCoroutineScope` und `kotlinx.coroutines.launch`. Dasselbe gilt dann für `navigator.navigateBack()`.

- [ ] **Step 2: Aufrufstelle umstellen**

In `android/app/src/main/java/xyz/vmflow/Navigation.kt` den `composable(Routes.MACHINES)`-Block ersetzen durch:

```kotlin
        composable(Routes.MACHINES) {
            MachinesPane()
        }
```

Und den Import `xyz.vmflow.ui.machines.MachineListScreen` ersetzen durch `xyz.vmflow.ui.machines.MachinesPane`.

- [ ] **Step 3: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manuell auf beiden Formaten prüfen**

Telefon: Automaten öffnen, eine Maschine antippen. Erwartet: Vollbild-Detail wie bisher, Rückpfeil funktioniert.

Tablet oder Emulator mit mindestens 840 dp Breite: Automaten öffnen. Erwartet: links die Liste, rechts das Detail. Eine andere Maschine antippen wechselt nur die rechte Seite; die Liste bleibt stehen.

Gerät im Querformat drehen, während ein Detail offen ist. Erwartet: kein Absturz, das gewählte Detail bleibt gewählt.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/ui/machines/MachineListScreen.kt android/app/src/main/java/xyz/vmflow/Navigation.kt
git commit -m "feat(android): show machines as list-detail on large screens

The Android counterpart to MachinesSplitView on iOS. Phones keep the
existing full-screen navigation; the scaffold decides the breakpoint."
```

---

### Task 10: Barrierefreiheit der vorhandenen Komponenten

`StockBar` hat zwei Betriebsarten, und beide sind für TalkBack mangelhaft. Mit `showLabel = true` steht über dem Balken ein `Text("$current / $capacity")`, das als „4 Schrägstrich 10" vorgelesen wird — ohne jeden Hinweis, worum es geht. Mit `showLabel = false`, wie es `StockHealthBar` auf den Maschinenkarten verwendet, ist der Füllstand ausschließlich als Farbe und Länge kodiert und wird komplett übersprungen.

Da die Komponente in den Paketen 2 und 3 stark wiederverwendet wird, wird sie jetzt korrigiert, nicht später an dreißig Aufrufstellen.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/ui/components/StockBar.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-de/strings.xml`

**Interfaces:**
- Consumes: `StockBar(current: Int, capacity: Int, modifier: Modifier, height: Dp, showLabel: Boolean, animationDurationMs: Int)` — Signatur bleibt unverändert
- Produces: dasselbe `StockBar`, ergänzt um eine zusammengeführte Semantik. `StockHealthBar` erbt sie automatisch, weil es `StockBar` aufruft.

- [ ] **Step 1: Textressource mit Platzhaltern anlegen**

In `android/app/src/main/res/values/strings.xml`:

```xml
    <string name="stock_bar_description">Stock %1$d of %2$d</string>
```

In `android/app/src/main/res/values-de/strings.xml`:

```xml
    <string name="stock_bar_description">Bestand %1$d von %2$d</string>
```

- [ ] **Step 2: Semantik in die Komponente einbauen**

In `android/app/src/main/java/xyz/vmflow/ui/components/StockBar.kt` die Zeile

```kotlin
    Column(modifier = modifier) {
```

ersetzen durch:

```kotlin
    val description = stringResource(R.string.stock_bar_description, current, capacity)

    Column(
        // mergeDescendants collapses the bar and its optional label into a
        // single node, and the explicit description replaces "4 slash 10"
        // with something a screen reader can actually convey.
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        }
    ) {
```

Importe ergänzen:

```kotlin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.vmflow.R
```

`StockHealthBar` braucht keine eigene Änderung — es reicht `StockBar` durch und erbt die Semantik mit.

- [ ] **Step 3: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`

- [ ] **Step 4: Mit TalkBack prüfen**

TalkBack in den Systemeinstellungen einschalten. **Beide** Betriebsarten prüfen:

1. Maschinen-Detailansicht mit Trays — hier läuft `StockBar` mit Label.
   Erwartet: „Bestand 4 von 10". Vorher wurde „4 Schrägstrich 10" vorgelesen.
2. Maschinenliste — hier läuft `StockHealthBar` ohne Label.
   Erwartet: ebenfalls „Bestand 4 von 10". Vorher wurde der Balken übersprungen.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/ui/components/StockBar.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-de/strings.xml
git commit -m "fix(android): make StockBar readable by TalkBack

The fill level was encoded as colour and width only, so screen readers
skipped it entirely. Fixed here rather than at the thirty call sites
the warehouse and refill packages will add."
```

---

### Task 11: Datumsbasierte Versionierung

PWA und iOS stempeln ihre Version aus dem Builddatum, Android steht auf einem festen `v2.0.0`. Damit lässt sich aus einer Fehlermeldung im Feld nicht ablesen, welcher Stand läuft.

Schema laut [2026-07-27-date-based-versioning-design.md](../specs/2026-07-27-date-based-versioning-design.md): real `MAJOR.MINOR.YYMMDD`, Anzeige `MAJOR.MINOR.M.D`. Die Basis `MAJOR.MINOR` wird von Hand gepflegt, das Datum stempelt der Build.

**Files:**
- Modify: `android/app/build.gradle`

**Interfaces:**
- Consumes: nichts
- Produces: `BuildConfig.VERSION_NAME` im Format `MAJOR.MINOR.YYMMDD`, `BuildConfig.DISPLAY_VERSION` im Format `MAJOR.MINOR.M.D`

- [ ] **Step 1: Versionsstempel einbauen**

In `android/app/build.gradle` oberhalb des `android {`-Blocks ergänzen:

```groovy
// Base version, bumped by hand. The date is stamped at build time so a
// version string from the field identifies the exact build.
def versionBase = "2.0"
def buildDate = new Date()
def versionDate = buildDate.format("yyMMdd")
def displayVersion = "${versionBase}.${buildDate.format("M.d")}"
```

und im `defaultConfig`-Block die Zeile `versionName "v2.0.0"` ersetzen durch:

```groovy
        versionName "${versionBase}.${versionDate}"
        buildConfigField "String", "DISPLAY_VERSION", "\"${displayVersion}\""
```

`versionCode 7` bleibt unverändert und wird bei jedem Release von Hand erhöht — der Play Store verlangt strikte Monotonie, ein Datumsstempel könnte das bei einem Nachreichen am selben Tag verletzen.

- [ ] **Step 2: Erzeugte Version prüfen**

```bash
cd android && ./gradlew assembleDebug && grep -rE "VERSION_NAME|DISPLAY_VERSION" app/build/generated/source/buildConfig/debug/xyz/vmflow/BuildConfig.java
```

Erwartet: zwei Zeilen, `VERSION_NAME = "2.0.260811"` und `DISPLAY_VERSION = "2.0.8.11"` — mit dem heutigen Datum.

- [ ] **Step 3: Commit**

```bash
git add android/app/build.gradle
git commit -m "build(android): adopt the repo's date-based versioning

Android was pinned to a static v2.0.0 while the PWA and iOS stamp the
build date, so a version reported from the field said nothing about
which build was running."
```

---

## Abschluss der Phase

Nach Task 11 sollte gelten:

```bash
cd android && ./gradlew clean assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`, 7 Unit-Tests grün.

Damit steht das Fundament für Paket 2 (Lager mit Barcode): aktuelle Toolchain, laufende Testinfrastruktur, Navigationsleiste, in die das Lager als viertes Ziel eingehängt wird, und die Regel, dass neue Nutzertexte Ressourcen sind.

## Nicht in dieser Phase

- Lager, Refill-Ausbau, Push, Produkte, Multi-Server, PWA-Brücke — eigene Pläne
- Vollständige Extraktion der bestehenden hart codierten englischen Texte (Paket 5)
- `android:localeConfig` und die Sprachumschaltung pro App über die Systemeinstellungen. Gehört zu Paket 5; `values-de` wird hier nur angelegt, damit ab sofort zweisprachig gearbeitet wird. Bis dahin folgt die App der Systemsprache.
- Migration von Coil 2 auf Coil 3
- AGP 9 / Gradle 9
- Umstellung auf typsichere Navigation mit `@Serializable`-Routen

## Offener Punkt aus dem Spec

Der Spec sieht vor, dass sich Dynamic Color zugunsten der Markenfarben abschalten lässt („Wer die Markenfarben erzwingen will, kann das in den Einstellungen tun"). Dafür gibt es keinen Ort: Einstellungen sind laut Modulschnitt ein Büro-Modul und bleiben auf der PWA, die das Android-Theme nicht steuern kann.

Entweder bekommt die App doch einen kleinen eigenen Einstellungsbildschirm, oder der Schalter entfällt und Dynamic Color bleibt fest eingeschaltet. Die Entscheidung ist für Phase 1 nicht nötig — `VMflowTheme` hat den Parameter `dynamicColor` bereits — muss aber vor Paket 7 fallen.

---

# Nachtrag: Serverauswahl im Login (Tasks 12–15)

Nachgereichte Anforderung. Im Spec war das Paket 7 ("Multi-Server + QR-Scan analog iOS `ServerStore`"); es wird auf Wunsch in Phase 1 vorgezogen.

**Entschiedener Umfang: exakte iOS-Parität.** Die Werkseinstellung kommt weiter aus dem Build (`gradle.properties` / `-PSUPABASE_URL`) und ist in der App **nicht** editierbar. Eigene Server werden daneben angelegt, bearbeitet und gelöscht. Das entspricht `ServerStore.swift`, wo `deleteServer` auf `isDefault` prüft und `ServerSelectionSheet` Bearbeiten nur für Nicht-Default anbietet.

**Der QR-Vertrag ist bereits etabliert** und darf nicht abgewandelt werden. `management-frontend/app/pages/mobile-app/index.vue` erzeugt `JSON.stringify({ v: 1, url, anonKey })`; `AddServerView.swift` liest daraus `v` (muss `1` sein), `url` und `anonKey`. Android liest exakt dasselbe.

**Ein Fallstrick, der mitgelöst werden muss:** `AuthRepository.authState` ist heute ein `val`, der `SupabaseService.client.auth.sessionStatus` **einmalig** einfängt. Wird der Client beim Serverwechsel ersetzt, beobachtet `authState` weiter den alten Client und der Login-Zustand friert ein. Task 14 macht den Client beobachtbar und leitet `authState` daraus ab.

**Wann darf gewechselt werden:** nur im abgemeldeten Zustand, also vom Login-Bildschirm aus — wie auf iOS, wo der Wähler in `LoginView` sitzt. Ein Wechsel bei aktiver Sitzung ist nicht vorgesehen.

---

### Task 12: `ServerEntry` und `ServerStore`

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/models/ServerEntry.kt`
- Create: `android/app/src/main/java/xyz/vmflow/data/ServerStore.kt`
- Create: `android/app/src/test/java/xyz/vmflow/models/ServerEntryTest.kt`
- Create: `android/app/src/test/java/xyz/vmflow/data/ServerStoreTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_ANON_KEY`
- Produces:
  - `data class ServerEntry(val id: String, val name: String, val url: String, val anonKey: String, val isDefault: Boolean)` mit `val sanitizedUrl: String` und `val isValid: Boolean`
  - `interface KeyValueStore { fun getString(key: String): String?; fun putString(key: String, value: String?) }`
  - `class ServerStore(private val storage: KeyValueStore, private val defaultServer: ServerEntry)` mit `allServers: List<ServerEntry>`, `customServers: StateFlow<List<ServerEntry>>`, `selectedServer: StateFlow<ServerEntry>`, `selectServer(ServerEntry)`, `addServer(ServerEntry)`, `updateServer(ServerEntry)`, `deleteServer(ServerEntry)`
  - `object ServerStoreHolder { val instance: ServerStore }` — baut den echten Store über `SharedPreferences`

`SharedPreferences` statt DataStore: entspricht der `UserDefaults`-Semantik von iOS, braucht keine neue Abhängigkeit. Die `KeyValueStore`-Abstraktion existiert allein, damit `ServerStoreTest` ohne Android-Laufzeit und ohne Robolectric läuft.

- [ ] **Step 1: Fehlschlagende Tests für `ServerEntry` schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/models/ServerEntryTest.kt`:

```kotlin
package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEntryTest {

    private fun entry(
        name: String = "My Server",
        url: String = "https://supabase.example.com",
        anonKey: String = "eyJhbGciOi",
    ) = ServerEntry(id = "id-1", name = name, url = url, anonKey = anonKey, isDefault = false)

    @Test
    fun `sanitizedUrl strips a single trailing slash`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com/").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl strips repeated trailing slashes`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com///").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl leaves a clean url alone`() {
        assertEquals("https://a.example.com", entry(url = "https://a.example.com").sanitizedUrl)
    }

    @Test
    fun `sanitizedUrl keeps a path segment`() {
        assertEquals("https://a.example.com/api", entry(url = "https://a.example.com/api/").sanitizedUrl)
    }

    @Test
    fun `a fully populated https entry is valid`() {
        assertTrue(entry().isValid)
    }

    @Test
    fun `http is accepted for lan servers`() {
        assertTrue(entry(url = "http://10.0.1.181:8000").isValid)
    }

    @Test
    fun `blank fields are invalid`() {
        assertFalse(entry(name = "").isValid)
        assertFalse(entry(url = "").isValid)
        assertFalse(entry(anonKey = "").isValid)
    }

    @Test
    fun `a url without a scheme is invalid`() {
        assertFalse(entry(url = "supabase.example.com").isValid)
    }

    @Test
    fun `a non http scheme is invalid`() {
        assertFalse(entry(url = "ftp://a.example.com").isValid)
    }

    @Test
    fun `a url without a host is invalid`() {
        assertFalse(entry(url = "https://").isValid)
    }
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.models.ServerEntryTest"
```

Erwartet: `BUILD FAILED` mit `Unresolved reference` auf `ServerEntry`.

- [ ] **Step 3: `ServerEntry` implementieren**

Neue Datei `android/app/src/main/java/xyz/vmflow/models/ServerEntry.kt`:

```kotlin
package xyz.vmflow.models

import kotlinx.serialization.Serializable

/**
 * One Supabase backend the app can talk to.
 *
 * The default entry comes from the build configuration and is not
 * editable in the app; everything else is user-defined. Mirrors
 * ServerEntry.swift on iOS so both clients accept the same QR payload.
 */
@Serializable
data class ServerEntry(
    val id: String,
    val name: String,
    val url: String,
    val anonKey: String,
    val isDefault: Boolean,
) {
    /** Trailing slashes break Supabase's URL joining, so drop them. */
    val sanitizedUrl: String
        get() = url.trimEnd('/')

    val isValid: Boolean
        get() {
            if (name.isBlank() || url.isBlank() || anonKey.isBlank()) return false
            val parsed = runCatching { java.net.URI(sanitizedUrl) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            return !parsed.host.isNullOrBlank()
        }
}
```

- [ ] **Step 4: Tests laufen lassen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.models.ServerEntryTest"
```

Erwartet: `BUILD SUCCESSFUL`, 10 Tests grün.

- [ ] **Step 5: Fehlschlagende Tests für `ServerStore` schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/data/ServerStoreTest.kt`:

```kotlin
package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.vmflow.models.ServerEntry

class ServerStoreTest {

    private class FakeStorage : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val default = ServerEntry(
        id = "00000000-0000-0000-0000-000000000001",
        name = "VMflow Cloud",
        url = "https://supabase.vmflow.xyz",
        anonKey = "factory-key",
        isDefault = true,
    )

    private lateinit var storage: FakeStorage
    private lateinit var store: ServerStore

    private fun custom(id: String, url: String = "https://a.example.com") =
        ServerEntry(id = id, name = "Server $id", url = url, anonKey = "k", isDefault = false)

    @Before
    fun setUp() {
        storage = FakeStorage()
        store = ServerStore(storage, default)
    }

    @Test
    fun `a fresh store offers only the default and selects it`() {
        assertEquals(listOf(default), store.allServers)
        assertEquals(default, store.selectedServer.value)
    }

    @Test
    fun `added servers appear after the default`() {
        store.addServer(custom("a"))
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "a"), store.allServers.map { it.id })
    }

    @Test
    fun `added servers are stored with the url sanitized`() {
        store.addServer(custom("a", url = "https://a.example.com//"))
        assertEquals("https://a.example.com", store.allServers.first { it.id == "a" }.url)
    }

    @Test
    fun `custom servers survive a new store over the same storage`() {
        store.addServer(custom("a"))
        val reopened = ServerStore(storage, default)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "a"), reopened.allServers.map { it.id })
    }

    @Test
    fun `the selection survives a new store over the same storage`() {
        val a = custom("a")
        store.addServer(a)
        store.selectServer(a)
        val reopened = ServerStore(storage, default)
        assertEquals("a", reopened.selectedServer.value.id)
    }

    @Test
    fun `updating a server replaces it in place`() {
        store.addServer(custom("a"))
        store.updateServer(custom("a").copy(name = "Renamed"))
        assertEquals("Renamed", store.allServers.first { it.id == "a" }.name)
        assertEquals(2, store.allServers.size)
    }

    @Test
    fun `deleting the selected server falls back to the default`() {
        val a = custom("a")
        store.addServer(a)
        store.selectServer(a)
        store.deleteServer(a)
        assertEquals(default, store.selectedServer.value)
        assertEquals(listOf(default), store.allServers)
    }

    @Test
    fun `the default server cannot be deleted`() {
        store.deleteServer(default)
        assertTrue(store.allServers.contains(default))
    }

    @Test
    fun `a selection pointing at a deleted server falls back to the default`() {
        storage.putString("selectedServerId", "ghost")
        val reopened = ServerStore(storage, default)
        assertEquals(default, reopened.selectedServer.value)
    }

    @Test
    fun `corrupt stored json is ignored rather than crashing`() {
        storage.putString("savedServers", "{not json")
        val reopened = ServerStore(storage, default)
        assertEquals(listOf(default), reopened.allServers)
    }
}
```

- [ ] **Step 6: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.data.ServerStoreTest"
```

Erwartet: `BUILD FAILED` mit `Unresolved reference` auf `ServerStore` bzw. `KeyValueStore`.

- [ ] **Step 7: `ServerStore` implementieren**

Neue Datei `android/app/src/main/java/xyz/vmflow/data/ServerStore.kt`:

```kotlin
package xyz.vmflow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import xyz.vmflow.BuildConfig
import xyz.vmflow.VMflowApp
import xyz.vmflow.models.ServerEntry

/** Minimal persistence seam so the store is testable without an Android runtime. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * The set of backends the user can pick from.
 *
 * The default entry is supplied by the build and is neither editable nor
 * deletable — matching ServerStore.swift. Switching servers is only
 * offered while signed out.
 */
class ServerStore(
    private val storage: KeyValueStore,
    val defaultServer: ServerEntry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _customServers = MutableStateFlow(loadCustomServers())
    val customServers: StateFlow<List<ServerEntry>> = _customServers.asStateFlow()

    private val _selectedServer = MutableStateFlow(loadSelectedServer())
    val selectedServer: StateFlow<ServerEntry> = _selectedServer.asStateFlow()

    val allServers: List<ServerEntry>
        get() = listOf(defaultServer) + _customServers.value

    fun selectServer(server: ServerEntry) {
        storage.putString(SELECTED_KEY, server.id)
        _selectedServer.value = server
    }

    fun addServer(server: ServerEntry) {
        _customServers.value = _customServers.value + server.copy(url = server.sanitizedUrl)
        persistCustomServers()
    }

    fun updateServer(server: ServerEntry) {
        // The build-supplied default is read-only, same as deleteServer.
        // Without this the selected entry could be replaced by a mutated
        // copy that is not in allServers and never gets persisted.
        if (server.isDefault) return
        val sanitized = server.copy(url = server.sanitizedUrl)
        _customServers.value = _customServers.value.map { if (it.id == server.id) sanitized else it }
        persistCustomServers()
        if (_selectedServer.value.id == server.id) _selectedServer.value = sanitized
    }

    fun deleteServer(server: ServerEntry) {
        if (server.isDefault) return
        _customServers.value = _customServers.value.filterNot { it.id == server.id }
        persistCustomServers()
        if (_selectedServer.value.id == server.id) selectServer(defaultServer)
    }

    private fun loadCustomServers(): List<ServerEntry> {
        val raw = storage.getString(SERVERS_KEY) ?: return emptyList()
        // Corrupt or older-format data must not brick the login screen.
        return runCatching { json.decodeFromString<List<ServerEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadSelectedServer(): ServerEntry {
        val id = storage.getString(SELECTED_KEY) ?: return defaultServer
        return allServers.firstOrNull { it.id == id } ?: defaultServer
    }

    private fun persistCustomServers() {
        storage.putString(SERVERS_KEY, json.encodeToString(_customServers.value))
    }

    private companion object {
        const val SERVERS_KEY = "savedServers"
        const val SELECTED_KEY = "selectedServerId"
    }
}

/** The app-wide instance, backed by SharedPreferences. */
object ServerStoreHolder {
    private const val PREFS = "vmflow_servers"

    /** Fixed id so the default entry keeps its identity across launches. */
    const val DEFAULT_SERVER_ID = "00000000-0000-0000-0000-000000000001"

    val instance: ServerStore by lazy {
        val prefs = VMflowApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ServerStore(
            storage = object : KeyValueStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String?) {
                    prefs.edit().putString(key, value).apply()
                }
            },
            defaultServer = ServerEntry(
                id = DEFAULT_SERVER_ID,
                name = "VMflow Cloud",
                url = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
                isDefault = true,
            ),
        )
    }
}
```

- [ ] **Step 8: Alle Tests laufen lassen**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`. Insgesamt 27 Tests (7 bestehende + 10 `ServerEntryTest` + 10 `ServerStoreTest`), keine Fehlschläge.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/models/ServerEntry.kt android/app/src/main/java/xyz/vmflow/data/ServerStore.kt android/app/src/test/java/xyz/vmflow/models/ServerEntryTest.kt android/app/src/test/java/xyz/vmflow/data/ServerStoreTest.kt
git commit -m "feat(android): model and persist the set of selectable servers

Mirrors ServerStore.swift: the build-supplied default is neither
editable nor deletable, user-defined servers live alongside it in
SharedPreferences.

The KeyValueStore seam exists so the store is unit-testable without an
Android runtime, which keeps Robolectric off the test classpath."
```

---

### Task 13: Den Supabase-Client umschaltbar machen

Heute ist `SupabaseService.client` ein `by lazy`-Wert aus `BuildConfig`. Damit ein anderer Server gewählt werden kann, muss der Client austauschbar werden.

Der Fallstrick dabei: `AuthRepository.authState` ist ein `val`, der `SupabaseService.client.auth.sessionStatus` **einmalig** einfängt. Wird der Client ersetzt, beobachtet `authState` weiter den alten und der Anmeldezustand friert ein. Deshalb wird der Client als `StateFlow` veröffentlicht und `authState` daraus abgeleitet.

**Files:**
- Modify: `android/app/src/main/java/xyz/vmflow/data/SupabaseService.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/data/AuthRepository.kt:23-33`

**Interfaces:**
- Consumes: `ServerStoreHolder.instance` und `ServerEntry` aus Task 12
- Produces:
  - `SupabaseService.clientFlow: StateFlow<SupabaseClient>`
  - `SupabaseService.client: SupabaseClient` (weiterhin, jetzt `clientFlow.value` — alle bestehenden Aufrufer bleiben unverändert)
  - `SupabaseService.reconfigure(server: ServerEntry)`

- [ ] **Step 1: `SupabaseService` umbauen**

`android/app/src/main/java/xyz/vmflow/data/SupabaseService.kt` vollständig ersetzen durch:

```kotlin
package xyz.vmflow.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.vmflow.models.ServerEntry

object SupabaseService {

    private fun build(server: ServerEntry): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = server.sanitizedUrl,
            supabaseKey = server.anonKey
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }

    private val _clientFlow = MutableStateFlow(build(ServerStoreHolder.instance.selectedServer.value))

    /**
     * The active client. Observe this rather than capturing [client] in a
     * `val`: switching servers replaces the instance, and anything holding
     * the old one silently stops receiving updates.
     */
    val clientFlow: StateFlow<SupabaseClient> = _clientFlow.asStateFlow()

    val client: SupabaseClient
        get() = _clientFlow.value

    /** Rebuilds the client against [server]. Only valid while signed out. */
    fun reconfigure(server: ServerEntry) {
        _clientFlow.value = build(server)
    }
}
```

- [ ] **Step 2: `authState` an den aktuellen Client binden**

In `android/app/src/main/java/xyz/vmflow/data/AuthRepository.kt` den Block

```kotlin
    val authState: Flow<AuthState> = auth.sessionStatus.map { status ->
```

ersetzen durch:

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: Flow<AuthState> = SupabaseService.clientFlow
        .flatMapLatest { it.auth.sessionStatus }
        .map { status ->
```

Der Rest des `map`-Rumpfs bleibt unverändert. Die Importe ergänzen:

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
```

Ohne `flatMapLatest` würde `authState` nach einem Serverwechsel weiter die Sitzung des alten Clients beobachten.

- [ ] **Step 3: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`, alle bestehenden Tests grün. `SupabaseService.client` behält seine Signatur, deshalb muss kein Aufrufer angefasst werden.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/data/SupabaseService.kt android/app/src/main/java/xyz/vmflow/data/AuthRepository.kt
git commit -m "feat(android): make the Supabase client switchable at runtime

authState captured the client's sessionStatus once, so replacing the
client would have frozen the sign-in state. It now follows clientFlow
via flatMapLatest."
```

---

### Task 14: Serverauswahl im Login-Bildschirm

Die Oberfläche: unten im Login ein Eintrag mit dem Namen des gewählten Servers, der ein `ModalBottomSheet` öffnet. Darin alle Server, eigene mit Bearbeiten und Löschen, dazu „Eigenen Server hinzufügen".

Android-Idiom statt iOS-Swipe-Actions: die Aktionen liegen als sichtbare `IconButton` in der Zeile, weil Swipe-Gesten auf Android nicht entdeckbar sind und hier kein `SwipeToDismissBox` mit Löschsemantik passt.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/ui/auth/ServerSelectionSheet.kt`
- Create: `android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/auth/LoginScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-de/strings.xml`

**Interfaces:**
- Consumes: `ServerStoreHolder.instance`, `ServerEntry`, `SupabaseService.reconfigure`
- Produces:
  - `ServerSelectionSheet(onDismiss: () -> Unit)`
  - `AddEditServerSheet(editing: ServerEntry?, onDismiss: () -> Unit)`

- [ ] **Step 1: Textressourcen anlegen**

In `android/app/src/main/res/values/strings.xml` ergänzen:

```xml
    <!-- Server selection -->
    <string name="server_selected">Server: %1$s</string>
    <string name="server_select_title">Select server</string>
    <string name="server_add">Add self-hosted server</string>
    <string name="server_edit">Edit server</string>
    <string name="server_delete">Delete server</string>
    <string name="server_delete_confirm">Delete “%1$s”?</string>
    <string name="server_name">Name</string>
    <string name="server_url">Supabase URL</string>
    <string name="server_anon_key">Anon key</string>
    <string name="server_name_hint">My Server</string>
    <string name="action_save">Save</string>
    <string name="action_cancel">Cancel</string>
    <string name="action_delete">Delete</string>
    <string name="action_done">Done</string>
```

In `android/app/src/main/res/values-de/strings.xml` ergänzen:

```xml
    <!-- Serverauswahl -->
    <string name="server_selected">Server: %1$s</string>
    <string name="server_select_title">Server wählen</string>
    <string name="server_add">Eigenen Server hinzufügen</string>
    <string name="server_edit">Server bearbeiten</string>
    <string name="server_delete">Server löschen</string>
    <string name="server_delete_confirm">„%1$s" löschen?</string>
    <string name="server_name">Name</string>
    <string name="server_url">Supabase-URL</string>
    <string name="server_anon_key">Anon-Key</string>
    <string name="server_name_hint">Mein Server</string>
    <string name="action_save">Speichern</string>
    <string name="action_cancel">Abbrechen</string>
    <string name="action_delete">Löschen</string>
    <string name="action_done">Fertig</string>
```

- [ ] **Step 2: `AddEditServerSheet` schreiben**

Neue Datei `android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt`:

```kotlin
package xyz.vmflow.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
import xyz.vmflow.models.ServerEntry
import java.util.UUID

/**
 * Create or edit a self-hosted server. The build-supplied default is
 * never passed here — it is not editable, matching iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerSheet(
    editing: ServerEntry?,
    onDismiss: () -> Unit,
) {
    val store = ServerStoreHolder.instance
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember { mutableStateOf(editing?.url.orEmpty()) }
    var anonKey by remember { mutableStateOf(editing?.anonKey.orEmpty()) }

    val draft = ServerEntry(
        id = editing?.id ?: "",
        name = name,
        url = url,
        anonKey = anonKey,
        isDefault = false,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(if (editing == null) R.string.server_add else R.string.server_edit),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_name)) },
                placeholder = { Text(stringResource(R.string.server_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text("https://supabase.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = anonKey,
                onValueChange = { anonKey = it },
                label = { Text(stringResource(R.string.server_anon_key)) },
                placeholder = { Text("eyJhbGciOi...") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (editing == null) {
                        store.addServer(draft.copy(id = UUID.randomUUID().toString()))
                    } else {
                        store.updateServer(draft)
                        // Editing the server we are currently pointed at has to
                        // rebuild the client, otherwise the app keeps talking to
                        // the old URL and key until the next launch.
                        if (store.selectedServer.value.id == draft.id) {
                            SupabaseService.reconfigure(store.selectedServer.value)
                        }
                    }
                    onDismiss()
                },
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
```

- [ ] **Step 3: `ServerSelectionSheet` schreiben**

Neue Datei `android/app/src/main/java/xyz/vmflow/ui/auth/ServerSelectionSheet.kt`:

```kotlin
package xyz.vmflow.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
import xyz.vmflow.data.SupabaseService
import xyz.vmflow.models.ServerEntry

/**
 * Picks which backend the app talks to. Only reachable while signed
 * out — switching rebuilds the Supabase client.
 *
 * Edit and delete are visible icon buttons rather than swipe actions:
 * on Android a swipe-to-edit affordance is undiscoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectionSheet(onDismiss: () -> Unit) {
    val store = ServerStoreHolder.instance
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customServers by store.customServers.collectAsState()
    val selected by store.selectedServer.collectAsState()

    var editing by remember { mutableStateOf<ServerEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerEntry?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.server_select_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            (listOf(store.defaultServer) + customServers).forEach { server ->
                ListItem(
                    modifier = Modifier.clickable {
                        store.selectServer(server)
                        SupabaseService.reconfigure(server)
                    },
                    leadingContent = {
                        RadioButton(
                            selected = server.id == selected.id,
                            onClick = {
                                store.selectServer(server)
                                SupabaseService.reconfigure(server)
                            },
                        )
                    },
                    headlineContent = { Text(server.name) },
                    supportingContent = {
                        Text(server.url, style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        if (server.isDefault) {
                            Icon(Icons.Default.Cloud, contentDescription = null)
                        } else {
                            Row {
                                IconButton(onClick = { editing = server }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.server_edit),
                                    )
                                }
                                IconButton(onClick = { pendingDelete = server }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.server_delete),
                                    )
                                }
                            }
                        }
                    },
                )
            }

            ListItem(
                modifier = Modifier.clickable { adding = true },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.server_add)) },
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }

    if (adding) {
        AddEditServerSheet(editing = null, onDismiss = { adding = false })
    }

    editing?.let { server ->
        AddEditServerSheet(editing = server, onDismiss = { editing = null })
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.server_delete_confirm, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteServer(server)
                    SupabaseService.reconfigure(store.selectedServer.value)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
```

Der `Icons.Default.Storage`-Import wird nicht verwendet und ist zu entfernen, falls der Compiler ihn anmahnt.

- [ ] **Step 4: In den Login-Bildschirm einhängen**

In `android/app/src/main/java/xyz/vmflow/ui/auth/LoginScreen.kt` innerhalb der äußeren `Column`, **nach** dem „Don't have an account? Register"-`TextButton`, ergänzen:

```kotlin
                Spacer(modifier = Modifier.height(8.dp))

                val selectedServer by ServerStoreHolder.instance.selectedServer.collectAsState()
                TextButton(onClick = { showServerSheet = true }) {
                    Text(
                        text = stringResource(R.string.server_selected, selectedServer.name),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
```

und oben im Composable, bei den übrigen `remember`-Zuständen:

```kotlin
    var showServerSheet by remember { mutableStateOf(false) }
```

sowie am Ende des Composable-Rumpfs, außerhalb der `Column`:

```kotlin
    if (showServerSheet) {
        ServerSelectionSheet(onDismiss = { showServerSheet = false })
    }
```

Die Importe ergänzen:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
```

- [ ] **Step 5: Build und Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`, alle bestehenden Tests grün.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/xyz/vmflow/ui/auth/ServerSelectionSheet.kt android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt android/app/src/main/java/xyz/vmflow/ui/auth/LoginScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-de/strings.xml
git commit -m "feat(android): pick the backend from the login screen

Mirrors ServerSelectionSheet.swift. Edit and delete are visible icon
buttons rather than iOS-style swipe actions, which are undiscoverable
on Android. The build-supplied default stays read-only."
```

---

### Task 15: QR-Scan für die Serverkonfiguration

Letztes Stück iOS-Parität. `management-frontend/app/pages/mobile-app/index.vue` erzeugt `JSON.stringify({ v: 1, url, anonKey })`; `AddServerView.swift` liest daraus `v` (muss `1` sein), `url` und `anonKey` und lehnt alles andere ab. Android liest exakt dasselbe.

Diese Aufgabe bringt CameraX und ML Kit ins Projekt — dieselbe Kamerabasis, die Paket 2 für den Lager-Barcode braucht. Deshalb wird der Scanner als eigenständige, wiederverwendbare Komponente gebaut und nicht in das Server-Sheet hineingeschrieben.

**Die Trennung, auf die es ankommt:** Das Parsen der Nutzlast ist reine Logik und wird vollständig unit-getestet. Die Kamera ist Verkabelung und kann ohne Gerät nicht geprüft werden.

**Files:**
- Create: `android/app/src/main/java/xyz/vmflow/models/ServerQrPayload.kt`
- Create: `android/app/src/test/java/xyz/vmflow/models/ServerQrPayloadTest.kt`
- Create: `android/app/src/main/java/xyz/vmflow/ui/components/QrScannerSheet.kt`
- Modify: `android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Produces:
  - `data class ServerQrPayload(val url: String, val anonKey: String)` mit `companion object { fun parse(raw: String): ServerQrPayload? }`
  - `QrScannerSheet(onResult: (String) -> Unit, onDismiss: () -> Unit)` — liefert den Rohtext des ersten erkannten QR-Codes

- [ ] **Step 1: Fehlschlagenden Test für das Parsen schreiben**

Neue Datei `android/app/src/test/java/xyz/vmflow/models/ServerQrPayloadTest.kt`:

```kotlin
package xyz.vmflow.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerQrPayloadTest {

    /** Exactly what management-frontend's /mobile-app page emits. */
    private val valid = """{"v":1,"url":"https://supabase.example.com","anonKey":"eyJhbGciOi"}"""

    @Test
    fun `parses the payload the web dashboard produces`() {
        val parsed = ServerQrPayload.parse(valid)
        assertEquals("https://supabase.example.com", parsed?.url)
        assertEquals("eyJhbGciOi", parsed?.anonKey)
    }

    @Test
    fun `tolerates extra keys so the web side can add fields`() {
        val withExtra = """{"v":1,"url":"https://a.example.com","anonKey":"k","name":"Prod"}"""
        assertEquals("https://a.example.com", ServerQrPayload.parse(withExtra)?.url)
    }

    @Test
    fun `rejects an unknown version`() {
        assertNull(ServerQrPayload.parse("""{"v":2,"url":"https://a.example.com","anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing version`() {
        assertNull(ServerQrPayload.parse("""{"url":"https://a.example.com","anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing url`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"anonKey":"k"}"""))
    }

    @Test
    fun `rejects a missing anon key`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"https://a.example.com"}"""))
    }

    @Test
    fun `rejects blank values`() {
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"","anonKey":"k"}"""))
        assertNull(ServerQrPayload.parse("""{"v":1,"url":"https://a.example.com","anonKey":""}"""))
    }

    @Test
    fun `rejects text that is not json`() {
        assertNull(ServerQrPayload.parse("https://a.example.com"))
        assertNull(ServerQrPayload.parse(""))
        assertNull(ServerQrPayload.parse("{not json"))
    }

    @Test
    fun `rejects a json array`() {
        assertNull(ServerQrPayload.parse("""[{"v":1,"url":"https://a.example.com","anonKey":"k"}]"""))
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.models.ServerQrPayloadTest"
```

Erwartet: `BUILD FAILED` mit `Unresolved reference` auf `ServerQrPayload`.

- [ ] **Step 3: Parser implementieren**

Neue Datei `android/app/src/main/java/xyz/vmflow/models/ServerQrPayload.kt`:

```kotlin
package xyz.vmflow.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server configuration encoded in the QR code shown by the web
 * dashboard's /mobile-app page.
 *
 * The wire format is a cross-client contract already honoured by iOS
 * (`AddServerView.handleQRCode`): `{"v":1,"url":...,"anonKey":...}`.
 * Unknown keys are tolerated so the web side can extend it; an unknown
 * `v` is rejected outright rather than guessed at.
 */
data class ServerQrPayload(val url: String, val anonKey: String) {
    companion object {
        private const val SUPPORTED_VERSION = 1

        fun parse(raw: String): ServerQrPayload? {
            val obj = runCatching {
                Json.parseToJsonElement(raw).jsonObject
            }.getOrNull() ?: return null

            val version = runCatching { obj["v"]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()
            if (version != SUPPORTED_VERSION) return null

            val url = runCatching { obj["url"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val anonKey = runCatching { obj["anonKey"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (url.isBlank() || anonKey.isBlank()) return null

            return ServerQrPayload(url = url, anonKey = anonKey)
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
cd android && ./gradlew testDebugUnitTest --tests "xyz.vmflow.models.ServerQrPayloadTest"
```

Erwartet: `BUILD SUCCESSFUL`, 9 Tests grün.

- [ ] **Step 5: Kamera-Abhängigkeiten ergänzen**

In `android/gradle/libs.versions.toml` im `[versions]`-Block ergänzen:

```toml
camerax = "1.6.1"
mlkitBarcode = "17.3.0"
```

im `[libraries]`-Block:

```toml
# Camera + barcode (also the base the warehouse scanner will reuse)
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }
```

und in `android/app/build.gradle` im `dependencies`-Block:

```groovy
    // Camera + barcode
    implementation libs.androidx.camera.core
    implementation libs.androidx.camera.camera2
    implementation libs.androidx.camera.lifecycle
    implementation libs.androidx.camera.view
    implementation libs.mlkit.barcode.scanning
```

- [ ] **Step 6: Kameraberechtigung im Manifest deklarieren**

In `android/app/src/main/AndroidManifest.xml` neben den bestehenden `uses-permission`-Zeilen ergänzen:

```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

`required="false"`, damit die App auch auf Geräten ohne Kamera installierbar bleibt.

- [ ] **Step 7: Textressourcen**

In `android/app/src/main/res/values/strings.xml`:

```xml
    <string name="qr_scan">Scan QR code</string>
    <string name="qr_scan_hint">Scan the code from your web dashboard</string>
    <string name="qr_invalid">That QR code is not a server configuration</string>
    <string name="qr_permission_needed">Camera access is needed to scan a code</string>
    <string name="qr_grant_permission">Allow camera</string>
```

In `android/app/src/main/res/values-de/strings.xml`:

```xml
    <string name="qr_scan">QR-Code scannen</string>
    <string name="qr_scan_hint">Scanne den Code aus deinem Web-Dashboard</string>
    <string name="qr_invalid">Dieser QR-Code ist keine Serverkonfiguration</string>
    <string name="qr_permission_needed">Für den Scan wird Kamerazugriff gebraucht</string>
    <string name="qr_grant_permission">Kamera erlauben</string>
```

- [ ] **Step 8: Scanner-Komponente schreiben**

Neue Datei `android/app/src/main/java/xyz/vmflow/ui/components/QrScannerSheet.kt`:

```kotlin
package xyz.vmflow.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import xyz.vmflow.R
import java.util.concurrent.Executors

/**
 * Scans a single QR code and hands its raw text to [onResult].
 *
 * Deliberately generic — it returns the raw string rather than a parsed
 * server config, so the warehouse barcode work can reuse it unchanged.
 * The camera permission is requested here, at the moment of scanning,
 * rather than at app start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGetImage::class)
@Composable
fun QrScannerSheet(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.qr_scan),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.qr_scan_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!hasPermission) {
                Text(stringResource(R.string.qr_permission_needed))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.qr_grant_permission))
                }
            } else {
                val executor = remember { Executors.newSingleThreadExecutor() }
                DisposableEffect(Unit) { onDispose { executor.shutdown() } }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val scanner = BarcodeScanning.getClient()
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()
                                    .also { it.setAnalyzer(executor) { proxy ->
                                        val media = proxy.image
                                        if (media == null) {
                                            proxy.close()
                                        } else {
                                            val image = InputImage.fromMediaImage(
                                                media,
                                                proxy.imageInfo.rotationDegrees
                                            )
                                            scanner.process(image)
                                                .addOnSuccessListener { codes ->
                                                    codes.firstOrNull { code ->
                                                        code.format == Barcode.FORMAT_QR_CODE
                                                    }?.rawValue?.let(onResult)
                                                }
                                                .addOnCompleteListener { proxy.close() }
                                        }
                                    } }

                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 9: In das Server-Sheet einhängen**

In `android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt` einen Zustand ergänzen:

```kotlin
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
```

Oberhalb des Namensfelds eine Schaltfläche einsetzen:

```kotlin
            OutlinedButton(
                onClick = { showScanner = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.qr_scan))
            }

            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
```

und am Ende des Composable-Rumpfs, außerhalb der `Column`:

```kotlin
    if (showScanner) {
        val invalidMessage = stringResource(R.string.qr_invalid)
        QrScannerSheet(
            onResult = { raw ->
                val payload = ServerQrPayload.parse(raw)
                if (payload == null) {
                    scanError = invalidMessage
                } else {
                    url = payload.url
                    anonKey = payload.anonKey
                    scanError = null
                }
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
    }
```

Die Importe ergänzen: `androidx.compose.material3.OutlinedButton`, `androidx.compose.material3.MaterialTheme`, `xyz.vmflow.models.ServerQrPayload`, `xyz.vmflow.ui.components.QrScannerSheet`.

Der Scan füllt nur die Felder — gespeichert wird weiterhin bewusst über „Speichern", damit man den Namen noch vergeben kann.

- [ ] **Step 10: Build und alle Tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Erwartet: `BUILD SUCCESSFUL`, 37 Tests (28 bestehende + 9 neue), keine Fehlschläge.

- [ ] **Step 11: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle android/app/src/main/AndroidManifest.xml android/app/src/main/java/xyz/vmflow/models/ServerQrPayload.kt android/app/src/test/java/xyz/vmflow/models/ServerQrPayloadTest.kt android/app/src/main/java/xyz/vmflow/ui/components/QrScannerSheet.kt android/app/src/main/java/xyz/vmflow/ui/auth/AddEditServerSheet.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-de/strings.xml
git commit -m "feat(android): configure a server by scanning the dashboard QR code

Reads the same {v:1,url,anonKey} payload the web dashboard emits and
iOS already accepts. The parser is pure and fully unit-tested; the
camera is CameraX + ML Kit, kept as a generic raw-string scanner so the
warehouse barcode work can reuse it unchanged."
```
