#!/usr/bin/env ruby
# frozen_string_literal: true

# Generates the localized App Store "What's New" text for VMflow from git
# history and writes it to ios/fastlane/metadata/<locale>/release_notes.txt,
# which is what `deliver` uploads. Called by the `release` lane; also runnable
# on its own to preview what a release would ship:
#
#   ruby ios/scripts/release_notes.rb --print
#   ruby ios/scripts/release_notes.rb --since ios-v1.0.260801-1750
#
# Range: commits touching `ios/` between the most recent reachable `ios-v*` tag
# and HEAD. That tag is pushed by the release workflow after a successful
# submission, so each run covers exactly what shipped since the last one. With
# no such tag (first release) it falls back to the last 40 ios/ commits — and
# App Store Connect rejects "What's New" on a first version anyway, so deliver
# skips the field entirely there.
#
# Bucketing: conventional-commit types where present (feat -> New, fix -> Fixed,
# perf/ui/ux/i18n -> Improved, chore/ci/docs/test/style/refactor/build dropped),
# otherwise the leading verb decides. Anything unrecognised lands in Improved
# rather than being dropped, so nothing user-facing disappears silently.
#
# LANGUAGE CAVEAT: commit subjects are English, so de-DE gets German headings
# with English bullets unless the commit carries an explicit trailer. Write
# proper German (and better English) per commit like this:
#
#     fix(ios): stop deducting warehouse stock for unpacked products
#
#     Release-Note-EN: Skipping a product while packing no longer books it out
#     of the warehouse.
#     Release-Note-DE: Übersprungene Produkte werden beim Packen nicht mehr vom
#     Lagerbestand abgezogen.
#
# Trailers may wrap across lines (they end at a blank line or the next
# `Key: value` trailer). `Release-Note: skip` drops the commit from the notes
# entirely; a bare `Release-Note:` applies to every locale.
#
# All of the above is implemented in scripts/lib/release_notes_core.rb and
# shared with android/scripts/release_notes.rb, so both stores describe a
# release in exactly the same words. Only the framing below is iOS-specific.

require_relative "../../scripts/lib/release_notes_core"

CONFIG = ReleaseNotesCore::Config.new(
  label: "iOS",
  tag_glob: "ios-v*",
  # `:(top)` anchors the pathspec at the repository root. Without it the filter
  # is relative to the working directory, and fastlane runs lanes from inside
  # `ios/` — where a plain "ios/" matches nothing and every release ships empty
  # notes.
  pathspec: ":(top)ios/",
  pathspec_label: "ios/",
  char_limit: 4000,          # App Store Connect hard limit for whatsNew
  default_max_bullets: 20,
  # Xcode-side build plumbing; the cross-platform words (fastlane, Gemfile, …)
  # come from the shared core.
  plumbing_words: %w[
    CFBundle\w* Info\.plist pbxproj xcodeproj xcconfig xcscheme entitlements
    Snapfile Deliverfile xcodegen podfile codesign
  ],
  prefix_strip: /\AiOS\s+/,
  paren_suffix: /\s*\(\s*ios\s*\)\s*\z/i,
  output_path: ->(root, locale) { File.join(root, "ios", "fastlane", "metadata", locale, "release_notes.txt") }
)

ReleaseNotesCore.run(CONFIG)
