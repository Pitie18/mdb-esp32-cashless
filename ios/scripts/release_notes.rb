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

require "optparse"
require "fileutils"

APP_STORE_LIMIT = 4000       # App Store Connect hard limit for whatsNew
DEFAULT_MAX_BULLETS = 20
FALLBACK_COMMIT_COUNT = 40   # used only when no ios-v* tag exists yet
TAG_GLOB = "ios-v*"
# `:(top)` anchors the pathspec at the repository root. Without it the filter is
# relative to the working directory, and fastlane runs lanes from inside `ios/`
# — where a plain "ios/" matches nothing and every release ships empty notes.
PATHSPEC = ":(top)ios/"
PATHSPEC_LABEL = "ios/"

SECTION_ORDER = %i[new improved fixed].freeze

LOCALES = {
  "en-US" => {
    headings: { new: "New", improved: "Improved", fixed: "Fixed" },
    fallback: "Minor improvements and bug fixes.",
    more: ->(n) { "…and #{n} smaller #{n == 1 ? 'change' : 'changes'}." }
  },
  "de-DE" => {
    headings: { new: "Neu", improved: "Verbessert", fixed: "Behoben" },
    fallback: "Kleinere Verbesserungen und Fehlerbehebungen.",
    more: ->(n) { "…und #{n} weitere #{n == 1 ? 'Änderung' : 'Änderungen'}." }
  }
}.freeze

CONVENTIONAL = /\A(?<type>[a-z]+)(?:\((?<scope>[^)]*)\))?!?:\s*(?<subject>.+)\z/
TRAILER = /\A(?<key>Release-Notes?(?:-(?<loc>[A-Za-z]{2}))?):\s*(?<value>.*)\z/i
ANY_TRAILER = /\A[A-Za-z][A-Za-z0-9-]*:\s/
SKIP_VALUE = /\A(?:skip|none|-)\z/i

TYPE_BUCKET = {
  "feat" => :new, "feature" => :new,
  "fix" => :fixed, "bugfix" => :fixed, "hotfix" => :fixed,
  "perf" => :improved, "ui" => :improved, "ux" => :improved, "i18n" => :improved
}.freeze
DROPPED_TYPES = %w[
  chore ci build docs doc test tests style refactor deps dep release revert
].freeze

FIX_VERBS = %w[
  fix fixes fixed correct corrects prevent prevents stop stops resolve
  resolves repair repairs avoid avoids guard guards restore restores
].freeze
NEW_VERBS = %w[
  add adds added introduce introduces implement implements support supports
  enable enables create creates
].freeze
DROP_VERBS = %w[
  bump bumps document documents doc docs test tests refactor refactors merge
  revert reverts release releases chore lint format formats wip stamp
].freeze

# Subjects that describe build/release plumbing rather than anything a user can
# see. A `feat(ios):` prefix does not make a pbxproj change user-facing, so
# these win over the type mapping. For anything this misses, put
# `Release-Note: skip` in the commit body.
DROP_SUBJECT = [
  /stamp build version/i,
  /\Amerge /i,
  /\Aversion bump/i,
  /\b(?:CFBundle\w*|Info\.plist|pbxproj|xcodeproj|xcconfig|xcscheme|entitlements|
       Fastfile|Snapfile|Deliverfile|Appfile|fastlane|xcodegen|gitignore|
       Gemfile|podfile|workflow|codesign)\b/xi
].freeze

def git(*args)
  # external_encoding + scrub: commit subjects carry umlauts, and the system
  # Ruby on macOS still defaults to US-ASCII, which makes String#split blow up
  # on the first non-ASCII byte.
  out = IO.popen(["git", *args], err: File::NULL, external_encoding: Encoding::UTF_8, &:read)
  return "" unless $?.success?
  out.to_s.scrub
end

def repo_root
  root = git("rev-parse", "--show-toplevel").strip
  abort("release_notes: not inside a git repository") if root.empty?
  root
end

