# Google Play — first-release setup

One-time steps that the fastlane pipeline **cannot** do for you. Do these before
the first `internal`/`release` dispatch. Ordered.

The Android counterpart to `docs/ios/app-store-connect-setup.md`. Where the two
pipelines differ, it is because the stores differ — those differences are called
out inline rather than left for you to trip over.

## 0. The package name is permanent

The app ships as **`de.kerlhandel.app`**. That is as close to the iOS bundle ID
`de.kerl-handel.app` as Android allows: an applicationId may only contain
`[a-zA-Z0-9_]`, with every dot-separated segment starting on a letter, so the
hyphen had to go.

Play binds a package name to an app record **forever** — it cannot be renamed,
and it cannot be reused by another app even after the first one is deleted. If
you want a different one, change it in `android/app/build.gradle` *before* the
first upload, not after.

## 1. Create the developer account

**Do this first — the verification, not the pipeline, is the long pole.** The
`$25` fee is instant; being allowed to publish is not.

### Pick the account type before you start

This is the decision that matters, and it cannot be changed later without
registering again from scratch:

| | Organisation | Personal |
|---|---|---|
| Who it's for | a company — Kerl Handel | an individual developer |
| Extra requirement | a **D-U-N-S number** for the legal entity | none |
| Shown on the listing | the company name | your verified name |
| Before you may ship to production | nothing extra | a **closed test with at least 12 testers, opted in continuously for 14 days**, then apply for production access |

**Take the organisation account.** VMflow is sold as a business tool, the
listing should say Kerl Handel rather than a private name, and it skips the
12-tester/14-day gate that would otherwise sit between you and the first
production release.

The catch is the D-U-N-S number: it is free from Dun & Bradstreet, but issuing
one takes on the order of days to a few weeks. Request it **before** anything
else in this document — everything else here is hours of work, that is the part
that waits on someone else. Kerl Handel may already have one; check first.

### Steps

1. **Use a company Google account**, not a personal one. The account that
   registers owns the developer account, and moving apps between developer
   accounts later is a formal transfer process, not a setting.
2. Request the **D-U-N-S number** for the legal entity if you don't have one
   (name, address and phone must match your business registration exactly —
   a mismatch is the usual cause of a rejected verification).
3. Sign up at <https://play.google.com/console/signup>, choose **organisation**,
   and pay the one-time **$25** registration fee.
4. Complete **identity and organisation verification**: legal name, address,
   phone, website, and the D-U-N-S number. Google emails you when it clears —
   plan for days, not minutes.
5. Fill in the **developer profile**. The developer name, email address and
   (for organisations) address are shown publicly on every listing, so use
   company contact details — the same ones the app's imprint uses.
6. Under **Users and permissions**, add whoever else needs access. Keep the
   owner account separate from day-to-day logins.

Once the account shows as verified, continue with section 2.

> Google reworks these requirements regularly — the tester count, the
> verification documents and the fee have all moved before. The console's own
> onboarding checklist is authoritative; if it disagrees with this page, it
> wins, and this page needs fixing.

## 2. Create the upload keystore

Android has no equivalent of Apple's certificate cap, so there is no fastlane
match here and no separate cert repo — one file does it:

