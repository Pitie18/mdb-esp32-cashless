# Aushang: N-up-Bogen, Schnittlinien und lesbare QR-Codes — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poster lassen sich zu mehreren auf ein A4-Blatt drucken (2 × A5, 4 × A6, 8 × A7) mit gestrichelten Schnittlinien, und die zu kleinen QR-Codes auf den Aufklebern werden lesbar.

**Architecture:** Der bestehende Aufkleber-Bogen (`StickerSheet.vue` + `STICKER_LAYOUT`) ist bereits ein generischer Kachelbogen; er wird zu `TiledSheet.vue` + `TILE_LAYOUT` verallgemeinert statt dupliziert. Neu darin: gedrehte Kacheln, eine em-Basis pro Kachel und gestrichelte Schnittlinien. Die QR-Fehlerkorrektur richtet sich künftig nach der kleinsten QR-Größe des Formats (`MIN_QR_MM`) statt nach Papier vs. Aufkleber. Die harten mm-Untergrenzen in den Motiv-Styles werden zu CSS-Variablen, die das Blatt setzt.

**Tech Stack:** Nuxt 4, Vue 3 `<script setup>`, TypeScript, Vitest, `qrcode`.

Spec: `docs/superpowers/specs/2026-08-24-aushang-n-up-formate-design.md`

## Global Constraints

- **Keine DB-Migration.** `poster_printed` schreibt `format` als freien String; neue Werte fließen ohne Schemaänderung durch.
- **Ziel-URLs bleiben unverändert.** `?feedback=problem` nicht kürzen — das würde jeden bereits gedruckten Aushang im Feld ungültig machen.
- **A4/A5/A6 rendern nach dieser Änderung byte-identisch wie vorher.** Das ist eine Testbedingung, keine Absicht: `MIN_QR_MM` für `a4`/`a5`/`a6` ist 30, `PAD_MIN_MM` ist 5 — exakt die Werte, die heute fest im CSS stehen.
- **Blatt-Füllung bleibt:** eine Kachel pro ausgewählter Maschine, Rest des Blatts weiß. Kein Kopien-Feld.
- Arbeitsverzeichnis für alle Kommandos: `management-frontend/`.
- Tests laufen mit `npx vitest run app/lib/__tests__/printSheet.test.ts`.
- Alle neuen i18n-Schlüssel immer in **beiden** Dateien (`i18n/locales/de.json`, `i18n/locales/en.json`).
- **Keine Browser-Abnahme in diesem Durchlauf.** `/machines/[id]/print` liegt hinter dem Login, und diese Session hat keine Zugangsdaten. Alles, was sich rechnerisch prüfen lässt, wird als Test geprüft; was Augen braucht, steht gesammelt unter „Abnahme durch den Bediener" am Ende dieses Plans. Kein Task gilt als erledigt, weil ein optischer Schritt übersprungen wurde — er wird dort eingetragen.
- **Code-Kommentare auf Englisch.** Alle berührten Dateien sind durchgehend englisch kommentiert. Die Kommentare in den Codeblöcken dieses Plans sind deutsche Entwürfe — beim Umsetzen ins Englische übertragen, Aussage unverändert. Das gilt nicht für Prosa, Commit-Messages und i18n-Werte.
- **`npx nuxi typecheck` ist kein grünes Gate.** Das Repo trägt rund 192 vorbestehende Typfehler (fehlende `database.types.ts`, siehe CLAUDE.md). Die Bedingung lautet: keine *neuen* Fehler in den berührten Dateien. Zählstand vorher und nachher vergleichen, z. B. über `git stash`.

---

### Task 1: QR-Fehlerkorrektur an der Kachelgröße ausrichten

Heute entscheidet `qrErrorLevel` „Aufkleber → Q, Poster → M" und begründet das mit Abrieb. Die Begründung geht am Problem vorbei: mehr Fehlerkorrektur heißt mehr Module auf gleicher Fläche. Auf einem 13-mm-Code macht Q ihn **unlesbarer**, nicht robuster. Die Stufe wird deshalb aus `MIN_QR_MM` abgeleitet.

**Files:**
- Modify: `app/lib/printSheet.ts:169-181` (`MIN_QR_MM`), `app/lib/printSheet.ts:206-208` (`qrErrorLevel`)
- Test: `app/lib/__tests__/printSheet.test.ts:536-542`

**Interfaces:**
- Consumes: nichts.
- Produces: `qrErrorLevel(format: PrintFormat): 'L' | 'M'` (vorher `'M' | 'Q'`). `MIN_QR_MM['a6']` wird von 25 auf 30 korrigiert.

- [ ] **Step 1: Bestehenden Test auf das neue Verhalten umschreiben**

In `app/lib/__tests__/printSheet.test.ts` den Block `describe('qrErrorLevel', …)` (Zeile 536–542) komplett ersetzen durch:

```ts
describe('qrErrorLevel', () => {
  // Mehr Fehlerkorrektur heisst mehr Module auf gleicher Flaeche. Unter etwa
  // 0,5 mm Modulgroesse ist ein Code unlesbar, egal wie viel Redundanz er
  // traegt — kleine Formate brauchen deshalb weniger, nicht mehr.
  it('drops redundancy where the code is small', () => {
    expect(qrErrorLevel('sticker-sheet')).toBe('L')
    expect(qrErrorLevel('sticker-sheet-small')).toBe('L')
    expect(qrErrorLevel('sticker-sheet-strip')).toBe('L')
  })

  it('keeps the print standard where there is room', () => {
    expect(qrErrorLevel('a4')).toBe('M')
    expect(qrErrorLevel('a5')).toBe('M')
    expect(qrErrorLevel('a6')).toBe('M')
  })

  it('derives the level from the format QR floor', () => {
    for (const format of Object.keys(MIN_QR_MM) as PrintFormat[]) {
      expect(qrErrorLevel(format)).toBe(MIN_QR_MM[format] < 25 ? 'L' : 'M')
    }
  })
})
```

Im Import-Block oben in der Datei `MIN_QR_MM` ergänzen (die Liste ist alphabetisch, es gehört zwischen `isStickerFormat` und `normalizeCustomUrl`), und den Typ-Import ergänzen:

```ts
import type { PrintFormat } from '@/lib/printSheet'
```

Falls die Datei bereits einen `import type`-Block für `printSheet` hat, `PrintFormat` dort einreihen statt eine zweite Zeile anzulegen.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts -t qrErrorLevel`
Expected: FAIL — `expected 'Q' to be 'L'`

- [ ] **Step 3: `MIN_QR_MM` korrigieren und `qrErrorLevel` umschreiben**

In `app/lib/printSheet.ts` den Eintrag `a6: 25` in `MIN_QR_MM` auf `30` ändern. Der Wert 25 war nie wirksam — die Motive erzwingen im CSS ohnehin `max(30mm, …)`, und 30 hier festzuhalten ist die Voraussetzung dafür, dass A6 nach Task 5 unverändert rendert:

```ts
export const MIN_QR_MM: Record<PrintFormat, number> = {
  a4: 30,
  a5: 30,
  // Was die Motive im CSS ohnehin erzwingen. Der frühere Wert 25 war tote
  // Dokumentation und würde A6 beim Umstieg auf --qr-min stillschweigend
  // verkleinern.
  a6: 30,
  'sticker-sheet': 20,
  'sticker-sheet-small': 16,
  'sticker-sheet-strip': 22,
}
```

`qrErrorLevel` ersetzen:

```ts
/**
 * QR-Fehlerkorrektur. Nicht Papier gegen Vinyl, sondern Fläche: höhere
 * Redundanz erhöht die Modulzahl, und ein Symbol, dessen Module unter etwa
 * 0,5 mm fallen, ist unlesbar — egal wie viel Redundanz es trägt. Kleine
 * Formate bekommen deshalb weniger Fehlerkorrektur, nicht mehr.
 */