# Most recent ios-v* tag reachable from HEAD. `git describe` answers by
# topology (the tag an ancestor actually carries), which is what we want;
# the creatordate listing is a fallback for a detached/odd checkout.
def previous_tag
  tag = git("describe", "--tags", "--match", TAG_GLOB, "--abbrev=0", "HEAD").strip
  return tag unless tag.empty?
  git("tag", "--list", TAG_GLOB, "--sort=-creatordate").lines.first.to_s.strip
end

def read_commits(since)
  args = ["log", "--no-merges", "--format=%H\x1f%s\x1f%b\x1e"]
  if since && !since.empty?
    args << "#{since}..HEAD"
  else
    args << "--max-count=#{FALLBACK_COMMIT_COUNT}"
  end
  args += ["--", PATHSPEC]

  git(*args).split("\x1e").map do |record|
    sha, subject, body = record.strip.split("\x1f", 3)
    next if sha.nil? || subject.to_s.strip.empty?
    { sha: sha, subject: subject.strip, body: body.to_s }
  end.compact
end

# Collect `Release-Note[-XX]:` trailers, keyed by lowercase locale prefix
# ("*" for the unsuffixed form). Values may wrap onto following lines.
def parse_trailers(body)
  found = {}
  current = nil
  body.each_line do |raw|
    line = raw.rstrip
    if (m = TRAILER.match(line))
      current = (m[:loc] || "*").downcase
      found[current] = m[:value].strip
    elsif current && !line.strip.empty? && !ANY_TRAILER.match?(line)
      found[current] = [found[current], line.strip].reject(&:empty?).join(" ")
    else
      current = nil
    end
  end
  found
end

def classify(subject)
  if (m = CONVENTIONAL.match(subject))
    type = m[:type].downcase
    return [nil, nil] if DROPPED_TYPES.include?(type)
    return [TYPE_BUCKET.fetch(type, :improved), m[:subject].strip]
  end

  verb = subject[/\A\p{Word}+/].to_s.downcase
  return [nil, nil] if DROP_VERBS.include?(verb)
  return [:fixed, subject] if FIX_VERBS.include?(verb)
  return [:new, subject] if NEW_VERBS.include?(verb)
  [:improved, subject]
end

# "Fix" under a "Fixed" heading and "Add" under "New" are noise; drop the verb
# so free-form and conventional commits read the same in the final list.
def strip_redundant_verb(subject, bucket)
  case bucket
  when :fixed then subject.sub(/\A(?:fix(?:es|ed)?|resolves?|corrects?)\s+/i, "")
  when :new   then subject.sub(/\A(?:adds?|added|introduces?|implements?)\s+/i, "")
  else subject
  end
end

def capitalize_first(text)
  return text if text.empty?
  first_word = text.split(/\s/, 2).first.to_s
  # Leave iOS / iPad / macOS / eSIM alone — upcasing mangles them.
  return text if first_word =~ /\A\p{Ll}+\p{Lu}/
  text.sub(/\A(\p{Ll})/) { ::Regexp.last_match(1).upcase }
end

def humanize(text, bucket)
  s = text.to_s.strip
  s = s.sub(/\A\[[^\]]+\]\s*/, "")          # "[iOS] foo"
  s = s.sub(/\s*\(\s*ios\s*\)\s*\z/i, "")   # "foo (iOS)"
  s = strip_redundant_verb(s, bucket)
  s = s.sub(/\AiOS\s+/, "")                 # "iOS refill: …" — the app is iOS
  s = s.gsub(/\s+/, " ").sub(/[.\s]+\z/, "")
  capitalize_first(s)
end

def build_entries(commits)
  seen = {}
  entries = []

  commits.each do |commit|
    subject = commit[:subject]
    next if DROP_SUBJECT.any? { |re| re.match?(subject) }

    trailers = parse_trailers(commit[:body])
    next if trailers.values.any? { |v| SKIP_VALUE.match?(v.to_s) }

    bucket, cleaned = classify(subject)
    next if bucket.nil?

    text = LOCALES.keys.to_h do |locale|
      lang = locale.split("-").first.downcase
      explicit = trailers[lang] || trailers["*"]
      value = explicit ? explicit.strip.sub(/[.\s]+\z/, "") : humanize(cleaned, bucket)
      [locale, value]
    end
    next if text.values.any?(&:empty?)

    key = [bucket, text["en-US"].downcase]
    next if seen[key]
    seen[key] = true

    entries << { bucket: bucket, text: text }
  end

  entries
