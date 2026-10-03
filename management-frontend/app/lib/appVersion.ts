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