export function qrErrorLevel(format: PrintFormat): 'L' | 'M' {
  return MIN_QR_MM[format] < 25 ? 'L' : 'M'
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts`
Expected: PASS, alle Blöcke grün.

- [ ] **Step 5: Typecheck**

Run: `npx nuxi typecheck`
Expected: keine *neuen* Fehler in `printSheet.ts` (siehe Global Constraints — das Repo trägt rund 192 vorbestehende). `useMachinePrint.ts:178` reicht das Ergebnis an `QRCode.toString({ errorCorrectionLevel })` weiter, das `'L'` akzeptiert.

- [ ] **Step 6: Commit**

```bash
git add app/lib/printSheet.ts app/lib/__tests__/printSheet.test.ts
git commit -m "fix(print): derive QR error level from the format's QR size, not from paper type"
```

---

### Task 2: Zu kleine QR-Codes auf den Aufklebern vergrößern

Der rechte Code des Duo-Aufklebers liegt bei 13 mm und Level Q bei 0,245 mm pro Modul — mit Punktzuwachs eines Büro-Druckers ist das Matsch. Task 1 hebt ihn auf 0,317 mm; das reicht noch nicht. Er ist doppelt bestraft: halb so groß wie der linke **und** mit der längeren `?feedback=problem`-URL belegt.

Platzrechnung Duo (90 × 50 mm, Padding 3 mm → 84 × 44 mm nutzbar): links 26 mm QR, dazu 2,5 mm Lücke + 0,2 mm Trennlinie + 2,5 mm Lücke = 31,2 mm. Für den rechten Block bleiben 52,8 mm; ein 22-mm-Code lässt dort 29 mm für Text. In der Höhe: 22 mm QR plus Telefonzeile ≈ 25 mm von 44 mm.

Mini (50 × 30 mm, Padding 2 mm → 46 × 26 mm nutzbar): 20 mm QR passt in die Höhe und lässt 24 mm für Titel und Telefonnummer.

**Files:**
- Modify: `app/components/print/StickerDuo.vue:55`
- Modify: `app/components/print/StickerMini.vue:37`

**Interfaces:**
- Consumes: `qrErrorLevel` aus Task 1 (Level L für alle Aufkleber-Bogen).
- Produces: nichts für spätere Tasks.

- [ ] **Step 1: Duo-Aufkleber — rechten Code auf 22 mm**

In `app/components/print/StickerDuo.vue` Zeile 55 ersetzen:

```css
.qr-side { width: 13mm; height: 13mm; flex: none; }
```

durch:

```css
/* 22 mm, nicht kleiner: die Störungs-URL ist die längste, die dieser
   Aufkleber trägt, und darunter fällt die Modulgröße unter 0,5 mm. */
.qr-side { width: 22mm; height: 22mm; flex: none; }
```

- [ ] **Step 2: Mini-Aufkleber — Code auf 20 mm**

Die Spec nennt hier 18 mm unter Vorbehalt („falls das Layout es hergibt"). Es gibt mehr her: bei 2 mm Padding sind 26 mm Höhe verfügbar, und 20 mm QR lassen immer noch 24 mm für Titel und Telefonnummer. 20 mm statt 18 mm ist der Unterschied zwischen 0,439 und 0,488 mm pro Modul.

In `app/components/print/StickerMini.vue` Zeile 37 ersetzen:

```css
.qr { width: 16mm; height: 16mm; flex: none; }
```

durch:

```css
/* 20 mm ist, was 50 x 30 mm hergibt: der Rest der Breite trägt Titel und
   Telefonnummer. Damit bleibt die Modulgröße knapp unter 0,5 mm — die
   physikalische Grenze dieses Formats, nicht ein Layout-Versehen. */
.qr { width: 20mm; height: 20mm; flex: none; }
```

- [ ] **Step 3: Modulgrößen nachrechnen**

Run:

```bash
node -e "
const QRCode = require('qrcode');
const t = 'https://app.vmflow.de/m/3f8b1c92-7d4e-4a11-9c53-8e2a6b0f14d7?feedback=problem';
const modules = QRCode.create(t, { errorCorrectionLevel: 'L' }).modules.size + 8;
for (const [name, mm] of [['Duo rechts', 22], ['Duo links', 26], ['Mini', 20]])
  console.log(name.padEnd(12), (mm / modules).toFixed(3), 'mm/Modul');
"
```

Expected:
```
Duo rechts   0.537 mm/Modul
Duo links    0.634 mm/Modul
Mini         0.488 mm/Modul
```

- [ ] **Step 4: Platzbedarf rechnerisch prüfen**

Der optische Abgleich geht in diesem Durchlauf nicht (siehe Global Constraints) und steht unter „Abnahme durch den Bediener". Rechnerisch nachweisen, dass der größere Code passt:

Duo, Breite: 90 − 2 × 3 (Padding) = 84 mm nutzbar. Belegt: 26 (linker QR) + 2,5 (Lücke) + 0,2 (Trennlinie) + 2,5 (Lücke) + 22 (rechter QR) + 1,8 (Lücke) = 55 mm. Für `side-text` bleiben **29 mm**.
Duo, Höhe: 50 − 2 × 3 = 44 mm nutzbar; der rechte Block braucht 22 (QR) + ~1 + ~2,3 (Telefonzeile) ≈ **25,3 mm**.
Mini, Breite: 50 − 2 × 2 = 46 mm nutzbar. Belegt: 20 (QR) + 2 (Lücke) = 22 mm, für `.text` bleiben **24 mm**. Höhe: 30 − 4 = 26 mm nutzbar, QR braucht 20 mm.

Beide Zahlen im Report festhalten. Keine `font-size` prophylaktisch anpassen — ob `side-hint` umbricht, entscheidet die Abnahme.

- [ ] **Step 5: Commit**

```bash
git add app/components/print/StickerDuo.vue app/components/print/StickerMini.vue
git commit -m "fix(print): enlarge the cramped sticker QR codes to a scannable module size"
```

---

### Task 3: Vokabular von „Sticker" auf „Kachel" umstellen

Reiner Rename ohne Verhaltensänderung, damit die neuen Poster-Formate dieselbe Mechanik mitbenutzen können, ohne dass der Code von „Aufklebern" spricht, die Poster sind. Nach diesem Task muss die App exakt dasselbe tun wie vorher.

**Files:**
- Modify: `app/lib/printSheet.ts` (`StickerLayout`, `STICKER_LAYOUT`, `stickerLayout`, `stickersPerSheet`, `distributeStickers`)
- Modify: `app/components/print/StickerSheet.vue` (Aufrufstellen)
- Modify: `app/components/print/MotifThumb.vue:4,25-29`
- Modify: `app/pages/machines/[id]/print.vue:9,229-233`
- Test: `app/lib/__tests__/printSheet.test.ts`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `export interface TileLayout { w, h, gap, cols, rows: number; rotate: boolean; scaleToTile: boolean }`
  - `export const TILE_LAYOUT: Record<TiledFormat, TileLayout>`
  - `export function tileLayout(format: PrintFormat): TileLayout`
  - `export function tilesPerSheet(format: PrintFormat): number`
  - `export function distributeTiles<T>(items: T[], perSheet?: number): T[][]`
  - `isStickerFormat` und `StickerFormat` bleiben unverändert bestehen (sie unterscheiden weiterhin Aufkleber von Postern für den Galerie-Thumbnail).

- [ ] **Step 1: Tests auf die neuen Namen umschreiben**

In `app/lib/__tests__/printSheet.test.ts`:
- Im Import-Block `distributeStickers` → `distributeTiles`, `stickerLayout` → `tileLayout`, `stickersPerSheet` → `tilesPerSheet`.
- `describe('distributeStickers', …)` → `describe('distributeTiles', …)`, alle drei Aufrufe darin umbenennen.
- Im Block `describe('sticker sheet geometry', …)` alle `stickersPerSheet`/`stickerLayout`-Aufrufe umbenennen.

Keine Erwartungswerte ändern — dieser Task ändert kein Verhalten.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts`
Expected: FAIL — `distributeTiles is not a function` (bzw. Import-Fehler).

- [ ] **Step 3: `printSheet.ts` umbenennen**

- `export interface StickerLayout` → `export interface TileLayout`, ergänzt um zwei Felder:

```ts
export interface TileLayout {
  w: number
  h: number
  gap: number
  cols: number
  rows: number
  /**
   * Die Kachel liegt um 90° gedreht auf dem Blatt und belegt dort h × w.
   * Acht hochkante A7 passen sonst rechnerisch nicht auf A4.
   */
  rotate: boolean
  /**
   * Poster-Motive skalieren alles in `em` gegen die Blattbreite; in einer
   * Kachel muss die Basis von der Kachel kommen, sonst läuft A4-Text über
   * eine A6-Karte. Aufkleber-Motive sind auf die Blatt-Basis getrimmt und
   * behalten sie.
   */
  scaleToTile: boolean
}
```

- `STICKER_LAYOUT` → `TILE_LAYOUT`, jeder der drei bestehenden Einträge bekommt `rotate: false, scaleToTile: false`:

```ts
export const TILE_LAYOUT: Record<StickerFormat, TileLayout> = {
  'sticker-sheet': { w: 90, h: 50, gap: 3, cols: 2, rows: 4, rotate: false, scaleToTile: false },
  // For the coin return and the flap edge, where 90 x 50 simply does not fit.
  'sticker-sheet-small': { w: 50, h: 30, gap: 3, cols: 3, rows: 8, rotate: false, scaleToTile: false },
  // The long band that runs across a machine front, above or below the
  // product window. Two of these do not fit side by side on A4, so it is one
  // per row and six to a sheet.
  'sticker-sheet-strip': { w: 148, h: 40, gap: 3, cols: 1, rows: 6, rotate: false, scaleToTile: false },
}
```

- `stickerLayout` → `tileLayout`, `stickersPerSheet` → `tilesPerSheet`, `distributeStickers` → `distributeTiles`. Bodies unverändert, nur die Bezeichner und die internen Verweise auf `TILE_LAYOUT`.

- [ ] **Step 4: Aufrufstellen nachziehen**

Run:

```bash
grep -rn "stickerLayout\|stickersPerSheet\|distributeStickers\|STICKER_LAYOUT\|StickerLayout" app/
```

Erwartet werden Treffer in `app/components/print/StickerSheet.vue`, `app/components/print/MotifThumb.vue` und `app/pages/machines/[id]/print.vue`. Jeden auf den neuen Namen umstellen. `isStickerFormat` **nicht** anfassen.

- [ ] **Step 5: Tests und Typecheck**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts && npx nuxi typecheck`
Expected: Tests PASS, keine *neuen* Typfehler in den berührten Dateien, keine Treffer mehr aus Step 4.

- [ ] **Step 6: Commit**

```bash
git add app/lib/printSheet.ts app/lib/__tests__/printSheet.test.ts app/components/print/ "app/pages/machines/[id]/print.vue"
git commit -m "refactor(print): rename the sticker sheet vocabulary to tiles"
```

---

### Task 4: Die drei N-up-Formate und ihre Geometrie

**Files:**
- Modify: `app/lib/printSheet.ts` (`PrintFormat`, `FORMAT_MM`, `MIN_QR_MM`, `TILE_LAYOUT`, `isTiledFormat`, `tileBlockMm`)
- Test: `app/lib/__tests__/printSheet.test.ts`

**Interfaces:**
- Consumes: `TileLayout`, `TILE_LAYOUT`, `tileLayout`, `tilesPerSheet` aus Task 3; `MIN_QR_MM`, `qrErrorLevel` aus Task 1.
- Produces:
  - `PrintFormat` um `'a5-2up' | 'a6-4up' | 'a7-8up'` erweitert
  - `export type TiledFormat = StickerFormat | 'a5-2up' | 'a6-4up' | 'a7-8up'`
  - `export function isTiledFormat(format: PrintFormat): format is TiledFormat`
  - `export function tileBlockMm(format: PrintFormat): { w: number; h: number }` — Grundfläche des gesamten Kachelblocks auf dem Blatt, Drehung eingerechnet

Zielgeometrie (A4 = 210 × 297 mm, mindestens 6 mm Rand rundum, 4 mm Steg, Seitenverhältnis 1:√2):

| Format | Kachel gerendert | Raster | gedreht | Block auf dem Blatt |
|---|---|---|---|---|
| `a5-2up` | 139 × 196,5 mm | 1 × 2 | ja | 196,5 × 282 mm |
| `a6-4up` | 97 × 137 mm | 2 × 2 | nein | 198 × 278 mm |
| `a7-8up` | 68 × 96 mm | 2 × 4 | ja | 196 × 284 mm |

- [ ] **Step 1: Die Geometrie-Tests schreiben**

In `app/lib/__tests__/printSheet.test.ts` den Block `describe('sticker sheet geometry', …)` umbenennen in `describe('tiled sheet geometry', …)` und darin ergänzen (die bestehenden Erwartungen bleiben stehen):

```ts
  it('holds two A5, four A6 and eight A7 to an A4 sheet', () => {
    expect(tilesPerSheet('a5-2up')).toBe(2)
    expect(tilesPerSheet('a6-4up')).toBe(4)
    expect(tilesPerSheet('a7-8up')).toBe(8)
  })

  it('leaves at least 6 mm of margin on every tiled format', () => {
    for (const format of Object.keys(TILE_LAYOUT) as TiledFormat[]) {
      const block = tileBlockMm(format)
      expect(block.w).toBeLessThanOrEqual(210 - 12)
      expect(block.h).toBeLessThanOrEqual(297 - 12)
    }
  })

  it('accounts for rotation when measuring the block', () => {
    // Acht hochkante A7 passen nicht auf A4 — die Kachel liegt quer, der
    // Block ist also so breit wie die Kachel hoch ist.
    const l = tileLayout('a7-8up')
    expect(l.rotate).toBe(true)
    expect(tileBlockMm('a7-8up').w).toBe(l.cols * l.h + (l.cols - 1) * l.gap)
    expect(tileBlockMm('a7-8up').h).toBe(l.rows * l.w + (l.rows - 1) * l.gap)
  })

  it('keeps every tile close to the A-series ratio', () => {
    for (const format of ['a5-2up', 'a6-4up', 'a7-8up'] as const) {
      const { w, h } = tileLayout(format)
      expect(h / w).toBeGreaterThan(1.39)
      expect(h / w).toBeLessThan(1.44)
    }
  })

  it('rescales the em base for poster tiles but not for stickers', () => {
    expect(tileLayout('a6-4up').scaleToTile).toBe(true)
    expect(tileLayout('sticker-sheet').scaleToTile).toBe(false)
  })

  it('prints n-up posters on A4 paper', () => {
    for (const format of ['a5-2up', 'a6-4up', 'a7-8up'] as const) {
      expect(FORMAT_MM[format]).toEqual({ w: 210, h: 297 })
    }
  })

  it('does not mistake an n-up poster for a sticker', () => {
    for (const format of ['a5-2up', 'a6-4up', 'a7-8up'] as const) {
      expect(isStickerFormat(format)).toBe(false)
      expect(isTiledFormat(format)).toBe(true)
    }
    expect(isTiledFormat('a4')).toBe(false)
    expect(isTiledFormat('sticker-sheet')).toBe(true)
  })

  it('drops the error level for A7 tiles, keeps it for A6', () => {
    expect(qrErrorLevel('a7-8up')).toBe('L')
    expect(qrErrorLevel('a6-4up')).toBe('M')
    expect(qrErrorLevel('a5-2up')).toBe('M')
  })

  it('describes every format exactly once', () => {
    const formats: PrintFormat[] = [
      'a4', 'a5', 'a6', 'a5-2up', 'a6-4up', 'a7-8up',
      'sticker-sheet', 'sticker-sheet-small', 'sticker-sheet-strip',
    ]
    for (const format of formats) {
      expect(FORMAT_MM[format]).toBeDefined()
      expect(MIN_QR_MM[format]).toBeDefined()
    }
  })
```

Import-Block ergänzen um `FORMAT_MM`, `TILE_LAYOUT`, `isTiledFormat`, `tileBlockMm` und den Typ `TiledFormat`.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts`
Expected: FAIL — `isTiledFormat is not a function`

- [ ] **Step 3: Formate hinzufügen**

In `app/lib/printSheet.ts`:

```ts
export type PrintFormat =
  | 'a4' | 'a5' | 'a6'
  | 'a5-2up' | 'a6-4up' | 'a7-8up'
  | 'sticker-sheet' | 'sticker-sheet-small' | 'sticker-sheet-strip'
```

`FORMAT_MM` um die drei ergänzen (alle A4, denn gedruckt wird auf A4):

```ts
  'a5-2up': { w: 210, h: 297 },
  'a6-4up': { w: 210, h: 297 },
  'a7-8up': { w: 210, h: 297 },
```

`MIN_QR_MM` um die drei ergänzen:

```ts
  'a5-2up': 30,
  'a6-4up': 25,
  // 68 mm Kachelbreite: ein 25-mm-Code fräße mehr als ein Drittel davon.
  'a7-8up': 18,
```

- [ ] **Step 4: `TiledFormat`, `TILE_LAYOUT`-Einträge, `isTiledFormat`, `tileBlockMm`**

```ts
export type TiledFormat = StickerFormat | 'a5-2up' | 'a6-4up' | 'a7-8up'

export function isTiledFormat(format: PrintFormat): format is TiledFormat {
  return format in TILE_LAYOUT
}
```

`TILE_LAYOUT` auf `Record<TiledFormat, TileLayout>` erweitern und die drei Einträge ergänzen:

```ts
  // Die A-Serie halbiert sich quer: zwei hochkante A5 passen nicht
  // nebeneinander auf A4, die Kachel liegt deshalb um 90° gedreht.
  'a5-2up': { w: 139, h: 196.5, gap: 4, cols: 1, rows: 2, rotate: true, scaleToTile: true },
  'a6-4up': { w: 97, h: 137, gap: 4, cols: 2, rows: 2, rotate: false, scaleToTile: true },
  'a7-8up': { w: 68, h: 96, gap: 4, cols: 2, rows: 4, rotate: true, scaleToTile: true },
```

`tileLayout` fällt heute auf `'sticker-sheet'` zurück, wenn das Format keine Kachel ist — das bleibt so, nur der Guard wechselt:

```ts
export function tileLayout(format: PrintFormat): TileLayout {
  return TILE_LAYOUT[isTiledFormat(format) ? format : 'sticker-sheet']
}
```

Neu, direkt darunter:

```ts
/**
 * Grundfläche des gesamten Kachelblocks auf dem A4-Blatt. Eine gedrehte
 * Kachel belegt dort h × w statt w × h — ohne diese Unterscheidung misst
 * jede Prüfung „passt das aufs Blatt" bei A5 und A7 das Falsche.
 */
export function tileBlockMm(format: PrintFormat): { w: number; h: number } {
  const l = tileLayout(format)
  const cellW = l.rotate ? l.h : l.w
  const cellH = l.rotate ? l.w : l.h
  return {
    w: l.cols * cellW + (l.cols - 1) * l.gap,
    h: l.rows * cellH + (l.rows - 1) * l.gap,
  }
}
```

- [ ] **Step 5: Tests und Typecheck**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts && npx nuxi typecheck`
Expected: Tests PASS. Der Typecheck meldet fehlende `Record<PrintFormat, …>`-Einträge, falls `FORMAT_MM` oder `MIN_QR_MM` unvollständig sind — diese Klasse Fehler muss verschwunden sein, bevor es weitergeht (vorbestehende Fehler anderswo bleiben, siehe Global Constraints).

- [ ] **Step 6: Commit**

```bash
git add app/lib/printSheet.ts app/lib/__tests__/printSheet.test.ts
git commit -m "feat(print): add 2-up A5, 4-up A6 and 8-up A7 tile geometry"
```

---

### Task 5: mm-Untergrenzen der Motive als CSS-Variablen

Die Poster-Motive tragen ihre Untergrenzen fest im CSS: `padding: max(5mm, 2.5em)` und `.qr { width: max(30mm, 12.5em) }`. Auf einer 68-mm-A7-Kachel gewinnt immer die mm-Grenze — ein 30-mm-Code fräße dort fast die halbe Breite. Statt in sieben Dateien Zahlen zu ändern, setzt das Blatt zwei Variablen. Die Spec nennt nur `--qr-min`; `--pad-min` kommt dazu, weil auf einer 68-mm-Kachel auch 5 mm Innenrand je Seite ein Sechstel der Breite kosten — dieselbe Ursache, dieselbe Mechanik, keine zweite Sonderregel.

Weil `MIN_QR_MM` für `a4`/`a5`/`a6` auf 30 und `PAD_MIN_MM` auf 5 steht, ist dieser Task für die bestehenden Formate ein **exakter No-op**.

**Files:**
- Modify: `app/lib/printSheet.ts` (`PAD_MIN_MM`, `sheetCssVars`)
- Modify: `app/components/print/PosterKlar.vue`, `PosterKachel.vue`, `PosterQrFirst.vue`, `PosterDunkel.vue`, `PosterSignal.vue`, `PosterDuo.vue`, `PosterKlassisch.vue`
- Modify: `app/pages/machines/[id]/print.vue:235-241` (`sheetStyle`)
- Modify: `app/components/print/MotifThumb.vue:30-35` (`innerStyle`)
- Test: `app/lib/__tests__/printSheet.test.ts`

**Interfaces:**
- Consumes: `MIN_QR_MM`, `PrintFormat` aus Task 4.
- Produces: `export function sheetCssVars(format: PrintFormat): Record<string, string>` — liefert `{ '--qr-min': '30mm', '--pad-min': '5mm' }` für A4.

- [ ] **Step 1: Test schreiben**

In `app/lib/__tests__/printSheet.test.ts` anfügen:

```ts
describe('sheetCssVars', () => {
  // Der Umstieg auf Variablen darf an A4/A5/A6 nichts verändern: die Werte
  // sind genau die, die heute fest in den Motiv-Styles stehen.
  it('reproduces the hardcoded values for the existing formats', () => {
    for (const format of ['a4', 'a5', 'a6'] as const) {
      expect(sheetCssVars(format)).toEqual({ '--qr-min': '30mm', '--pad-min': '5mm' })
    }
  })

  it('shrinks both floors for an A7 tile', () => {
    expect(sheetCssVars('a7-8up')).toEqual({ '--qr-min': '18mm', '--pad-min': '3mm' })
  })

  it('keeps the A6 tile at a scannable floor', () => {
    expect(sheetCssVars('a6-4up')).toEqual({ '--qr-min': '25mm', '--pad-min': '5mm' })
  })
})
```

Import-Block um `sheetCssVars` ergänzen.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts -t sheetCssVars`
Expected: FAIL — `sheetCssVars is not a function`

- [ ] **Step 3: `PAD_MIN_MM` und `sheetCssVars` implementieren**

In `app/lib/printSheet.ts` direkt unter `MIN_QR_MM`:

```ts
/**
 * Kleinster Innenrand, den ein Motiv behält. Auf einer 68-mm-A7-Kachel
 * kosteten 5 mm je Seite fast ein Sechstel der Breite.
 */
export const PAD_MIN_MM: Record<PrintFormat, number> = {
  a4: 5,
  a5: 5,
  a6: 5,
  'a5-2up': 5,
  'a6-4up': 5,
  'a7-8up': 3,
  'sticker-sheet': 5,
  'sticker-sheet-small': 5,
  'sticker-sheet-strip': 5,
}

/**
 * Die mm-Untergrenzen, die ein Motiv nicht selbst kennen kann, weil sie vom
 * Format abhängen. Als Custom Properties auf dem Blatt bzw. der Kachel
 * gesetzt; die Motive lesen sie mit ihren heutigen Werten als Fallback,
 * damit sie auch ohne gesetzte Variable unverändert rendern.
 */
export function sheetCssVars(format: PrintFormat): Record<string, string> {
  return {
    '--qr-min': `${MIN_QR_MM[format]}mm`,
    '--pad-min': `${PAD_MIN_MM[format]}mm`,
  }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `npx vitest run app/lib/__tests__/printSheet.test.ts -t sheetCssVars`
Expected: PASS

- [ ] **Step 5: Die sieben Poster-Motive umstellen**

Run:

```bash
sed -i '' -E \
  -e 's/max\(5mm,/max(var(--pad-min, 5mm),/g' \
  -e 's/max\(30mm,/max(var(--qr-min, 30mm),/g' \
  -e 's/max\(20mm,/max(calc(var(--qr-min, 30mm) * 2 \/ 3),/g' \
  app/components/print/Poster*.vue
```

Der dritte Ausdruck betrifft genau eine Stelle: `PosterDuo.vue`, `.strip-qr` — der kleinere Zweitcode des Duo-Posters. `calc(30mm * 2 / 3)` ist exakt die heutigen 20 mm, und auf einer A7-Kachel wird daraus 12 mm.

- [ ] **Step 6: Ergebnis der Ersetzung prüfen**

Run:

```bash
grep -rn "max(var(--qr-min\|max(var(--pad-min\|max(calc(var(--qr-min" app/components/print/Poster*.vue | wc -l
grep -rn "max(5mm\|max(30mm\|max(20mm" app/components/print/Poster*.vue
```

Expected: erste Zeile gibt `17` aus (nachgezählt: 8 Zeilen `padding`, 8 Zeilen mit je zwei `.qr`-Grenzen, 1 Zeile `.strip-qr`); die zweite gibt nichts aus (keine harte mm-Grenze mehr übrig).

- [ ] **Step 7: Variablen auf dem Blatt setzen**

In `app/pages/machines/[id]/print.vue` `sheetStyle` ersetzen:

```ts
const sheetStyle = computed(() => ({
  width: `${sheetMm.value.w}mm`,
  height: `${sheetMm.value.h}mm`,
  // One layout scales across A4/A5/A6: motifs size everything in em.
  fontSize: `${(4 * sheetMm.value.w) / 210}mm`,
  ...sheetCssVars(format.value),
}))
```

Import in derselben Datei ergänzen: `sheetCssVars` in den bestehenden `import { … } from '@/lib/printSheet'` einreihen.

In `app/components/print/MotifThumb.vue` `innerStyle` ersetzen:

```ts
const innerStyle = computed(() => ({
  width: `${mm.value.w}mm`,
  height: `${mm.value.h}mm`,
  fontSize: `${(4 * mm.value.w) / 210}mm`,
  transform: `scale(${scale.value})`,
  ...sheetCssVars(props.motif.formats[0]!),
}))
```

Import dort ergänzen: `sheetCssVars` in den bestehenden `import { FORMAT_MM, isStickerFormat, tileLayout } from '@/lib/printSheet'`.

- [ ] **Step 8: No-op für A4/A5/A6 rechnerisch nachweisen**

Der optische Abgleich steht unter „Abnahme durch den Bediener". Rechnerisch: `sheetCssVars('a4' | 'a5' | 'a6')` liefert `--qr-min: 30mm` und `--pad-min: 5mm`, und genau diese Werte standen vorher fest im CSS (`max(30mm, …)`, `max(5mm, …)`). Der Test aus Step 1 deckt das ab.

Zusätzlich belegen, dass die Ersetzung nichts anderes angefasst hat:

```bash
git diff --unified=0 -- app/components/print/Poster*.vue | grep '^[+-]' | grep -v '^[+-][+-]' \
  | grep -cvE 'qr-min|pad-min|max\(5mm|max\(30mm|max\(20mm'
```

Expected: `0` — jede geänderte Zeile ist entweder eine entfernte harte mm-Grenze oder eine hinzugefügte Variable. Es gibt keine dritte Sorte Änderung.

Das Filtermuster muss beide Seiten abdecken: `git diff --unified=0` gibt zu jeder geänderten Zeile die alte **und** die neue aus, und die alte trägt die Variable naturgemäß nicht.

- [ ] **Step 9: Tests, Typecheck, Commit**

Run: `npx vitest run && npx nuxi typecheck`
Expected: Tests PASS, keine *neuen* Typfehler.

```bash
git add app/lib/printSheet.ts app/lib/__tests__/printSheet.test.ts app/components/print/ "app/pages/machines/[id]/print.vue"
git commit -m "refactor(print): drive the motifs' mm floors from the format instead of hardcoding them"
```

---

### Task 6: `TiledSheet` — Drehung, em-Basis pro Kachel, gestrichelte Schnittlinien

**Files:**
- Create: `app/components/print/TiledSheet.vue` (ersetzt `StickerSheet.vue`)
- Delete: `app/components/print/StickerSheet.vue`
- Modify: `app/pages/machines/[id]/print.vue:3` (Import)

**Interfaces:**
- Consumes: `tileLayout`, `tileBlockMm` (Task 4), `sheetCssVars` (Task 5).
- Produces: Komponente `TiledSheet` mit den Props `{ sheets: PrintSheet[]; motif: Component; t: PosterT; format: PrintFormat }` — identisch zu `StickerSheet`, damit Task 7 nur den Namen tauschen muss.

- [ ] **Step 1: Den fehlschlagenden Geometrie-Test schreiben**

Die Kachel-Geometrie ist der riskante Teil dieser Änderung und rechnerisch vollständig prüfbar — Positionen, Drehung, Schnittlinien und em-Basis stehen als Inline-Styles im DOM. Das Repo hat bereits Komponententests mit `@vue/test-utils` und `happy-dom` (`app/components/__tests__/CellularHealthBadge.test.ts` als Muster; Setup in `vitest.config.ts`).

Create `app/components/__tests__/TiledSheet.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import TiledSheet from '../print/TiledSheet.vue'
import type { PrintFormat, PrintSheet } from '@/lib/printSheet'

/** Motifs are irrelevant to geometry — this one just marks its slot. */
const Stub = defineComponent({ props: ['sheet', 't'], setup: () => () => h('div', 'x') })

function sheets(n: number): PrintSheet[] {
  return Array.from({ length: n }, (_, i) => ({ machineId: `m${i}` }) as PrintSheet)
}

function mountSheet(format: PrintFormat, n: number) {
  return mount(TiledSheet, {
    props: { sheets: sheets(n), motif: Stub, t: (k: string) => k, format },
  })
}

/** Inline styles, read back as the browser stores them. */
const styleOf = (w: ReturnType<typeof mountSheet>, sel: string, i = 0) =>
  (w.findAll(sel)[i]!.element as HTMLElement).style

describe('TiledSheet geometry', () => {
  it('lays four A6 tiles on a centred 2 x 2 grid', () => {
    const w = mountSheet('a6-4up', 4)
    const cells = w.findAll('.cell')
    expect(cells).toHaveLength(4)
    // Block is 198 x 278 mm on A4, so 6 mm left and 9.5 mm top.
    expect(styleOf(w, '.cell', 0).left).toBe('6mm')
    expect(styleOf(w, '.cell', 0).top).toBe('9.5mm')
    expect(styleOf(w, '.cell', 1).left).toBe('107mm')
    expect(styleOf(w, '.cell', 2).top).toBe('150.5mm')
    expect(styleOf(w, '.cell', 0).width).toBe('97mm')
    expect(styleOf(w, '.cell', 0).height).toBe('137mm')
  })

  it('never renders more tiles than the grid holds', () => {
    expect(mountSheet('a6-4up', 9).findAll('.cell')).toHaveLength(4)
    expect(mountSheet('a7-8up', 20).findAll('.cell')).toHaveLength(8)
  })

  it('lays a rotated tile on its side and pushes it back into its cell', () => {
    const w = mountSheet('a7-8up', 1)
    // The cell is the rotated footprint: 96 wide, 68 tall.
    expect(styleOf(w, '.cell', 0).width).toBe('96mm')
    expect(styleOf(w, '.cell', 0).height).toBe('68mm')
    // The tile itself stays portrait and is rotated into place.
    const tile = styleOf(w, '.tile', 0)
    expect(tile.width).toBe('68mm')
    expect(tile.height).toBe('96mm')
    expect(tile.transform).toBe('translateX(96mm) rotate(90deg)')
    expect(tile.transformOrigin).toBe('top left')
  })

  it('leaves an unrotated tile untransformed', () => {
    expect(styleOf(mountSheet('a6-4up', 1), '.tile', 0).transform).toBe('')
  })

  it('rescales the em base for poster tiles only', () => {
    // A poster motif sizes everything in em against the sheet width; in a
    // 97 mm tile that base has to shrink or A4 text runs off an A6 card.
    expect(styleOf(mountSheet('a6-4up', 1), '.tile', 0).fontSize).toBe(`${(4 * 97) / 210}mm`)
    // Sticker motifs are tuned to the page base and must keep it.
    expect(styleOf(mountSheet('sticker-sheet', 1), '.tile', 0).fontSize).toBe('')
  })

  it('cuts once per gutter, plus the block edges', () => {
    const w = mountSheet('a6-4up', 4)
    // Two columns: left edge, gutter centre, right edge.
    expect(w.findAll('.cut-v').map((c) => (c.element as HTMLElement).style.left))
      .toEqual(['6mm', '105mm', '204mm'])
    expect(w.findAll('.cut-h').map((c) => (c.element as HTMLElement).style.top))
      .toEqual(['9.5mm', '148.5mm', '287.5mm'])
  })

  it('cuts a rotated grid on its own gutters', () => {
    const w = mountSheet('a7-8up', 8)
    expect(w.findAll('.cut-v').map((c) => (c.element as HTMLElement).style.left))
      .toEqual(['7mm', '105mm', '203mm'])
    expect(w.findAll('.cut-h').map((c) => (c.element as HTMLElement).style.top))
      .toEqual(['6.5mm', '76.5mm', '148.5mm', '220.5mm', '290.5mm'])
  })

  it('passes the format QR floor down to the tile', () => {
    const style = styleOf(mountSheet('a7-8up', 1), '.tile', 0)
    expect(style.getPropertyValue('--qr-min')).toBe('18mm')
    expect(style.getPropertyValue('--pad-min')).toBe('3mm')
  })
})
```

Sollte `happy-dom` Custom Properties nicht über `style.getPropertyValue` herausgeben, im letzten Test stattdessen gegen das gerenderte Attribut prüfen: `expect(w.findAll('.tile')[0]!.attributes('style')).toContain('--qr-min: 18mm')`. Nur dann umstellen, und im Report vermerken.

Die erwarteten Zahlen stammen aus `TILE_LAYOUT` (Task 4) und sind hier bewusst als konkrete mm-Werte ausgeschrieben statt aus der Implementierung nachgerechnet — ein Test, der die Formel der Implementierung wiederholt, kann sie nicht widerlegen.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `npx vitest run app/components/__tests__/TiledSheet.test.ts`
Expected: FAIL — die Komponente `../print/TiledSheet.vue` existiert noch nicht.

- [ ] **Step 3: `TiledSheet.vue` anlegen**

Create `app/components/print/TiledSheet.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import type { PrintFormat, PrintSheet } from '@/lib/printSheet'
import { sheetCssVars, tileBlockMm, tileLayout } from '@/lib/printSheet'
import type { PosterT } from '@/lib/printMotifs'

const props = defineProps<{
  sheets: PrintSheet[]
  motif: Component
  t: PosterT
  format: PrintFormat
}>()

const PAGE = { w: 210, h: 297 }

const grid = computed(() => {
  const l = tileLayout(props.format)
  const block = tileBlockMm(props.format)
  // Was eine Kachel auf dem Blatt belegt: gedreht liegt sie auf der Seite.
  const cellW = l.rotate ? l.h : l.w
  const cellH = l.rotate ? l.w : l.h
  return {
    ...l,
    cellW,
    cellH,
    blockW: block.w,
    blockH: block.h,
    offX: (PAGE.w - block.w) / 2,
    offY: (PAGE.h - block.h) / 2,
  }
})

const tiles = computed(() => {
  const g = grid.value
  return props.sheets.slice(0, g.cols * g.rows).map((sheet, i) => ({
    sheet,
    key: `${sheet.machineId}-${i}`,
    left: g.offX + (i % g.cols) * (g.cellW + g.gap),
    top: g.offY + Math.floor(i / g.cols) * (g.cellH + g.gap),
  }))
})

/**
 * Eine Linie je Schnitt, nicht eine je Kachelkante: mittig im Steg trennt ein
 * Zug mit dem Lineal zwei Kacheln, und jede Karte behält den halben Steg als
 * weissen Rand. Die Aussenkanten des Blocks bekommen ihre eigene Linie.
 */
function cuts(count: number, off: number, cell: number, gap: number, block: number): number[] {
  const out = [off]
  for (let i = 1; i < count; i++) out.push(off + i * (cell + gap) - gap / 2)
  out.push(off + block)
  return out
}

const xCuts = computed(() => {
  const g = grid.value
  return cuts(g.cols, g.offX, g.cellW, g.gap, g.blockW)
})

const yCuts = computed(() => {
  const g = grid.value
  return cuts(g.rows, g.offY, g.cellH, g.gap, g.blockH)
})

const tileStyle = computed(() => {
  const g = grid.value
  const style: Record<string, string> = {
    width: `${g.w}mm`,
    height: `${g.h}mm`,
    ...sheetCssVars(props.format),
  }
  // Poster-Motive skalieren alles in em gegen die Blattbreite; in einer
  // Kachel muss die Basis von der Kachel kommen, sonst läuft A4-Text über
  // eine A6-Karte. Aufkleber sind auf die Blatt-Basis getrimmt.
  if (g.scaleToTile) style.fontSize = `${(4 * g.w) / 210}mm`
  // Um die linke obere Ecke gedreht hängt die Kachel links neben ihrer
  // Zelle; translateX schiebt sie um ihre eigene Höhe wieder hinein.
  if (g.rotate) {
    style.transform = `translateX(${g.h}mm) rotate(90deg)`
    style.transformOrigin = 'top left'
  }
  return style
})
</script>

<template>
  <div class="tiled-page">
    <div
      v-for="tile in tiles"
      :key="tile.key"
      class="cell"
      :style="{
        left: `${tile.left}mm`,
        top: `${tile.top}mm`,
        width: `${grid.cellW}mm`,
        height: `${grid.cellH}mm`,
      }"
    >
      <div class="tile" :style="tileStyle">
        <component :is="motif" :sheet="tile.sheet" :t="t" />
      </div>
    </div>

    <!-- Nach den Kacheln, damit die Linien an den Blockkanten nicht halb
         unter einer Kachel verschwinden. -->
    <div v-for="x in xCuts" :key="`cx-${x}`" class="cut cut-v" :style="{ left: `${x}mm` }" />
    <div v-for="y in yCuts" :key="`cy-${y}`" class="cut cut-h" :style="{ top: `${y}mm` }" />
  </div>