end

def render(entries, locale, overflow: 0)
  cfg = LOCALES.fetch(locale)
  blocks = SECTION_ORDER.map do |bucket|
    items = entries.select { |e| e[:bucket] == bucket }
    next if items.empty?
    ([cfg[:headings][bucket]] + items.map { |e| "• #{e[:text][locale]}" }).join("\n")
  end.compact
  return cfg[:fallback] if blocks.empty?
  blocks << cfg[:more].call(overflow) if overflow.positive?
  blocks.join("\n\n")
end

# Trim until every locale fits App Store Connect's 4000-character limit. The
# bullet set stays identical across locales so the listings match.
def fit(entries, max_bullets)
  kept = entries.first(max_bullets)
  overflow = entries.length - kept.length

  loop do
    rendered = LOCALES.keys.to_h { |loc| [loc, render(kept, loc, overflow: overflow)] }
    return [rendered, kept.length, overflow] if rendered.values.all? { |t| t.length <= APP_STORE_LIMIT }
    break if kept.empty?
    kept = kept[0..-2]
    overflow = entries.length - kept.length
  end

  [LOCALES.keys.to_h { |loc| [loc, LOCALES[loc][:fallback]] }, 0, entries.length]
end

def append_step_summary(rendered, range_label, commit_count, kept, overflow)
  path = ENV["GITHUB_STEP_SUMMARY"]
  return if path.to_s.empty?

  lines = ["### iOS release notes (auto-generated)", ""]
  lines << "Range: `#{range_label}` — #{commit_count} commit(s) touching `#{PATHSPEC_LABEL}`, " \
           "#{kept} bullet(s)#{overflow.positive? ? " (+#{overflow} folded into a summary line)" : ''}."
  rendered.each do |locale, text|
    lines += ["", "**#{locale}**", "", "```", text, "```"]
  end
  File.open(path, "a") { |f| f.puts(lines.join("\n")) }
end

options = { print_only: false, max: DEFAULT_MAX_BULLETS }
OptionParser.new do |opts|
  opts.banner = "Usage: release_notes.rb [options]"
  opts.on("--since REF", "Diff against REF instead of the last #{TAG_GLOB} tag") { |v| options[:since] = v }
  opts.on("--max N", Integer, "Maximum bullets (default #{DEFAULT_MAX_BULLETS})") { |v| options[:max] = v }
  opts.on("--print", "Print only; do not write the metadata files") { options[:print_only] = true }
  opts.on("-h", "--help") { puts(opts); exit(0) }
end.parse!

root = repo_root
since = options[:since] || previous_tag
range_label = since.to_s.empty? ? "last #{FALLBACK_COMMIT_COUNT} commits" : "#{since}..HEAD"

commits = read_commits(since)
entries = build_entries(commits)
rendered, kept, overflow = fit(entries, options[:max])

warn("release_notes: range #{range_label} — #{commits.length} commit(s), #{kept} bullet(s)" \
     "#{overflow.positive? ? ", #{overflow} folded" : ''}")

# An empty range means "nothing new since the last release". Overwriting the
# committed notes with the generic fallback in that case would clobber the real
# notes of the version that is already live, so leave the files alone.
if entries.empty? && !options[:print_only]
  warn("release_notes: no user-facing commits in #{range_label} — keeping the committed notes")
  options[:print_only] = true
end

rendered.each do |locale, text|
  warn("--- #{locale} ---")
  warn(text)
  next if options[:print_only]

  dir = File.join(root, "ios", "fastlane", "metadata", locale)
  FileUtils.mkdir_p(dir)
  File.write(File.join(dir, "release_notes.txt"), "#{text}\n", encoding: Encoding::UTF_8)
end

append_step_summary(rendered, range_label, commits.length, kept, overflow)
