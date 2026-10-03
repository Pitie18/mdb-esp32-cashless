# Automaten-Aushänge: druckbare Kontakt- und QR-Vorlagen

**Datum:** 2026-08-10
**Status:** Design approved (user), pending spec review
**Plattform:** PWA (management-frontend). iOS bewusst nicht in v1.

## Problem

Steht ein Kunde vor einem Automaten und etwas funktioniert nicht – Ware hängt,
Geld weg, Produkt aus –, gibt es heute keinen Weg zum Betreiber. Am Automaten
klebt nichts: keine Telefonnummer, kein QR, kein Hinweis auf die bereits
existierende öffentliche Automatenseite.

Gleichzeitig liegt die halbe Lösung schon im Repo und wird nicht genutzt:

- `/m/{machine_id}` ([app/pages/m/[machine_id].vue](../../../management-frontend/app/pages/m/[machine_id].vue))
  zeigt Sortiment, Preise, Verfügbarkeit, Impressum, Störungsmeldung
  (`submit-machine-feedback`) und – wo aktiviert – Online-Zahlung.
- `companies` trägt seit `20260410140000_company_imprint_and_feedback.sql`
  vollständige Betreiberdaten (`legal_name`, `contact_email`, `contact_phone`,
  `website`, `address_*`).
- `qrcode` und `jspdf` sind bereits Dependencies.
- [MachineSettingsModal.vue:49](../../../management-frontend/app/components/MachineSettingsModal.vue:49)
  erzeugt bereits einen QR auf `${origin}/m/${machineId}` – nur zur Anzeige,
  nicht druckbar.

Es fehlt also nicht die Infrastruktur, sondern das Blatt Papier.

## Ziel

Eine Druckseite pro Automat, die aus den vorhandenen Betreiber- und
Automatendaten fertige Aushänge in mehreren Motiven und Formaten erzeugt –
mit großer Support-Telefonnummer, QR-Codes (Automatenseite, Anruf, WhatsApp,
Störungsmeldung) und Impressumszeile. Gedruckt wird über den Browser-Druckdialog.

## Entscheidungen (mit dem Nutzer abgestimmt)

1. **Datenquelle:** Firmen-Impressum als Standard, pro Automat überschreibbar.
2. **Bausteine:** alle vier – QR auf die Automatenseite, große Telefonnummer
   mit `tel:`-QR, WhatsApp-QR, separater Störungs-QR.
3. **Formate:** A4 hoch, A5, A6, Aufkleber 90×50 mm, Mehrfachnutzen auf A4.
4. **Motive:** alle vier (A Klar, B Kachel, C QR zuerst, D Dunkel) plus beide
   Aufkleber-Sorten (Störung melden, Sortiment ansehen).
5. **Technik:** HTML + CSS-Print (`window.print()`), kein PDF-Generator.
6. **Plattform:** nur PWA in v1.
7. **Einstieg:** eigene Route `/machines/[id]/print`, Button auf der
   Automatenseite. Kein Modal.
8. **Sammel-Druck:** ja, in v1 – Mehrfachauswahl von Automaten, ein Druckauftrag.
9. **Öffentliche URL:** aus der bestehenden `SITE_URL` statt aus einem neuen
   DB-Feld (siehe unten).
10. **Neue Felder:** WhatsApp-Nummer, Erreichbarkeitszeiten, Firmenlogo,
    Freitext pro Ausdruck.

## Die öffentliche URL

Der gedruckte QR muss auf die extern erreichbare Domain zeigen. `window.location.origin`
reicht dafür nicht: Wer die PWA über LAN-IP oder interne Domain bedient, druckt
`http://10.0.1.181:3000/m/…` – ein QR, der auf jedem Kundenhandy ins Leere
läuft, und man merkt es erst, wenn die Blätter hängen.

`SITE_URL` existiert bereits ([.env.example:64](../../../Docker/.env.example:64)),
wird von [setup.sh:225](../../../Docker/setup.sh:225) auf `https://${APP_HOST}`
gesetzt und speist über `GOTRUE_SITE_URL`
([docker-compose.yml:215](../../../Docker/docker-compose.yml:215)) die
Auth-Mails. In jeder Installation, in der Passwort-Reset funktioniert, ist sie
zwangsläufig korrekt. Sie ist dem Frontend nur bisher nicht bekannt.

