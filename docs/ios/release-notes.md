# iOS release notes — how the "What's New" text is produced

`ios/scripts/release_notes.rb` writes
`ios/fastlane/metadata/<locale>/release_notes.txt` from git history. The
`release` lane runs it before uploading, because deliver pushes metadata ahead
of the binary.

Preview what the next release would say, without building or uploading anything:

```bash
ruby ios/scripts/release_notes.rb --print
```

or in CI: Actions → **iOS Release** → lane `release_notes` (needs no secrets;
the result lands in the job summary).

## What goes in

**Range** — commits between the most recent `ios-v*` tag reachable from HEAD and
HEAD, limited to commits touching `ios/`. The release workflow pushes that tag
after a successful submission, so each release covers exactly what shipped since
the previous one. With no tag at all it falls back to the last 40 `ios/`
commits — and App Store Connect rejects "What's New" on a first version anyway,
so deliver skips the field entirely there.

**Buckets** — conventional-commit types decide where a line goes:

| type | section |
|------|---------|
| `feat`, `feature` | New / Neu |
| `fix`, `bugfix`, `hotfix` | Fixed / Behoben |
| `perf`, `ui`, `ux`, `i18n` | Improved / Verbessert |
| `chore`, `ci`, `build`, `docs`, `test`, `style`, `refactor`, `deps`, `revert` | dropped |

Commits without a conventional prefix are bucketed by their leading verb
(`Fix …` → Fixed, `Add …` → New, `Bump …`/`Document …` → dropped). Anything
unrecognised lands in *Improved* rather than being dropped, so nothing
user-facing disappears silently. Subjects naming build plumbing (`CFBundle…`,
`pbxproj`, `Fastfile`, `xcconfig`, …) are dropped regardless of type.

Bullets are capped at 20; the rest collapse into a "…and N smaller changes"
line. Both locales get the same bullet set, and each is trimmed to App Store
Connect's 4000-character limit.

## Writing better lines: commit trailers

Commit subjects are English, so **de-DE gets German headings with English
bullets** unless you say otherwise. Override per commit:

```
fix(ios): stop deducting warehouse stock for unpacked products

Release-Note-EN: Skipping a product while packing no longer books it out
of the warehouse.
Release-Note-DE: Übersprungene Produkte werden beim Packen nicht mehr vom
Lagerbestand abgezogen.
```

- Values may wrap across lines; a trailer ends at a blank line or the next
  `Key: value` line (so `Co-Authored-By:` terminates it cleanly).
- A bare `Release-Note:` applies to every locale.
- `Release-Note: skip` keeps the commit out of the notes entirely — use it for
  anything internal that the type/verb rules would otherwise let through.

## Where the text lives

The generated files are committed back to the branch after a successful release
(`chore(ios): release notes for …`, which the next run drops as a `chore`). That
matters because the `metadata` lane uploads whatever is committed — without the
write-back, a later metadata-only run would replace the live notes with stale
ones.

For the same reason the generator **never writes when the range is empty**: it
leaves the committed notes alone rather than replacing a real changelog with the
generic "Minor improvements and bug fixes." fallback.

To hand-write a release's notes: edit the two `release_notes.txt` files, commit
them, and dispatch `release` with `notes: keep`.
