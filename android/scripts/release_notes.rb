#!/usr/bin/env ruby
# frozen_string_literal: true

# Generates the localized Play Store "What's new" text for VMflow from git
# history and writes it to
# android/fastlane/metadata/android/<locale>/changelogs/default.txt, which is
# what `supply` uploads. Called by the `release` lane; also runnable on its own
# to preview what a release would ship:
#
#   ruby android/scripts/release_notes.rb --print
#   ruby android/scripts/release_notes.rb --since android-v2.0.260810-26081002
#
# Range: commits touching `android/` between the most recent reachable
# `android-v*` tag and HEAD. That tag is pushed by the release workflow after a
# successful production upload, so each run covers exactly what shipped since
# the last one. With no such tag (first release) it falls back to the last 40
# android/ commits.
#
# The rules for bucketing, wording and the `Release-Note-DE:` / `Release-Note:
# skip` commit trailers are shared with iOS and documented in
# docs/ios/release-notes.md — they apply here verbatim, with `android-v*` tags
# and the `android/` path filter. The engine itself lives in
# scripts/lib/release_notes_core.rb.
#
# WHY `changelogs/default.txt` AND NOT `changelogs/<versionCode>.txt`: supply
# prefers a file named after the versionCode being uploaded and falls back to
# `default.txt`. The versionCode is derived from the build date and the day's
# commit count (see android/app/build.gradle), so it isn't known until Gradle
# runs — after the notes have already been written. `default.txt` always
# matches the build it ships with; a versionCode-named file would either be
# stale or require guessing the number twice.

require_relative "../../scripts/lib/release_notes_core"

CONFIG = ReleaseNotesCore::Config.new(
  label: "Android",
  tag_glob: "android-v*",
  # `:(top)` anchors the pathspec at the repository root. Without it the filter
  # is relative to the working directory, and fastlane runs lanes from inside
  # `android/` — where a plain "android/" matches nothing and every release
  # would ship empty notes.
  pathspec: ":(top)android/",
  pathspec_label: "android/",
  # Play's hard limit for a changelog is 500 characters — an eighth of what App
  # Store Connect allows. The shared `fit` trims bullets until both locales are
  # under it, so a busy release degrades to fewer bullets plus an "…and N more"
  # line rather than being rejected at upload.
  char_limit: 500,
  default_max_bullets: 8,
  # Gradle-side build plumbing; the cross-platform words (fastlane, Gemfile, …)
  # come from the shared core.
  plumbing_words: %w[
    build\.gradle gradle proguard keystore versionCode versionName
    AndroidManifest signingConfig Supplyfile aab r8 minify
  ],
  prefix_strip: /\AAndroid\s+/,
  paren_suffix: /\s*\(\s*android\s*\)\s*\z/i,
  output_path: lambda { |root, locale|
    File.join(root, "android", "fastlane", "metadata", "android", locale, "changelogs", "default.txt")
  }
)

ReleaseNotesCore.run(CONFIG)