</template>

<style scoped>
.tiled-page {
  position: relative;
  width: 100%;
  height: 100%;
  background: #fff;
}
.cell { position: absolute; overflow: hidden; }
.tile { position: absolute; left: 0; top: 0; box-sizing: border-box; overflow: hidden; }

/* Verlauf statt `border: dashed`: bei 0,25 mm Strichstärke rundet jeder
   Browser das Strichmuster anders, der Verlauf druckt überall gleich. */
.cut { position: absolute; }
.cut-v {
  top: 0;
  height: 100%;
  width: 0.25mm;
  background: repeating-linear-gradient(to bottom, #b0aca8 0 2mm, transparent 2mm 4mm);
}
.cut-h {
  left: 0;
  width: 100%;
  height: 0.25mm;
  background: repeating-linear-gradient(to right, #b0aca8 0 2mm, transparent 2mm 4mm);
}
</style>
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `npx vitest run app/components/__tests__/TiledSheet.test.ts`
Expected: PASS, 8/8.

- [ ] **Step 5: Alte Komponente entfernen und Import umhängen**

```bash
git rm app/components/print/StickerSheet.vue
```

In `app/pages/machines/[id]/print.vue` Zeile 3 ersetzen:

```ts
import TiledSheet from '@/components/print/TiledSheet.vue'
```

und im Template `<StickerSheet` → `<TiledSheet` (öffnendes Tag; es ist selbstschliessend, also nur eine Stelle).

- [ ] **Step 6: Aufkleber-Bogen auf Unverändertheit prüfen**

Der Test aus Step 1 deckt den gefährlichsten Fall bereits ab (`sticker-sheet` behält die Blatt-em-Basis). Ergänzend die Anordnung nachweisen, damit die Umstellung von `StickerSheet` auf `TiledSheet` die drei Aufkleber-Raster nicht verschiebt — in `app/components/__tests__/TiledSheet.test.ts` anfügen:

```ts
  it('keeps the three sticker grids where they were', () => {
    // 90 x 50 with a 3 mm gutter: 183 x 209 mm, centred on A4.
    const w = mountSheet('sticker-sheet', 8)
    expect(w.findAll('.cell')).toHaveLength(8)
    expect(styleOf(w, '.cell', 0).left).toBe('13.5mm')
    expect(styleOf(w, '.cell', 0).top).toBe('44mm')
    expect(styleOf(w, '.cell', 1).left).toBe('106.5mm')
    expect(styleOf(w, '.cell', 2).top).toBe('97mm')
    expect(mountSheet('sticker-sheet-small', 24).findAll('.cell')).toHaveLength(24)
    expect(mountSheet('sticker-sheet-strip', 6).findAll('.cell')).toHaveLength(6)
  })
```

Run: `npx vitest run app/components/__tests__/TiledSheet.test.ts`
Expected: PASS, 9/9.

Falls eine der Positionen abweicht: `offX`/`offY` in `TiledSheet` weichen von der Zentrierung in `StickerSheet` ab — dort liegt der Fehler, nicht im Test. Die optische Abnahme der Aufkleber steht unter „Abnahme durch den Bediener".

- [ ] **Step 7: Typecheck und Commit**

Run: `npx nuxi typecheck`
Expected: keine *neuen* Fehler in den berührten Dateien.

```bash
git add app/components/print/TiledSheet.vue app/components/__tests__/TiledSheet.test.ts "app/pages/machines/[id]/print.vue"
git commit -m "feat(print): tiled sheets gain rotation, per-tile em base and dashed cut lines"
```

---

### Task 7: Formate in der Oberfläche freischalten

**Files:**
- Modify: `app/lib/printMotifs.ts` (sieben `formats`-Arrays)
- Modify: `app/pages/machines/[id]/print.vue` (`isSticker` → `isTiled`, `pages`, Hinweis unter der Formatleiste)
- Modify: `i18n/locales/de.json:2061-2069`, `i18n/locales/en.json:2061-2069`

**Interfaces:**
- Consumes: `isTiledFormat`, `tilesPerSheet`, `distributeTiles` (Tasks 3 + 4), `TiledSheet` (Task 6).
- Produces: nichts.

- [ ] **Step 1: i18n-Schlüssel ergänzen**

In `i18n/locales/de.json` innerhalb von `print.formats` nach der Zeile `"a6": "A6 (105 × 148 mm)",` einfügen (6 Leerzeichen Einrückung):

```json
      "a5-2up": "2 × A5 auf A4",
      "a6-4up": "4 × A6 auf A4",
      "a7-8up": "8 × A7 auf A4",
```

und im selben `print`-Objekt neben `"browserHint"` (Zeile 2054) ergänzen:

```json
    "tiledHint": "Eine Kachel je ausgewähltem Automaten. Die gestrichelten Linien sind die Schnitthilfe.",
```

In `i18n/locales/en.json` an denselben Stellen:

```json
      "a5-2up": "2 × A5 on A4",
      "a6-4up": "4 × A6 on A4",
      "a7-8up": "8 × A7 on A4",
```

```json
    "tiledHint": "One tile per selected machine. The dashed lines show where to cut.",
```

- [ ] **Step 2: JSON-Gültigkeit prüfen**

Run:

```bash
node -e "
for (const f of ['de', 'en']) {
  const p = require('./i18n/locales/' + f + '.json').print;
  console.log(f, Object.keys(p.formats).length, p.tiledHint ? 'hint ok' : 'HINT MISSING');
}
"
```

Expected:
```
de 9 hint ok
en 9 hint ok
```

- [ ] **Step 3: Die neuen Formate den Poster-Motiven zuweisen**

In `app/lib/printMotifs.ts` die `formats`-Zeile **jedes** der sieben Poster-Motive ersetzen. `klar` heute `['a4', 'a5', 'a6']`, die übrigen sechs `['a4', 'a5']` — alle bekommen dieselbe Liste:

```ts
    formats: ['a4', 'a5', 'a6', 'a5-2up', 'a6-4up', 'a7-8up'],
```

Betrifft `klar`, `kachel`, `qr-first`, `duo`, `signal`, `klassisch`, `dunkel`. Die acht Aufkleber-Motive bleiben unverändert.

Danach prüfen:

```bash
grep -c "a7-8up" app/lib/printMotifs.ts
```

Expected: `7`

- [ ] **Step 4: `print.vue` auf gekachelte Formate umstellen**

`isSticker` ersetzen (Zeile 32) — der Import von `isStickerFormat` in dieser Datei entfällt dabei, `isTiledFormat` tritt an seine Stelle:

```ts
const isTiled = computed(() => isTiledFormat(format.value))
```

`pages` ersetzen (Zeile 229–233):

```ts
const pages = computed<PrintSheet[][]>(() =>
  isTiled.value
    ? distributeTiles(sheets.value, tilesPerSheet(format.value))
    : sheets.value.map(s => [s]),
)
```

Im Template `v-if="isSticker"` am `<TiledSheet …>` auf `v-if="isTiled"` ändern.

Der Import aus `@/lib/printMotifs` gibt `isStickerFormat` weiter — diese Datei importiert `isTiledFormat`, `distributeTiles` und `tilesPerSheet` stattdessen aus `@/lib/printSheet`, wo die drei bereits importiert werden.

- [ ] **Step 5: Hinweis unter der Formatleiste**

Im Template direkt nach dem `</div>` des Format-Button-Containers (innerhalb der `<section>` mit `t('print.format')`) einfügen:

```vue
        <p v-if="isTiled" class="mt-2 text-xs leading-snug text-muted-foreground">
          {{ t('print.tiledHint') }}
        </p>
```

- [ ] **Step 6: Verdrahtung statisch nachweisen**

Das Durchklicken steht unter „Abnahme durch den Bediener". Was ohne Browser belegbar ist:

```bash
grep -n "isTiled\|distributeTiles\|tilesPerSheet\|tiledHint" "app/pages/machines/[id]/print.vue"
grep -c "isSticker\b" "app/pages/machines/[id]/print.vue"
```

Expected: der erste Aufruf zeigt `isTiled` in `computed`, `v-if` und dem Hinweis-`<p>`, dazu `distributeTiles` und `tilesPerSheet` in `pages`; der zweite gibt `0` aus (kein `isSticker` mehr in dieser Datei).

Dass `pageSizeCss` für die neuen Formate auf A4 fällt, hängt an exakter Gleichheit gegen `'a5'`/`'a6'` — `'a5-2up'` trifft nicht zu. Diese Stelle einmal lesen und im Report bestätigen, dass sie unverändert korrekt ist.

- [ ] **Step 7: Volle Testsuite, Typecheck, Lint**

Run: `npx vitest run && npx nuxi typecheck`
Expected: Tests PASS, keine *neuen* Typfehler. (Kein Lint-Schritt: das Repo hat weder eine ESLint-Konfiguration noch ein `lint`-Script.)

- [ ] **Step 8: Commit**

```bash
git add app/lib/printMotifs.ts "app/pages/machines/[id]/print.vue" i18n/locales/de.json i18n/locales/en.json
git commit -m "feat(print): offer 2-up A5, 4-up A6 and 8-up A7 poster sheets"
```

---

## Abnahme durch den Bediener

Diese Schritte brauchen Augen und einen Login und sind in diesem Durchlauf **nicht** erledigt. `/machines/<id>/print` öffnen und der Reihe nach:

1. **Duo-Aufkleber** (Task 2): der rechte QR-Code überlappt den Text nicht, `side-title` und `side-hint` bleiben lesbar, die Telefonzeile wird nicht abgeschnitten. Falls `side-hint` auf mehr als zwei Zeilen umbricht: `.side-hint` in `StickerDuo.vue` von `0.9em` auf `0.8em`.
2. **Mini-Aufkleber** (Task 2): Titel und Telefonnummer stehen neben dem 20-mm-Code noch vollständig.
3. **Beide Aufkleber real ausdrucken und scannen** — das ist die einzige Prüfung, die die Ausgangsfrage wirklich beantwortet.
4. **A4 und A5, alle sieben Poster-Motive** (Task 5): keine sichtbare Änderung gegenüber vorher. Springt ein QR-Code oder ein Innenrand, ist ein Wert in `MIN_QR_MM` oder `PAD_MIN_MM` falsch, nicht das CSS.
5. **Aufkleber-Bogen** (Task 6): dieselbe Anordnung wie vorher, dieselbe Textgröße — statt der kurzen Eckstriche laufen jetzt gestrichelte Linien durch.
6. **„4 × A6 auf A4"** (Task 7): eine Kachel oben links, gestrichelte Linien im Kreuz, Rest weiß, Hinweistext unter der Formatleiste. Vier Maschinen ankreuzen → alle vier Kacheln belegt, ein Blatt.
7. **„8 × A7 auf A4"** (Task 7): Kacheln liegen quer, das Motiv steht darin hochkant. Alle sieben Motive durchklicken — welche in A7 überlaufen, ist eine Entscheidung, kein Bug: laut Spec sind alle sieben wählbar, die Vorschau ist die Entscheidungshilfe.
8. **„2 × A5 auf A4"** (Task 7): zwei quer liegende Kacheln übereinander.
9. **Druckvorschau (`Cmd+P`) bei „4 × A6 auf A4"**: Seitengröße A4, ein Blatt, Schnittlinien sichtbar — Hintergrundgrafiken müssen im Dialog aktiv sein.

## Abschluss

Nach Task 7 ist die Spec vollständig umgesetzt. Offen bleibt bewusst:

- `StickerMini` liegt mit 20 mm bei 0,488 mm pro Modul, knapp unter der Komfortschwelle. Das ist die physikalische Grenze eines 50 × 30 mm großen Aufklebers, kein Bug.
- Ob einzelne Motive in A7 gedrängt wirken, entscheidet der Bediener in der Vorschau. Wenn Task 7 Step 6 handfeste Überläufe zeigt (Text ausserhalb der Kachel, überlappende Blöcke), ist das ein Folge-Ticket, kein Teil dieses Plans.
