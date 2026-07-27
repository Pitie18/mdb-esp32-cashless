# Date-Based Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed the build date in both client apps' version numbers so `1.0` built on 27.07.2026 shows as `1.0.7.27`, while the real store/semver version stays a valid `1.0.260727`.

**Architecture:** A pure format helper (once in TypeScript, once in Swift) maps a base `MAJOR.MINOR` plus a date to two strings — the real version `MAJOR.MINOR.YYMMDD` (Apple/npm-valid, monotonic across years) and the display version `MAJOR.MINOR.M.D`. The PWA computes both at build time in `nuxt.config.ts` from `package.json` + `BUILD_DATE`; iOS stamps `CFBundleShortVersionString` in the existing pre-build script and renders the display form in Settings.

**Tech Stack:** TypeScript / Nuxt 4 / Vitest (PWA), Swift / XcodeGen `project.yml` / PlistBuddy (iOS).

## Global Constraints

- Real version format: `MAJOR.MINOR.YYMMDD`, exactly 3 period-separated integers (Apple `CFBundleShortVersionString` limit + valid npm semver). Example 27.07.2026 → `1.0.260727`.
- Display version format: `MAJOR.MINOR.M.D`, month and day **without** leading zeros. Example → `1.0.7.27`.
- `YY`/`MM`/`DD` are each zero-padded to 2 digits in the real version; their concatenation is one integer component.
- Base `MAJOR.MINOR` is maintained manually at exactly one place per app: iOS `MARKETING_VERSION` in `ios/project.yml`; PWA `version` in `management-frontend/package.json` (first two components).
- Client-only change: no DB migration, no MQTT/edge-function/API/firmware change.
- iOS: avoid adding a new `.swift` file (no XcodeGen run assumed); place Swift helper in the already-registered `SettingsView.swift`.

---

### Task 1: TypeScript version helper + tests

**Files:**
- Create: `management-frontend/app/lib/appVersion.ts`
- Test: `management-frontend/app/lib/__tests__/appVersion.test.ts`

**Interfaces:**
- Produces:
  - `baseFromSemver(version: string): string` — first two dot-components, e.g. `"1.0.0"` → `"1.0"`.
  - `formatVersion(base: string, date: Date): { real: string; display: string }` — e.g. `("1.0", 2026-07-27)` → `{ real: "1.0.260727", display: "1.0.7.27" }`.

- [ ] **Step 1: Write the failing test**

Create `management-frontend/app/lib/__tests__/appVersion.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { baseFromSemver, formatVersion } from '../appVersion'

describe('baseFromSemver', () => {
  it('takes the first two components', () => {
    expect(baseFromSemver('1.0.0')).toBe('1.0')
    expect(baseFromSemver('2.5.13')).toBe('2.5')
  })
  it('passes through a bare major.minor', () => {
    expect(baseFromSemver('1.0')).toBe('1.0')
  })
})

describe('formatVersion', () => {
  it('builds real (YYMMDD) and display (M.D) for a two-digit month/day', () => {
    const r = formatVersion('1.0', new Date(2026, 6, 27)) // month is 0-based: 6 = July
    expect(r.real).toBe('1.0.260727')
    expect(r.display).toBe('1.0.7.27')
  })
  it('pads the real version but strips leading zeros in display', () => {
    const r = formatVersion('1.0', new Date(2026, 0, 5)) // 5 Jan 2026
    expect(r.real).toBe('1.0.260105')
    expect(r.display).toBe('1.0.1.5')
  })
  it('handles December for monotonic ordering', () => {
    const r = formatVersion('1.0', new Date(2026, 11, 31)) // 31 Dec 2026
    expect(r.real).toBe('1.0.261231')
    expect(r.display).toBe('1.0.12.31')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/appVersion.test.ts`
Expected: FAIL — cannot resolve `../appVersion`.

- [ ] **Step 3: Write minimal implementation**

Create `management-frontend/app/lib/appVersion.ts`:

```ts
/**
 * Version formatting for the date-based scheme.
 *
 * Real version   MAJOR.MINOR.YYMMDD  (valid semver + Apple CFBundleShortVersionString,
 *                                      monotonic across year boundaries)
 * Display version MAJOR.MINOR.M.D     (month/day without leading zeros)
 */
const pad2 = (n: number): string => String(n).padStart(2, '0')

/** First two dot-components of a semver string, e.g. "1.0.0" -> "1.0". */
export function baseFromSemver(version: string): string {
  return version.split('.').slice(0, 2).join('.')
}

export function formatVersion(base: string, date: Date): { real: string; display: string } {
  const yy = date.getFullYear() % 100
  const mm = date.getMonth() + 1
  const dd = date.getDate()
  const real = `${base}.${pad2(yy)}${pad2(mm)}${pad2(dd)}`
  const display = `${base}.${mm}.${dd}`
  return { real, display }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd management-frontend && npx vitest run app/lib/__tests__/appVersion.test.ts`
