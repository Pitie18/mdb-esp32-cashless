# Aushang: N-up-Bogen, Schnittlinien und lesbare Aufkleber-QRs

Datum: 2026-08-24
Betrifft: `management-frontend`, Seite `/machines/[id]/print`
Vorgänger: `2026-08-10-automaten-aushang-druck-design.md`

## Problem

Zwei unabhängige Beschwerden aus dem Feld:

1. **Kleine Formate brauchen kleines Papier.** A5 und A6 gibt es zwar, aber
   jeweils als eigene Seitengröße (`@page { size: A6 portrait }`). Wer vier
   A6-Aushänge braucht, muss vier Blatt A6 in den Drucker legen oder im
   Systemdialog von Hand „mehrere Seiten pro Blatt" suchen. A7 gibt es gar
   nicht. Die Mechanik für „mehrere pro A4 mit Schnitthilfe" existiert bereits —
   aber nur für Aufkleber.
2. **Der rechte QR-Code des Duo-Aufklebers ist unlesbar.** Mit 13 mm Kantenlänge
   und Fehlerkorrektur Q ergibt die Ziel-URL 45 Module, also **0,245 mm pro
   Modul**. Mit Punktzuwachs eines Büro-Druckers ist das Matsch, kein Code.

## Nicht Teil dieser Änderung

- Die Ziel-URLs bleiben, wie sie sind. `?feedback=problem` auf 17 Zeichen zu
  kürzen würde jeden bereits gedruckten Aushang im Feld ungültig machen.
- Einzelformate A4/A5/A6 bleiben unverändert bestehen. Wer A5-Papier hat, soll
  weiter direkt A5 drucken können.
- Keine DB-Migration. `poster_printed` schreibt `format` als freien String,
  neue Werte fließen ohne Schemaänderung durch; `usePosterFreshness` vergleicht
  den Kontakt-Fingerprint, nicht das Format.

## Teil 1 — N-up-Bogen

### Formatmodell (`app/lib/printSheet.ts`)

Der bestehende „Sticker-Bogen" ist bereits ein generischer Kachelbogen. Er wird
verallgemeinert statt dupliziert.

Drei neue `PrintFormat`-Werte: `a5-2up`, `a6-4up`, `a7-8up`.

- `StickerLayout` → `TileLayout`, ergänzt um `rotate: boolean` und
  `scaleToTile: boolean`.
- `STICKER_LAYOUT` → `TILE_LAYOUT`, um die drei neuen Einträge erweitert.
- `stickerLayout` / `stickersPerSheet` / `distributeStickers` →
  `tileLayout` / `tilesPerSheet` / `distributeTiles` (drei Aufrufstellen:
  `print.vue`, `TiledSheet.vue`, `MotifThumb.vue`).
- `isStickerFormat` bleibt — es unterscheidet weiterhin Aufkleber von Postern
  für den Galerie-Thumbnail. Neu daneben `isTiledFormat`, das beide Familien
  abdeckt.

Geometrie auf A4 (210 × 297 mm), 6 mm Außenrand, 4 mm Steg, Seitenverhältnis
1:√2 gehalten:

| Format | Kachel gerendert | Raster | gedreht | Kacheln/Blatt |
|---|---|---|---|---|
| `a5-2up` | 139 × 196,5 mm | 1 × 2 | ja | 2 |
| `a6-4up` | 97 × 137 mm | 2 × 2 | nein | 4 |
| `a7-8up` | 68 × 96 mm | 2 × 4 | ja | 8 |

Acht hochkante A7 nebeneinander passen rechnerisch nicht auf A4 — deshalb liegt
die Kachel bei `a5-2up` und `a7-8up` um 90° gedreht auf dem Blatt. Das Motiv
bleibt hochkant und weiß von der Drehung nichts; ausgeschnitten kommt eine
hochkante Karte heraus.