**Kein neues DB-Feld, keine neue Env-Variable** – nur durchreichen:

| Datei | Änderung |
|---|---|
| `Docker/docker-compose.yml` | `NUXT_PUBLIC_SITE_URL: ${SITE_URL}` im `frontend`-Service |
| `management-frontend/nuxt.config.ts` | `runtimeConfig.public.siteUrl: process.env.SITE_URL ?? ''` |

Kein `ARG`/`ENV` im Dockerfile nötig: `runtimeConfig.public.*` wird von Nuxt zur
Laufzeit aus `NUXT_PUBLIC_*` überschrieben, der Wert wird nicht zur Build-Zeit
eingebacken.

**Auflösung** in `useMachinePrint()`:

```
publicOrigin = runtimeConfig.public.siteUrl || window.location.origin
```

**Warnung:** Sieht `publicOrigin` nach einer nicht-öffentlichen Adresse aus,
erscheint ein Banner über der Vorschau (mit Link in die Einstellungen bzw.
Hinweis auf `SITE_URL`). Der Druckknopf bleibt aktiv – Testdrucke sind legitim.
Als nicht-öffentlich gilt: `localhost`, `127.0.0.0/8`, `10.0.0.0/8`,
`172.16.0.0/12`, `192.168.0.0/16`, `*.local`, oder ein anderer Port als 80/443.
Diese Prüfung ist eine reine Funktion (`isPublicOrigin(url: string): boolean`)
und wird unit-getestet.

## Datenmodell

Neue Migration `YYYYMMDDHHMMSS_machine_poster_contact.sql`. Bestehende
Migrationen werden **nicht** editiert (siehe CLAUDE.md → Database Migrations).
Alle Operationen idempotent, alle Spalten nullable → rückwärtskompatibel.

```sql
alter table public.companies
  add column if not exists whatsapp_phone text,
  add column if not exists support_hours  text,
  add column if not exists logo_path      text;

alter table public."vendingMachine"
  add column if not exists contact_phone  text,
  add column if not exists whatsapp_phone text,
  add column if not exists support_hours  text,
  add column if not exists contact_email  text;
```

Die Spalten auf `vendingMachine` sind reine Overrides. Auflösung immer
`machine.x ?? company.x`; leerer String zählt als „nicht gesetzt" und fällt
ebenfalls auf die Firma zurück.

`comment on column` für jede neue Spalte, analog zur Imprint-Migration.

**RLS:** keine neuen Policies. `companies` und `vendingMachine` haben bereits
company-scoped Policies; neue Spalten erben sie. Schreibrechte auf die
Kontaktfelder folgen den bestehenden Regeln (Firmenfelder: Admin; Automatenfelder:
wie die übrigen `vendingMachine`-Updates).

### Storage-Bucket `company-logos`

`Docker/supabase/config.toml`:

```toml
[storage.buckets.company-logos]
public = true
file_size_limit = "2MiB"
allowed_mime_types = ["image/png", "image/jpeg", "image/webp"]
```

Kein SVG: In einem öffentlichen Bucket ist ein hochladbares SVG ein XSS-Vektor.
Pfad `{company_id}.{ext}` mit `upsert`, analog zu `product-images` in
`useProducts()`. `companies.logo_path` hält den Objektpfad; die URL wird wie bei
Produktbildern clientseitig gebaut.

## Neue/geänderte Einstellungen

