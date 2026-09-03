#!/usr/bin/env ruby
# frozen_string_literal: true

# Plain-assertion tests for the shared release-notes engine. No gems, no
# harness — run it directly:
#
#   ruby scripts/lib/release_notes_core_test.rb
#
# Covers the rules that decide what a store listing says. The engine reads git,
# but every function under test here is pure, so the tests build commit hashes
# by hand instead of touching the repository.

require_relative "release_notes_core"

FAILURES = []
RUN = []

def check(name)
  ok = yield
  RUN << name
  FAILURES << name unless ok
  puts(format("%-4s %s", ok ? "ok" : "FAIL", name))
end

def commit(subject, body = "")
  { sha: "0" * 40, subject: subject, body: body }
end

IOS = ReleaseNotesCore::Config.new(
  label: "iOS", tag_glob: "ios-v*", pathspec: ":(top)ios/", pathspec_label: "ios/",
  char_limit: 4000, default_max_bullets: 20,
  plumbing_words: %w[
    CFBundle\w* Info\.plist pbxproj xcodeproj xcconfig xcscheme entitlements
    Snapfile Deliverfile xcodegen podfile codesign
  ],
  prefix_strip: /\AiOS\s+/, paren_suffix: /\s*\(\s*ios\s*\)\s*\z/i,
  output_path: ->(root, locale) { File.join(root, "ios", locale) }
)

ANDROID = ReleaseNotesCore::Config.new(
  label: "Android", tag_glob: "android-v*", pathspec: ":(top)android/", pathspec_label: "android/",
  char_limit: 500, default_max_bullets: 8,
  plumbing_words: %w[
    build\.gradle gradle proguard keystore versionCode versionName
    AndroidManifest signingConfig Supplyfile aab r8 minify
  ],
  prefix_strip: /\AAndroid\s+/, paren_suffix: /\s*\(\s*android\s*\)\s*\z/i,
  output_path: ->(root, locale) { File.join(root, "android", locale) }
)

# --- plumbing filter --------------------------------------------------------
# This is the regex the iOS script carried before the shared-core refactor.
# Keeping it here verbatim means a future edit to SHARED_PLUMBING_WORDS or to
# the iOS word list that changes behaviour shows up as a failure, not as a
# surprise in a release listing.
LEGACY_IOS_DROP = [
  /stamp build version/i,
  /\Amerge /i,
  /\Aversion bump/i,
  /\b(?:CFBundle\w*|Info\.plist|pbxproj|xcodeproj|xcconfig|xcscheme|entitlements|
       Fastfile|Snapfile|Deliverfile|Appfile|fastlane|xcodegen|gitignore|
       Gemfile|podfile|workflow|codesign)\b/xi
].freeze

LEGACY_SUBJECTS = [
  "feat(ios): bump CFBundleVersion for the release",
  "fix(ios): correct CFBundleShortVersionString",
  "chore: touch Info.plist",
  "feat: regenerate pbxproj",
  "feat(ios): add a real feature",
  "ci: update the release workflow",
  "fix: tidy the Fastfile",
  "feat: rework codesign settings",
  "fix(ios): stop double-deducting stock",
  "Merge branch main",
  "chore: update gitignore",
  "feat(ios): stamp build version",
  "feat: adopt a new xcconfig",
  "fix: repair the Deliverfile"
].freeze

check("iOS plumbing filter matches the pre-refactor regex exactly") do
  LEGACY_SUBJECTS.all? do |s|
    LEGACY_IOS_DROP.any? { |re| re.match?(s) } == IOS.drop_subject.any? { |re| re.match?(s) }
  end
end

check("CFBundle\\w* still matches suffixed keys (the %w backslash survives)") do
  IOS.drop_subject.any? { |re| re.match?("feat(ios): bump CFBundleVersion") } &&
    IOS.drop_subject.any? { |re| re.match?("feat(ios): set CFBundleShortVersionString") }
end

check("Android drops Gradle plumbing that iOS never sees") do
  %w[
    feat:\ raise\ versionCode
    fix:\ correct\ the\ signingConfig
    feat:\ tune\ proguard\ rules
    chore:\ rotate\ the\ keystore
  ].map { |s| s.tr("\\", "") }
   .all? { |s| ANDROID.drop_subject.any? { |re| re.match?(s) } }
end

check("Android keeps a genuine feature that merely mentions a slot") do
  !ANDROID.drop_subject.any? { |re| re.match?("feat(android): show stock per tray slot") }
end

check("shared plumbing words apply to both platforms") do
  s = "chore: update the Fastfile"
  IOS.drop_subject.any? { |re| re.match?(s) } && ANDROID.drop_subject.any? { |re| re.match?(s) }
end

# --- bucketing --------------------------------------------------------------
check("conventional types map to their sections") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("feat(ios): add tray batch editing"),
    commit("fix(ios): stop losing the refill draft"),
    commit("perf(ios): speed up the dashboard"),
    commit("chore(ios): reorder imports")
  ])
  entries.map { |e| e[:bucket] } == %i[new fixed improved]
end