Durch den Steg sind die Kacheln rund 92 % der Norm (A6 wird 97 × 137 statt
105 × 148 mm). Die Format-Labels sagen deshalb „4 × A6 auf A4", nicht „A6".

`FORMAT_MM` liefert für alle drei A4, damit `@page size` und der Zoom
unverändert funktionieren. `pageSizeCss` in `print.vue` prüft exakte Gleichheit
gegen `'a5'` / `'a6'` und fällt für die neuen IDs korrekt auf A4 zurück.

### Kachelbogen (`StickerSheet.vue` → `TiledSheet.vue`)

Drei Erweiterungen an der bestehenden Komponente:

- **Drehung.** Die Kachel wird in ihrer Hochkant-Größe gerendert und per
  `rotate(90deg)` in den quer liegenden Platz gesetzt.
- **em-Basis pro Kachel.** Poster-Motive skalieren alles in `em`; die Basis kommt
  heute vom A4-Blatt (4 mm). In einer 97-mm-Kachel muss sie von der
  Kachelbreite kommen, sonst läuft A4-Text über eine A6-Karte. Aufkleber
  behalten die Blatt-Basis, weil ihr CSS darauf getrimmt ist — gesteuert über
  `scaleToTile` in `TILE_LAYOUT`, nicht über eine Sonderabfrage im Template.
- **Gestrichelte Schnittlinien** statt der kurzen Eckstriche: je eine Linie in
  der Stegmitte, durchgehend bis zum Papierrand, plus je eine am äußeren Rand
  des Kachelblocks. Ein Schnitt pro Linie, mit dem Lineal in einem Zug; jede
  Karte behält 2 mm weißen Rand. Gilt auch für die drei Aufkleber-Bogen.

### Blatt-Füllung

Unverändert die bestehende Semantik von `distributeStickers`: **eine Kachel pro
ausgewählter Maschine**, der Rest des Blatts bleibt weiß. Kein Kopien-Feld, keine
Auto-Wiederholung.

### Motive und Seite

- Alle sieben Poster-Motive bekommen `a5-2up`, `a6-4up`, `a7-8up` in ihr
  `formats`-Array — auch Kachel und Duo in A7, obwohl es dort eng wird. Die
  Vorschau zeigt dem Bediener, ob es passt.
- `print.vue`: `isSticker` → `isTiled`, `distributeTiles(sheets,
  tilesPerSheet(format))`, drei neue i18n-Labels (de + en) sowie ein Hinweis
  unter der Formatleiste, dass eine Kachel pro ausgewählter Maschine belegt
  wird.
- `MotifThumb.vue`: nur die Umbenennung von `stickerLayout`.

## Teil 2 — QR-Lesbarkeit

### Fehlerkorrektur folgt der Kachelgröße, nicht dem Papiertyp

`qrErrorLevel` entscheidet heute „Aufkleber → Q, Poster → M" und begründet das
mit Abrieb. Die Begründung geht am Problem vorbei: ein Symbol, dessen Module
unter ~0,5 mm fallen, ist unlesbar, egal wie viel Redundanz es trägt. Höhere
Fehlerkorrektur *verschlimmert* das sogar, weil sie die Modulzahl erhöht.

Neue Regel, datengetrieben aus dem ohnehin vorhandenen `MIN_QR_MM`:

```ts
export function qrErrorLevel(format: PrintFormat): 'L' | 'M' {
  return MIN_QR_MM[format] < 25 ? 'L' : 'M'
}
```

`MIN_QR_MM` wird um die neuen Formate ergänzt: `a5-2up` 30, `a6-4up` 25,
`a7-8up` 18. Damit ergibt sich L für alle drei Aufkleber-Bogen und für
`a7-8up`, M für alle übrigen. Das setzt die Entscheidung „Aufkleber auf L"
um und deckt A7 gleich mit ab, ohne eine zweite Sonderregel.