[app/components/settings/ImprintCard.vue](../../../management-frontend/app/components/settings/ImprintCard.vue)
wird um drei Felder erweitert: WhatsApp-Nummer, Erreichbarkeitszeiten (Freitext,
z.B. „Mo–Fr 8–18 Uhr"), Logo-Upload mit Vorschau und Entfernen-Knopf.

Die Automaten-Overrides (`contact_phone`, `whatsapp_phone`, `support_hours`,
`contact_email`) kommen in
[MachineSettingsModal.vue](../../../management-frontend/app/components/MachineSettingsModal.vue)
in einen eigenen, zugeklappten Abschnitt „Abweichende Kontaktdaten" mit dem
Hinweis, dass leere Felder die Firmendaten verwenden. Jedes Feld zeigt den
geerbten Firmenwert als Platzhalter – so sieht man ohne Nachschlagen, was
gedruckt wird.

## Motiv-System

### Datenvertrag

Ein Motiv ist eine `.vue`-Datei in `app/components/print/` mit **genau einem
Prop**: einem flachen, bereits aufgelösten `PrintSheet`. Kein Motiv fragt die
Datenbank, kein Motiv kennt `machineId`, kein Motiv erzeugt QR-Codes.

```ts
export interface PrintSheetQr {
  /** SVG-Markup, nicht DataURL. */
  page: string
  tel: string | null
  whatsapp: string | null
  problem: string | null
}

export interface PrintSheet {
  machineName: string
  machineNote: string | null      // Standortzusatz, z.B. "Foyer Nord"
  companyName: string             // legal_name ?? companies.name
  addressLine: string | null      // "Musterstr. 4 · 34117 Kassel"
  email: string | null
  website: string | null
  phone: string | null
  whatsapp: string | null
  hours: string | null
  logoUrl: string | null
  customText: string | null       // Freitext, nicht persistiert
  pageUrl: string                 // absolute URL, auch als Klartext druckbar
  qr: PrintSheetQr
  missing: string[]               // Feld-Keys ohne Wert, für die Lückenwarnung
}
```

`qr.tel`, `qr.whatsapp` und `qr.problem` sind `null`, wenn der zugehörige
Baustein abgeschaltet ist **oder** die Datengrundlage fehlt. Motive rendern
einen Block nur, wenn sein Wert nicht `null` ist – so gibt es keine leeren
Kacheln auf Papier.

### QR-Erzeugung

`QRCode.toString(data, { type: 'svg', margin: 4, errorCorrectionLevel })` –
Vektor statt PNG-DataURL wie bisher. Bei 5 cm Kantenlänge auf Papier ist der
Unterschied deutlich sichtbar. Das generierte SVG stammt aus lokalem Code und
wird per `v-html` eingesetzt.

- `errorCorrectionLevel: 'M'` für A4/A5/A6.
- `errorCorrectionLevel: 'Q'` für Aufkleber – die kleben am Auswurffach und
  werden verkratzt und verschmutzt.
- `margin: 4` Module Quiet Zone. Ohne sie scannt der Code aus 50 cm nicht.

QR-Ziele:

| Baustein | Ziel |
|---|---|
| Automatenseite | `{publicOrigin}/m/{machine_id}` |
| Anruf | `tel:{phone ohne Leer-/Sonderzeichen}` |
| WhatsApp | `https://wa.me/{whatsapp E.164 ohne +}?text={vorbefüllter Text inkl. Automatenname}` |
| Störung | `{publicOrigin}/m/{machine_id}?feedback=problem` |

Der Query-Parameter `feedback=problem` muss in
[app/pages/m/[machine_id].vue](../../../management-frontend/app/pages/m/[machine_id].vue)
ausgewertet werden und das bestehende Störungsformular direkt öffnen. Das ist
die einzige Änderung an der öffentlichen Seite.

### Registry

`app/lib/printMotifs.ts`:

```ts
export interface PrintMotif {
  id: 'klar' | 'kachel' | 'qr-first' | 'dunkel' | 'sticker-problem' | 'sticker-menu'
  labelKey: string
  component: Component
  formats: PrintFormat[]        // welche Formate das Motiv trägt
  blocks: PrintBlock[]          // welche Bausteine es überhaupt darstellen kann
}
```

Ein neues Motiv später = ein Registry-Eintrag plus eine `.vue`-Datei. Nichts
anderes muss angefasst werden.

### Die sechs Motive

| ID | Beschreibung | Formate |
|---|---|---|
| `klar` | Weiß, Telefonnummer dominant, ein QR unten. Funktioniert auch in Schwarz-Weiß. **Default.** | A4, A5, A6 |
| `kachel` | Farbiger Kopfbalken, drei beschriftete QR-Kacheln, Impressum-Fußzeile | A4, A5 |
| `qr-first` | Ein großer QR mittig auf die Automatenseite, Nummer als Fallback | A4, A5 |
| `dunkel` | Dunkler Grund, Amber-Akzent, QR auf weißer Kachel | A4, A5 |
| `sticker-problem` | 90×50 mm: Störungs-QR + Telefonnummer | Aufkleberbogen |
| `sticker-menu` | 90×50 mm: QR auf die Automatenseite | Aufkleberbogen |

`kachel` zeigt maximal drei QR-Codes und ist damit bewusst die dichteste
Variante; die Beschriftung jeder Kachel ist deshalb Pflicht, nicht optional.

## Die Druckseite

Route `/machines/[id]/print`, `definePageMeta({ layout: false })`. Eigene Route
statt Modal, weil so die App-Chrome gar nicht erst existiert und das Print-CSS
sie nicht mühsam ausblenden muss – ein Ansatz, der bei jedem Layout-Update
wieder bricht.

**Zugriff:** jedes eingeloggte Mitglied der Firma (nicht nur Admins) – Drucken
ist eine Lese-Operation. Das Bearbeiten der Kontaktfelder folgt weiter den
bestehenden Rollenregeln.

### Layout

Links eine Steuerleiste mit Klasse `no-print`:

- Motiv-Auswahl (kleine Vorschau-Kacheln)
- Format (A4 / A5 / A6 / Aufkleberbogen) – gefiltert nach `motif.formats`
- Bausteine als Schalter: Telefonnummer, WhatsApp-QR, Störungs-QR,
  Impressum-Fußzeile – gefiltert nach `motif.blocks`
- Sprache des Aushangs
- Freitext (ein Satz, nicht persistiert)
- Automaten-Mehrfachauswahl für den Sammel-Druck
- Druckknopf

Rechts die Vorschau im echten Seitenverhältnis, bei Sammel-Druck eine Seite pro
Automat untereinander. Auf dem Bildschirm wird sie per `transform: scale()`
eingepasst; im Druck greift die Skalierung nicht (`@media screen` only).

### Sprache des Aushangs

Getrennt von der UI-Sprache wählbar, Default = aktuelle Locale. Ein Betreiber
mit englischer Oberfläche und einem Automaten in Kassel braucht einen deutschen
Aushang. Die Motive erhalten ihre Texte über einen an die gewählte Locale
gebundenen `t`-Aufruf (`useI18n().t` mit explizitem `locale`-Argument), nicht
über den globalen Locale-State – sonst kippt die ganze Oberfläche mit.

Die Aushangtexte liegen unter `print.*` in allen vier Locale-Dateien
(`i18n/locales/{de,en,fr,nl}.json`).

### Lückenwarnung

`PrintSheet.missing` listet Feld-Keys ohne Wert. Die Vorschau markiert die
betroffenen Stellen und zeigt über der Vorschau einen Hinweis mit Direktlink in
die Einstellungen bzw. die Automaten-Kontaktdaten. Ein Aushang ohne
Support-Nummer darf keine stille Leerfläche drucken.

## Print-CSS

```css
@media print {
  .no-print { display: none !important; }
}
.sheet {
  print-color-adjust: exact;
  -webkit-print-color-adjust: exact;
  page-break-after: always;
}
.sheet:last-child { page-break-after: auto; }
```

- `print-color-adjust: exact` ist nicht optional: ohne sie druckt Chrome die
  Farbflächen von `kachel` und `dunkel` schlicht nicht.
- `page-break-after: auto` auf dem letzten Blatt, sonst hängt eine leere Seite
  hinten dran.
- `@page { size: <format>; margin: 0 }` wird dynamisch nach gewähltem Format
  gesetzt (gebundener `<style>`-Block, da `@page` nicht über Inline-Styles
  ansprechbar ist). Der Innenrand von 10 mm liegt im Motiv – randlos kann kein
  Bürodrucker.

Blattmaße: A4 210×297 mm, A5 148×210 mm, A6 105×148 mm, jeweils hoch.

### Aufkleberbogen

Ein A4-Blatt mit 2×4 Raster à 90×50 mm, 3 mm Steg, zentriert. Schnittmarken als
0,2-mm-Haarlinien in den Stegen. Beim Sammel-Druck werden die Aufkleber
fortlaufend über die Bögen verteilt (Automat 1 belegt Position 1, Automat 2
Position 2, …), nicht ein Bogen pro Automat – sonst verschwendet man bei 3
Automaten 21 Aufkleber.

Mindest-QR-Größen: ≥ 30 mm bei A4/A5, ≥ 25 mm bei A6, ≥ 20 mm auf dem Aufkleber.
Diese Werte sind Konstanten im Motiv-CSS, keine freien Parameter.

## Protokollierung

Jeder Druckvorgang schreibt pro Automat einen `activity_log`-Eintrag:

```
entity_type: 'machine'
entity_id:   <machine_id>
action:      'poster_printed'
metadata:    { motif, format, blocks: string[], sheet_language }
```

Der Eintrag wird beim Klick auf „Drucken" geschrieben, nicht nach dem Dialog –
ob der Nutzer im Systemdialog abbricht, ist vom Browser aus nicht zuverlässig
feststellbar. Ein Eintrag bedeutet also „Druck ausgelöst", und so lautet auch
das Label.

**Rendering:** `poster_printed` wird in
[app/lib/activityDescriptor.ts](../../../management-frontend/app/lib/activityDescriptor.ts)
zu `KNOWN_ACTIONS` hinzugefügt, bekommt ein Icon (`Printer`, Tint `neutral`),
eine Beschreibung und Detailzeilen. Das ist die einzige Stelle – die
`.vue`-Dateien der History-Seite werden nicht angefasst.

**iOS:** keine Änderung nötig. Der native „Verlauf" ist action-whitelisted;
eine unbekannte Action wird schlicht nicht angezeigt. Da `poster_printed` eine
**neue** Action mit **neuen** Metadata-Keys ist, wird kein bestehender
Typvertrag verändert – die iOS-Metadata-Struct bleibt gültig.

## Nicht in v1

- **PDF-Export.** Der Browser-Druckdialog bietet „Als PDF sichern" an. Ein
  zweiter Renderpfad (jsPDF) würde jedes Motiv doppelt pflegen lassen und
  auseinanderdriften.
- **Kurz-URL** (`vmflow.app/a/AB12CD`). Braucht öffentliche
  Redirect-Infrastruktur, die es nicht gibt.
- **iOS nativ.** Später ist der günstigste Weg, die `/print`-Route in einem
  `WKWebView` zu laden und daraus über `UIPrintInteractionController` zu
  drucken – dann teilen beide Clients dieselben Motive, statt sie in SwiftUI
  nachzubauen.
- **Freier Motiv-Editor** mit wählbaren Farben. Motive sind fix.
- **Veraltungs-Hinweis** („Kontaktdaten wurden nach dem letzten Druck
  geändert"). Fachlich der wertvollste Folgeschritt – Nummer gewechselt, 40
  Aushänge zeigen die alte, niemand ruft mehr an – aber eigenständig genug für
  v1.1. Der `poster_printed`-Eintrag ist bewusst schon in v1 enthalten, damit
  die Datengrundlage existiert, sobald man den Hinweis bauen will.

## Tests

Vitest, in `app/composables/__tests__/` bzw. `app/lib/__tests__/`. Getestet
werden die reinen Funktionen, nicht das Rendering:

- `isPublicOrigin()` – localhost, private Bereiche, `.local`, abweichende
  Ports, echte https-Domain.
- Kontaktauflösung `machine.x ?? company.x`, inklusive leerem String als
  „nicht gesetzt".
- `buildPrintSheet()` – `missing` korrekt befüllt; abgeschaltete Bausteine
  ergeben `null` statt leerem String; fehlende Telefonnummer schaltet
  `qr.tel` auf `null`.
- Telefon-Normalisierung für `tel:` und `wa.me` (Leerzeichen, `/`, `(0)`,
  führende `+`).
- Aufkleber-Verteilung über Bögen bei n Automaten.

## Umsetzungsreihenfolge

1. Migration + `config.toml`-Bucket + `SITE_URL`-Durchreichung.
2. `useMachinePrint()` + `PrintSheet` + Tests (noch ohne UI).
3. Motiv `klar` + Print-CSS + Route + Druckknopf auf der Automatenseite.
4. Restliche Motive.
5. Aufkleberbogen + Mehrfachnutzen.
6. Sammel-Druck.
7. Einstellungen (WhatsApp, Zeiten, Logo) + Automaten-Overrides.
8. `feedback=problem` auf der öffentlichen Seite.
9. `poster_printed` + Activity-Descriptor.
10. i18n für alle vier Locales.

Schritt 3 ist der erste Punkt, an dem ein Blatt aus dem Drucker kommt – ab da
ist alles danach additiv.