Expected: PASS (all 6 assertions).

- [ ] **Step 5: Commit**

```bash
git add management-frontend/app/lib/appVersion.ts management-frontend/app/lib/__tests__/appVersion.test.ts
git commit -m "feat(frontend): add date-based version format helper"
```

---

### Task 2: Wire version helper into Nuxt config + sidebar

**Files:**
- Modify: `management-frontend/nuxt.config.ts:44-53` (runtimeConfig.public)
- Modify: `management-frontend/app/components/AppSidebar.vue:43-51` (versionLine)

**Interfaces:**
- Consumes: `baseFromSemver`, `formatVersion` from Task 1.
- Produces: `runtimeConfig.public.appVersion` (real, e.g. `1.0.260727`) and `runtimeConfig.public.appVersionDisplay` (e.g. `1.0.7.27`).

- [ ] **Step 1: Compute versions in `nuxt.config.ts`**

At the top of the file (`pkg` is already imported on line 1), add the import and computation. After the existing `import pkg from './package.json'` line, add:

```ts
import { baseFromSemver, formatVersion } from './app/lib/appVersion'

const buildDateRaw = process.env.BUILD_DATE ?? ''
const buildDateObj = buildDateRaw && !isNaN(new Date(buildDateRaw).getTime())
  ? new Date(buildDateRaw)
  : new Date()
const { real: appVersionReal, display: appVersionDisplay } = formatVersion(
  baseFromSemver(pkg.version),
  buildDateObj,
)
```

Then in `runtimeConfig.public` (lines 44-53), replace the `appVersion` line and add a display field:

```ts
      appVersion: appVersionReal,
      appVersionDisplay: appVersionDisplay,
```

(Leave `buildDate: process.env.BUILD_DATE ?? ''` unchanged.)

- [ ] **Step 2: Use the display version in the sidebar**

In `management-frontend/app/components/AppSidebar.vue`, change the `versionLine` computed (lines 43-51) so the first line uses the display version:

```ts
const versionLine = computed(() => {
  const v = `v${config.public.appVersionDisplay}`
  const raw = config.public.buildDate
  if (!raw) return v
  const d = new Date(raw)
  if (isNaN(d.getTime())) return v
  return `${v} · ${d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })}`
})
```

- [ ] **Step 3: Type-check and build**

Run: `cd management-frontend && BUILD_DATE=2026-07-27T10:00:00Z npm run build`
Expected: build succeeds, no TypeScript error about `appVersionDisplay`.

- [ ] **Step 4: Verify the rendered version string**

Run: `cd management-frontend && grep -ro "1\.0\.7\.27" .output 2>/dev/null | head -1`
Expected: at least one match (the display version is baked into the built output).
If `.output` layout differs, instead run the dev server and read the sidebar via the browser preview: it must show `v1.0.7.27`.

- [ ] **Step 5: Commit**

```bash
git add management-frontend/nuxt.config.ts management-frontend/app/components/AppSidebar.vue
git commit -m "feat(frontend): show date-based version in sidebar"
```

---

### Task 3: iOS build stamps CFBundleShortVersionString with the date

**Files:**
- Modify: `ios/project.yml:43-49` (VMflow preBuildScript) and `ios/project.yml:69-75` (NotificationService preBuildScript)

**Interfaces:**
- Consumes: `MARKETING_VERSION` build setting (`"1.0"`, already in `settings.base`, inherited by both targets and exposed as `$MARKETING_VERSION` in run-script phases).
- Produces: built bundles carry `CFBundleShortVersionString = <MARKETING_VERSION>.YYMMDD`.

- [ ] **Step 1: Extend the VMflow pre-build script**

In `ios/project.yml`, replace the VMflow `Auto-increment build number from git` script body (lines 45-47) with:

```yaml
        script: |
          BUILD_NUM=$(git -C "${SRCROOT}/.." rev-list --count HEAD)
          /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUM" "${SRCROOT}/VMflow/Resources/Info.plist"
          SHORT_VERSION="${MARKETING_VERSION}.$(date +%y%m%d)"
          /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $SHORT_VERSION" "${SRCROOT}/VMflow/Resources/Info.plist"
```

- [ ] **Step 2: Extend the NotificationService pre-build script**

In `ios/project.yml`, replace the NotificationService `Auto-increment extension build number from git` script body (lines 71-73) with:

```yaml
        script: |
          BUILD_NUM=$(git -C "${SRCROOT}/.." rev-list --count HEAD)
          /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUM" "${SRCROOT}/NotificationService/Info.plist"
          SHORT_VERSION="${MARKETING_VERSION}.$(date +%y%m%d)"
          /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $SHORT_VERSION" "${SRCROOT}/NotificationService/Info.plist"
```

- [ ] **Step 3: Regenerate the Xcode project (if XcodeGen is used to produce the pbxproj)**