```bash
keytool -genkeypair -v -keystore vmflow-upload.jks -alias vmflow-upload -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

Answer the prompts (organisation etc. — they are cosmetic) and pick a strong
store password. Use the **same** value for the key password when prompted; the
pipeline passes both, but keeping them equal is one less thing to lose.

Keep the `.jks` somewhere safe and out of the repo — `android/.gitignore`
already refuses `*.jks` and `*.keystore`, deliberately.

This is the **upload key**, not the app signing key. With Play App Signing
(step 4) Google holds the actual signing key and re-signs every upload; the
upload key only proves the upload is yours. If you lose it, Google can reset
it — which is exactly why this doesn't need the ceremony iOS signing does.

## 3. Create the app record

Play Console → **All apps** → **Create app**:

- App name **VMflow**, default language **German (Germany)** or English, type
  **App**, **Free**.
- Accept the declarations.

`supply` uploads to an **existing** app record; it does not create one.

## 4. Turn on Play App Signing and register the upload key

Play Console → your app → **Test and release → Setup → App integrity → App
signing**. Choose *Let Google create and manage my app signing key*, then
register the certificate of the keystore from step 2 as the upload
certificate. Export it with:

```bash
keytool -export -rfc -keystore vmflow-upload.jks -alias vmflow-upload -file upload_certificate.pem
```

## 5. Service account for the Publishing API

1. Play Console → **Setup → API access** → link (or create) a Google Cloud
   project.
2. In that Google Cloud project: **IAM & Admin → Service Accounts → Create**.
   No project-level roles are needed — Play grants the permissions itself.
3. On the new account: **Keys → Add key → Create new key → JSON**. Downloaded
   once, never again.
4. Back in Play Console → **Users and permissions → Invite new users**, invite
   the service account's `…iam.gserviceaccount.com` address and give it, scoped
   to this app: **Release to testing tracks**, **Release to production**, and
   **Edit store listing, pricing & distribution**.

Permission changes can take a little while to propagate; a first run that fails
with a 401/403 right after this step is worth simply retrying.

## 6. GitHub secrets

Repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `PLAY_JSON_KEY_BASE64` | the service-account JSON: `base64 < play-service-account.json \| pbcopy` |
| `ANDROID_KEYSTORE_BASE64` | the keystore: `base64 < vmflow-upload.jks \| pbcopy` |
| `ANDROID_KEYSTORE_PASSWORD` | the store password from step 2 |
| `ANDROID_KEY_ALIAS` | `vmflow-upload` |
| `ANDROID_KEY_PASSWORD` | the key password from step 2 |

Piping through stdin rather than `base64 -i file` is deliberate: the input flags
differ between stock macOS's BSD `base64` and the GNU/Homebrew one, stdin works
on both. Same reasoning as the iOS `.p8` secret.

## 7. The first upload is manual

**Play will not accept an upload through the API until the app has had one
release created in the console.** This catches everyone once. The workflow
hands you the artefact for it:

1. Actions → **Android Release** → Run workflow → lane `internal`. The upload
   step will fail — that is expected and fine.
2. Download the `vmflow-release-aab` artefact from that run.
3. Play Console → **Testing → Internal testing → Create new release**, upload
   the `.aab` by hand, roll it out.

From then on every `internal`/`release` dispatch goes through the API.

If you ended up on a **personal** account after all (section 1), production is
gated behind a closed test with 12 testers for 14 days. The `internal` lane is
what feeds that test — dispatch it as often as you like; only the console's
counter has to be satisfied.

## 8. Store listing

The `metadata` and `release` lanes push these from
`android/fastlane/metadata/android/<locale>/`:

| File | Play field | Limit |
|---|---|---|
| `title.txt` | app name | 30 |
| `short_description.txt` | short description | 80 |
| `full_description.txt` | full description | 4000 |
| `changelogs/default.txt` | "What's new" | 500 |
| `images/phoneScreenshots/*.png` | phone screenshots | ≥ 2 required |

**Screenshots are hand-managed.** iOS generates them from UI tests via
`snapshot`; Android has no `androidTest` sources to generate them from, so
Screengrab would mean writing a UI-test suite first. Drop PNGs into
`images/phoneScreenshots/` (and `images/tenInchScreenshots/` for tablets) and
they upload with the next `metadata` or `release` run. The iOS screenshots are
**not** reusable: at 1320 × 2868 they exceed Play's 2:1 aspect-ratio cap.

These are set **by hand** in the console and block publishing while empty —
`supply` cannot touch any of them:

- **Content rating** questionnaire (IARC).
- **Data safety** form — the answers in `docs/ios/app-store-privacy-answers.md`
  map over closely; the questions are worded differently.
- **Target audience and content**.
- **Privacy policy URL** — `https://app.kerl-handel.de/legal/privacy`.
- **App category** and contact details.
- **Pricing** — free.

## 9. Releasing

Actions → **Android Release** → Run workflow.

| lane | what it does |
|---|---|
| `internal` | builds, signs, uploads to the internal testing track. The `beta`/TestFlight equivalent. No store listing is touched. |
| `release` | regenerates the release notes, builds, signs, uploads binary **and** listing to production |
| `promote` | moves the build already on the internal track to production without rebuilding — the exact bytes testers vetted. No iOS equivalent. |
| `metadata` | listing text and screenshots only, no binary |
| `release_notes` | dry run: generates and prints the notes, needs no secrets |

Unlike the App Store, Play reviews **after** the upload rather than gating it,
so there is no "submit for review" step — a successful production upload *is*
the submission. Review typically clears in hours to a couple of days; a
rejection appears in the console's Policy status.

Dispatch options:

- `rollout: 0.2` — staged rollout to 20% of users (`release` and `promote`).
  Empty means everyone. Ramp it up afterwards in the console.
- `notes: keep` — upload `changelogs/default.txt` exactly as committed instead
  of regenerating it, for hand-written notes.
- `notes_since: <ref>` — generate the notes against `<ref>` rather than the last
  tag.
- `version_code: <code>` — for `promote`, pick a specific build instead of
  whatever is currently on the internal track.

Afterwards the workflow tags the commit it built and commits the generated
notes back, exactly as the iOS pipeline does:

| tag | meaning |
|---|---|
| `android-v<version>-<code>` | went to the production track |
| `android-build-<version>-<code>` | internal track only |

Only `android-v*` anchors the release notes, so the commits in an internal build
still show up in the next real release's notes instead of vanishing.

## 10. versionCode

`versionCode` is `yyMMdd * 100 + the day's commit count` — `26082703` is the
third commit of 2026-08-27 (`android/app/build.gradle`). Play permanently
refuses a versionCode it has already seen, so this must only ever increase.

Two consequences:

- The workflow checks out with `fetch-depth: 0`. A shallow clone makes the
  commit count wrong.
- **Do not upload from your machine.** A local build burns a versionCode that
  CI then cannot reuse. Local release builds are for debugging; without the
  keystore env vars they come out unsigned on purpose.

## 11. Writing better release notes

Identical to iOS, including the `Release-Note-DE:` / `Release-Note: skip` commit
trailers — the same engine (`scripts/lib/release_notes_core.rb`) generates both
stores' text so they never disagree. The rules are written up once, in
`docs/ios/release-notes.md`.

The one difference is budget: Play allows **500** characters against the App
Store's 4000. The generator trims bullets until both locales fit and folds the
remainder into an "…and N smaller changes" line, so a busy release loses detail
rather than failing at upload.

Preview what the next release would say:

```bash
ruby android/scripts/release_notes.rb --print
```
