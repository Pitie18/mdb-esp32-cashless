# App Store Connect — first-submission setup

One-time steps that the fastlane pipeline **cannot** do for you. Do these before
the first `beta`/`release` dispatch. Ordered.

## 1. Apple Developer portal — App IDs & capabilities
- App ID `de.kerl-handel.app` must exist with **Push Notifications** and
  **Associated Domains** enabled. Cloud signing auto-creates the App ID, but a
  capability missing here makes CI provisioning fail opaquely (spec §3.3).
- App ID `de.kerl-handel.app.NotificationService` (the extension) must exist too.

## 2. App Store Connect API key — **Admin role**
- Users and Access → Integrations → App Store Connect API → generate a key with
  the **Admin** role. Lesser roles cannot use cloud-managed distribution
  certificates and the export step fails (Apple forums 698117).
- Note the **Key ID** and **Issuer ID**; download the `.p8` once.

## 3. GitHub secrets
Repo → Settings → Secrets and variables → Actions:
- `APP_STORE_CONNECT_KEY_ID` — the Key ID
- `APP_STORE_CONNECT_ISSUER_ID` — the Issuer ID
- `APP_STORE_CONNECT_KEY_P8` — the key, base64: `base64 -i AuthKey_XXXXX.p8 | pbcopy`

## 4. Create the app record
My Apps → **+** → New App:
- Platform iOS, Bundle ID `de.kerl-handel.app`, a name (e.g. "VMflow"), an SKU
  (any unique string, e.g. `vmflow-ios`), primary language English (U.K. or U.S.).
- `pilot`/`deliver` upload to an **existing** app record; they do not create it.
  Without this, the first `beta` dispatch fails "no suitable application records".

## 5. First TestFlight build
GitHub → Actions → **iOS Release** → Run workflow → lane `beta`. This uploads the
first build; TestFlight processing takes a few minutes.

## 6. Store listing (before `release` / review submission)
Metadata and screenshots are handled by the `metadata`/`release` lanes from
`ios/fastlane/`. These fields are **per-version, set by hand** in ASC and block
submission if empty:
- **Age rating** questionnaire → answer honestly; this app has no objectionable
  content and should rate 4+.
- **Category** — Primary **Business**, Secondary **Productivity**. These live in
  `metadata/primary_category.txt` / `secondary_category.txt` and the `metadata`/
  `release` lanes run `force: true`, so **deliver overwrites** any hand-set
  category on every run — the files are the source of truth. Change the files,
  not the ASC UI, or your edit gets reverted next upload.
- **Price** — Free.
- **App Privacy** — enter the answers from `app-store-privacy-answers.md`.
- **Review information** — comes from `metadata/review_information/` via deliver;
  fill the demo user/password/phone placeholders there first (see below).
- **Encryption** — already answered via `ITSAppUsesNonExemptEncryption=false`.

## 7. Before your first `release`
- Fill the placeholders in `ios/fastlane/metadata/review_information/`:
  `demo_user.txt`, `demo_password.txt`, `phone_number.txt`.
- Provision the demo account and its organisation on `supabase.kerl-handel.de`
  with seeded data, per `app-store-review-notes.md`. The reviewer will test
  account deletion — the account must be **disposable and re-seedable**.
- That folder is git-ignored and therefore absent in CI, so deliver never
  uploads review information. Enter the demo account, phone and notes **once by
  hand** in App Store Connect — ASC carries them over to every later version,
  which is what lets the automated submissions below go through untouched.

## 8. Releasing (no App Store Connect visit needed)
Actions → **iOS Release** → Run workflow → lane `release`. That single dispatch:

1. regenerates the localized release notes from the commits since the last
   `ios-v*` tag (see `release-notes.md`) and prints them to the job summary,
2. builds, signs and uploads the binary plus all metadata and screenshots,
3. **selects the uploaded build** — deliver waits out build processing and
   attaches it to the version by itself,
4. **submits for review**, and
5. sets the release type to *after approval*, so App Store Connect publishes
   the version the moment review passes.

Afterwards the workflow tags the released commit `ios-v<version>-<build>` and
commits the generated notes back, so the next run knows where to start.

Escape hatches, all on the same dispatch form:
- `submit: false` — upload only and leave the version sitting in ASC (the old
  behaviour).
- `notes: keep` — upload `fastlane/metadata/<locale>/release_notes.txt` exactly
  as committed instead of regenerating it.
- `notes_since: <ref>` — generate the notes against `<ref>` rather than the last
  tag. Needed after a rejected review, where the tag was already pushed.
- lane `release_notes` — dry run: generates and prints the notes, builds
  nothing, uploads nothing, needs no secrets.

The one thing that still needs a human in ASC is a **rejection**: read the
resolution centre, fix, dispatch `release` again.
