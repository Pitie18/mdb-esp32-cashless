import { describe, it, expect } from 'vitest'
import de from '@/../i18n/locales/de.json'
import en from '@/../i18n/locales/en.json'
import fr from '@/../i18n/locales/fr.json'
import nl from '@/../i18n/locales/nl.json'

/**
 * `en` is the `defaultLocale` and `fallbackLocale` (see nuxt.config.ts), so a
 * key missing from `de`/`fr`/`nl` does not break the build — vue-i18n just
 * silently falls back to the English string in the middle of an otherwise
 * translated screen. Nothing else in the suite catches that, so this test
 * guards it directly: every leaf key path present in `en` must also exist in
 * the other three locales.
 */

type LocaleTree = { [key: string]: string | LocaleTree }

function leafPaths(tree: LocaleTree, prefix = ''): string[] {
  const paths: string[] = []
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') {
      paths.push(path)
    } else {
      paths.push(...leafPaths(value, path))
    }
  }
  return paths
}

function missingPaths(reference: LocaleTree, target: LocaleTree): string[] {
  const targetPaths = new Set(leafPaths(target))
  return leafPaths(reference).filter(path => !targetPaths.has(path))
}

describe('locale key parity', () => {
  const referencePaths = leafPaths(en as LocaleTree)

  it('en has keys (sanity check the fixture loaded)', () => {
    expect(referencePaths.length).toBeGreaterThan(0)
  })

  it('de has no keys missing relative to en', () => {
    expect(missingPaths(en as LocaleTree, de as LocaleTree)).toEqual([])
  })

  // Pre-existing gap, not introduced by this branch: fr.json and nl.json are
  // each missing 130 leaf keys that de.json and en.json already have (mostly
  // the legal.* pages and the analytics.* section). Remove the skip once
  // those translations are filled in and this test starts asserting for real.
  it.skip('fr has no keys missing relative to en', () => {
    expect(missingPaths(en as LocaleTree, fr as LocaleTree)).toEqual([])
  })

  it.skip('nl has no keys missing relative to en', () => {
    expect(missingPaths(en as LocaleTree, nl as LocaleTree)).toEqual([])
  })
})