Gemessen mit der `qrcode`-Lib gegen die längste reale Ziel-URL
(`https://app.vmflow.de/m/<uuid>?feedback=problem`, 77 Zeichen, Quiet Zone 4
Module je Seite):

| Aufkleber | QR | heute (Q) | mit L |
|---|---|---|---|
| Duo rechts | 13 mm | **0,245** | 0,317 |
| Mini | 16 mm | 0,302 | 0,390 |
| Imprint | 17 mm | 0,321 | 0,415 |
| Service | 20 mm | 0,377 | 0,488 |
| Problem / Menu | 22 mm | 0,415 | 0,537 |
| Duo links / Strip | 24–26 mm | 0,491 | 0,634 |

### Duo-Aufkleber: rechter Code 13 → 22 mm

Der Level-Wechsel allein reicht bei Duo nicht — 0,317 mm bleibt unter jeder
brauchbaren Schwelle. Der Code ist doppelt bestraft: halb so groß wie der linke
**und** mit einer längeren URL belegt, die ihn bei Q von Version 5 auf 7 hebt.

Platz ist vorhanden: 90 × 50 mm minus 3 mm Padding = 84 × 44 mm nutzbar, davon
26 mm für den linken Code plus 5,2 mm Abstände und Trennlinie. Für den rechten
Block bleiben 52,8 mm; ein 22-mm-Code lässt dort 29 mm für Text. Die zweizeilige
`side-hint` wird daneben zu eng und wandert unter den Titel oder entfällt.

22 mm mit L ergibt **0,537 mm** pro Modul — das 2,2-fache von heute.

Die Hierarchie bleibt gewahrt: der einladende linke Code (26 mm) ist weiterhin
der größere, der Störungs-Code der kleinere.

### Bekannte Untergrenze

`StickerMini` bleibt mit 16 mm auf 0,390 mm und damit unter der 0,5-mm-Marke.
Der Aufkleber ist 50 × 30 mm groß, der Code belegt schon jetzt über die halbe
Höhe. Falls das Layout es hergibt, wird er bei der Umsetzung auf 18 mm
gebracht; darüber hinaus ist es eine physikalische Grenze des Formats, kein Bug.

### `--qr-min` statt harter mm-Zahlen

Die Poster-Motive tragen mm-Untergrenzen fest im CSS: `padding: max(5mm, 2.5em)`
und `.qr { width: max(30mm, 12.5em) }`. Auf einer 68-mm-A7-Kachel gewinnt immer
die mm-Grenze — ein 30-mm-Code fräße dort die halbe Breite. Statt in sieben
Dateien Zahlen zu ändern, setzt `TiledSheet` eine CSS-Variable `--qr-min` aus
`MIN_QR_MM[format]`; die Motive lesen `max(var(--qr-min, 30mm), 12.5em)`. Damit
wird `MIN_QR_MM` wirksam statt nur dokumentiert.

## Tests

In `app/lib/__tests__/printSheet.test.ts`:

- Für jedes gekachelte Format passt das Raster nachweislich auf A4:
  `cols * w + (cols-1) * gap + 2 * margin <= 210` (bzw. `<= 297` in der Höhe),
  bei gedrehten Formaten mit vertauschten Kantenlängen.
- `tilesPerSheet` liefert 2 / 4 / 8 für die neuen Formate.
- `isStickerFormat` ist für die drei N-up-Poster-Formate falsch (sonst
  bekämen sie den Aufkleber-Thumbnail).
- `MIN_QR_MM` und `TILE_LAYOUT` haben für jedes `PrintFormat` einen Eintrag.
- `qrErrorLevel` liefert L genau für die Formate mit `MIN_QR_MM < 25`.
- Die bestehenden `distributeStickers`-Tests laufen unter dem neuen Namen
  weiter.

Manuell in der Vorschau zu prüfen: alle sieben Poster-Motive in `a7-8up`, sowie
der Duo-Aufkleber nach der Vergrößerung.