Run: `cd ios && xcodegen generate` (skip if the repo edits `project.pbxproj` by hand — then apply the same two script bodies to the corresponding `PBXShellScriptBuildPhase` entries).
Expected: `VMflow.xcodeproj` regenerated without error.

- [ ] **Step 4: Build and confirm the stamped short version**

Run:
```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```
Then read the value written into the source plist by the script:
`/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" ios/VMflow/Resources/Info.plist`
Expected: `1.0.` followed by today's `YYMMDD` (e.g. `1.0.260727`), and the build succeeds.

- [ ] **Step 5: Commit**

Note: the build rewrites the tracked `Info.plist` version strings (same existing behavior as the git-count build number). Stage `project.yml`, the regenerated `project.pbxproj` if changed, and the updated `Info.plist` values.

```bash
git add ios/project.yml ios/VMflow.xcodeproj/project.pbxproj ios/VMflow/Resources/Info.plist ios/NotificationService/Info.plist
git commit -m "feat(ios): stamp CFBundleShortVersionString with build date"
```

---

### Task 4: iOS Settings shows the display version

**Files:**
- Modify: `ios/VMflow/Views/Settings/SettingsView.swift` (add an About section + append an `AppVersion` helper enum before `#Preview`)
- Modify: `ios/VMflow/Resources/Localizable.xcstrings` (German entries for the two new strings)

**Interfaces:**
- Consumes: `CFBundleShortVersionString` from the bundle (stamped in Task 3, e.g. `1.0.260727`).
- Produces: `AppVersion.current` — the display string (`1.0.7.27`) shown in Settings.

- [ ] **Step 1: Add the `AppVersion` helper (append to `SettingsView.swift`, after the `struct SettingsView` closing brace and before `#Preview`)**

```swift
/// Converts the stamped short-version string ("1.0.260727") into the
/// human-facing display form ("1.0.7.27"). Falls back to the raw input if the
/// last component is not a 6-digit YYMMDD date.
enum AppVersion {
    static func display(from shortVersion: String) -> String {
        let parts = shortVersion.split(separator: ".").map(String.init)
        guard let last = parts.last, last.count == 6, Int(last) != nil else {
            return shortVersion
        }
        let base = parts.dropLast().joined(separator: ".")
        let mm = Int(last.dropFirst(2).prefix(2)) ?? 0
        let dd = Int(last.suffix(2)) ?? 0
        return "\(base).\(mm).\(dd)"
    }

    static var current: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        return display(from: short)
    }
}
```

- [ ] **Step 2: Verify the pure helper with a throwaway Swift script (no test target exists)**

Create `/tmp/appversion_check.swift` with the `display(from:)` logic copied verbatim plus:

```swift
assert(AppVersion.display(from: "1.0.260727") == "1.0.7.27")
assert(AppVersion.display(from: "1.0.260105") == "1.0.1.5")
assert(AppVersion.display(from: "1.0") == "1.0")           // no date component -> passthrough
print("ok")
```

Run: `swift /tmp/appversion_check.swift`
Expected: prints `ok` (no assertion failure).

- [ ] **Step 3: Add the About section to the Settings `List`**

In `SettingsView.swift`, add this Section as the last item inside the `List { … }` (after the existing sections, e.g. right before the `List`'s closing brace):

```swift
            // MARK: - About Section
            Section {
                HStack {
                    Text("Version")
                    Spacer()
                    Text(AppVersion.current)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Label("About", systemImage: "info.circle")
            }
```

- [ ] **Step 4: Add German translations for the two new strings**

In `ios/VMflow/Resources/Localizable.xcstrings`, add `de` entries (du-tone) for the new keys, following the surgical-edit approach (do not reserialize the whole file):
- `"Version"` → German `"Version"`
- `"About"` → German `"Über"`

- [ ] **Step 5: Build and confirm it compiles and localizes**

Run:
```bash
cd ios && xcodebuild -project VMflow.xcodeproj -scheme VMflow \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```
Expected: build succeeds (xcstringstool validates the catalog during the build).

- [ ] **Step 6: Commit**

```bash
git add ios/VMflow/Views/Settings/SettingsView.swift ios/VMflow/Resources/Localizable.xcstrings
git commit -m "feat(ios): show date-based version in Settings"
```

---

## Self-Review Notes

- **Spec coverage:** Real vs display formats (Task 1); PWA build wiring + sidebar (Task 2); iOS build stamping both targets (Task 3); iOS in-app display + shared helper logic + German strings (Task 4). Shared "one place for format rules" satisfied by `appVersion.ts` (TS) and `AppVersion` (Swift) using identical rules.
- **Type consistency:** `formatVersion` returns `{ real, display }` in Task 1 and is consumed with those exact keys in Task 2. Swift `AppVersion.display(from:)` / `AppVersion.current` names match between Task 4 steps.
- **Verification per stack:** Vitest (PWA helper), `npm run build` + grep (PWA wiring), `xcodebuild` + PlistBuddy read (iOS stamp), throwaway `swift` + `xcodebuild` (iOS display).