check("unprefixed subjects fall back to the leading verb") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("Fix a crash on launch"),
    commit("Add a warehouse filter"),
    commit("Bump the Kotlin version"),
    commit("Rework how tours are grouped")
  ])
  entries.map { |e| e[:bucket] } == %i[fixed new improved]
end

check("the redundant leading verb is dropped from the bullet") do
  entries = ReleaseNotesCore.build_entries(IOS, [commit("Fix a crash on launch")])
  entries.first[:text]["en-US"] == "A crash on launch"
end

check("duplicate bullets collapse to one") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("fix(ios): stop losing the refill draft"),
    commit("fix(ios): stop losing the refill draft")
  ])
  entries.length == 1
end

# --- trailers ---------------------------------------------------------------
check("Release-Note-DE / -EN override the generated wording per locale") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("fix(ios): stop deducting stock for unpacked products", <<~BODY)
      Release-Note-EN: Skipping a product no longer books it out of the warehouse.
      Release-Note-DE: Übersprungene Produkte werden nicht mehr abgezogen.
    BODY
  ])
  e = entries.first
  e[:text]["en-US"] == "Skipping a product no longer books it out of the warehouse" &&
    e[:text]["de-DE"] == "Übersprungene Produkte werden nicht mehr abgezogen"
end

check("a trailer value may wrap onto the next line") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("fix(ios): something", "Release-Note-EN: A wrapped\nsecond line.\n")
  ])
  entries.first[:text]["en-US"] == "A wrapped second line"
end

check("the next Key: value trailer terminates the previous one") do
  entries = ReleaseNotesCore.build_entries(IOS, [
    commit("fix(ios): something", "Release-Note-EN: Just this.\nCo-Authored-By: Someone <a@b.c>\n")
  ])
  entries.first[:text]["en-US"] == "Just this"
end

check("Release-Note: skip removes the commit entirely") do
  ReleaseNotesCore.build_entries(IOS, [
    commit("feat(ios): internal plumbing", "Release-Note: skip\n")
  ]).empty?
end

check("a bare Release-Note: applies to every locale") do
  e = ReleaseNotesCore.build_entries(IOS, [
    commit("feat(ios): whatever", "Release-Note: One text for both.\n")
  ]).first
  e[:text]["en-US"] == "One text for both" && e[:text]["de-DE"] == "One text for both"
end

# --- platform prefix stripping ---------------------------------------------
check("each platform strips only its own name") do
  ios = ReleaseNotesCore.build_entries(IOS, [commit("feat: iOS refill review step")]).first
  android = ReleaseNotesCore.build_entries(ANDROID, [commit("feat: Android refill review step")]).first
  ios[:text]["en-US"] == "Refill review step" && android[:text]["en-US"] == "Refill review step"
end

check("a trailing platform parenthetical is removed") do
  e = ReleaseNotesCore.build_entries(ANDROID, [commit("feat: tray batch editing (Android)")]).first
  e[:text]["en-US"] == "Tray batch editing"
end

check("mixed-case product names are not re-capitalized") do
  e = ReleaseNotesCore.build_entries(ANDROID, [commit("feat: iPad-style grid for slots")]).first
  e[:text]["en-US"] == "iPad-style grid for slots"
end

# --- trimming to the store's budget ----------------------------------------
def many_entries(config, count)
  ReleaseNotesCore.build_entries(config, Array.new(count) { |i| commit("feat: feature number #{i} with a reasonably long name") })
end

check("Play's 500-character budget is respected") do
  rendered, = ReleaseNotesCore.fit(ANDROID, many_entries(ANDROID, 40), ANDROID.default_max_bullets)
  rendered.values.all? { |t| t.length <= 500 } && rendered.values.none?(&:empty?)
end

check("the App Store's 4000-character budget is respected") do
  rendered, = ReleaseNotesCore.fit(IOS, many_entries(IOS, 200), IOS.default_max_bullets)
  rendered.values.all? { |t| t.length <= 4000 }
end

check("dropped bullets are counted in an overflow line, not silently lost") do
  rendered, kept, overflow = ReleaseNotesCore.fit(ANDROID, many_entries(ANDROID, 40), ANDROID.default_max_bullets)
  overflow == 40 - kept && overflow.positive? && rendered["de-DE"].include?("weitere")
end

check("an empty entry list renders the generic fallback") do
  rendered, = ReleaseNotesCore.fit(ANDROID, [], 8)
  rendered["en-US"] == "Minor improvements and bug fixes." &&
    rendered["de-DE"] == "Kleinere Verbesserungen und Fehlerbehebungen."
end

check("both locales keep the same number of bullets") do
  rendered, = ReleaseNotesCore.fit(ANDROID, many_entries(ANDROID, 40), ANDROID.default_max_bullets)
  rendered["en-US"].scan("•").length == rendered["de-DE"].scan("•").length
end

puts
if FAILURES.empty?
  puts "All #{RUN.length} checks passed."
  exit(0)
else
  puts "#{FAILURES.length} failure(s):"
  FAILURES.each { |f| puts "  - #{f}" }
  exit(1)
end
