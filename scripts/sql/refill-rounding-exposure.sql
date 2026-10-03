-- Wie weit reicht die iOS-Rundungsabweichung zurück?
--
-- Hintergrund: `startTour` auf iOS hat eine reduzierte Packmenge auf die
-- Fächer eines Produkts verteilt, indem es JEDE Fachquote einzeln rundete,
-- während das Lager mit der ungeteilten Menge belastet wurde. Zwei Fächer mit
-- Bedarf 5 und einer gepinnten Menge von 7 wurden zu 4 + 4 — acht Einheiten
-- in die Maschine gebucht gegen sieben abgebuchte. Umgekehrt genauso: drei
-- Fächer mit Bedarf 3 und Menge 1 wurden zu 0 + 0 + 0, das Lager zahlte für
-- eine Einheit, die die Maschine nie bekam.
-- Behoben in `fix(ios): distribute a reduced pack quantity without inventing
-- units`. Android war nie betroffen (Largest-Remainder + Eigenschaftstest),
-- die PWA auch nicht (sie verteilt greedy, ohne zu runden).
--
-- Diese Datei MISST nur. Sie ändert nichts. Vor irgendeiner Korrektur
-- historischer Bestände: erst lesen, dann entscheiden.
--
-- Ausführen z. B. mit:
--   psql "$DATABASE_URL" -f scripts/sql/refill-rounding-exposure.sql
--   docker compose exec -T db psql -U postgres -d postgres -f - < scripts/sql/refill-rounding-exposure.sql

\echo '== A) Genau messbar: Touren seit dem 11.05.2026 =='
-- `refill_tour_tray_applications` gibt es erst seit Migration
-- 20260511120000. Davor buchte der Wizard mit einzelnen UPDATEs und hinterließ
-- keine Zeile je Fach — für ältere Touren siehe Abfrage B.
--
-- Verglichen wird je Tour + Maschine + Produkt:
--   belastet = was `deduct_warehouse_stock_fifo` vom Lager genommen hat
--   gebucht  = was tatsächlich in die Fächer geschrieben wurde
-- Beschränkt auf Produkte, die in DERSELBEN Maschine in MEHREREN Fächern
-- liegen — nur dort konnte die alte Arithmetik überhaupt danebenliegen.

WITH charged AS (
  SELECT
    wt.metadata->>'tour_id' AS tour_id,
    wt.reference_id         AS machine_id,
    wt.product_id,
    SUM(-wt.quantity_change) AS charged_qty       -- quantity_change ist negativ
  FROM public.warehouse_transactions wt
  WHERE wt.transaction_type = 'outgoing_refill'
    AND wt.metadata->>'tour_id' IS NOT NULL
  GROUP BY 1, 2, 3
),
booked AS (
  SELECT
    a.tour_id,
    mt.machine_id::text     AS machine_id,
    mt.product_id,
    SUM(a.new_stock - a.old_stock) AS booked_qty,
    COUNT(*)                       AS trays_touched
  FROM public.refill_tour_tray_applications a
  JOIN public.machine_trays mt ON mt.id = a.tray_id
  GROUP BY 1, 2, 3
),
multi_tray AS (                                    -- die Rundungs-Signatur
  SELECT machine_id::text AS machine_id, product_id
  FROM public.machine_trays
  WHERE product_id IS NOT NULL
  GROUP BY 1, 2
  HAVING COUNT(*) > 1
)
SELECT
  c.tour_id,
  c.machine_id,
  p.name                                   AS produkt,
  c.charged_qty                            AS belastet,
  COALESCE(b.booked_qty, 0)                AS gebucht,
  COALESCE(b.booked_qty, 0) - c.charged_qty AS differenz,   -- >0: Maschine bekam mehr als das Lager gab
  b.trays_touched                          AS faecher
FROM charged c
JOIN multi_tray m ON m.machine_id = c.machine_id AND m.product_id = c.product_id
LEFT JOIN booked b
       ON b.tour_id = c.tour_id AND b.machine_id = c.machine_id AND b.product_id = c.product_id
LEFT JOIN public.products p ON p.id = c.product_id
WHERE COALESCE(b.booked_qty, 0) <> c.charged_qty
ORDER BY abs(COALESCE(b.booked_qty, 0) - c.charged_qty) DESC, c.tour_id;

\echo ''
\echo '== A2) Summe der Abweichung, gleiche Grundmenge =='
WITH charged AS (
  SELECT wt.metadata->>'tour_id' AS tour_id, wt.reference_id AS machine_id,
         wt.product_id, SUM(-wt.quantity_change) AS charged_qty
  FROM public.warehouse_transactions wt
  WHERE wt.transaction_type = 'outgoing_refill' AND wt.metadata->>'tour_id' IS NOT NULL
  GROUP BY 1, 2, 3
),
booked AS (
  SELECT a.tour_id, mt.machine_id::text AS machine_id, mt.product_id,
         SUM(a.new_stock - a.old_stock) AS booked_qty
  FROM public.refill_tour_tray_applications a
  JOIN public.machine_trays mt ON mt.id = a.tray_id
  GROUP BY 1, 2, 3
),
multi_tray AS (
  SELECT machine_id::text AS machine_id, product_id
  FROM public.machine_trays WHERE product_id IS NOT NULL
  GROUP BY 1, 2 HAVING COUNT(*) > 1
)
SELECT
  COUNT(*)                                                  AS betroffene_faelle,
  COUNT(DISTINCT c.tour_id)                                 AS betroffene_touren,
  SUM(GREATEST(COALESCE(b.booked_qty,0) - c.charged_qty, 0)) AS zuviel_gebucht,
  SUM(GREATEST(c.charged_qty - COALESCE(b.booked_qty,0), 0)) AS zuwenig_gebucht
FROM charged c
JOIN multi_tray m ON m.machine_id = c.machine_id AND m.product_id = c.product_id
LEFT JOIN booked b ON b.tour_id = c.tour_id AND b.machine_id = c.machine_id AND b.product_id = c.product_id
WHERE COALESCE(b.booked_qty, 0) <> c.charged_qty;

\echo ''
\echo '== B) Grob, dafür über die ganze Historie: je Tour belastet vs. gemeldet =='
-- Für Touren VOR dem 11.05.2026 gibt es keine Zeile je Fach. Die einzige
-- Buchungsseite ist dann `activity_log.metadata->>'total_added'` je Maschine.
-- Diese Abfrage findet Drift, kann sie aber nicht der Rundung zuordnen —
-- siehe die Vorbehalte unten.
WITH charged AS (
  SELECT wt.metadata->>'tour_id' AS tour_id, SUM(-wt.quantity_change) AS belastet
  FROM public.warehouse_transactions wt
  WHERE wt.transaction_type = 'outgoing_refill' AND wt.metadata->>'tour_id' IS NOT NULL
  GROUP BY 1
),
reported AS (
  SELECT al.metadata->>'tour_id' AS tour_id,
         SUM((al.metadata->>'total_added')::int) AS gemeldet,
         MIN(al.created_at)                      AS tour_start
  FROM public.activity_log al
  WHERE al.action = 'stock_refill_tour'
    AND al.metadata->>'tour_id' IS NOT NULL
  GROUP BY 1
)
SELECT r.tour_start::date AS tag, c.tour_id, c.belastet, r.gemeldet,
       r.gemeldet - c.belastet AS differenz
FROM charged c
JOIN reported r USING (tour_id)
WHERE r.gemeldet <> c.belastet
ORDER BY r.tour_start DESC;

\echo ''
\echo '== Vorbehalte: was eine Differenz NICHT beweist =='
\echo '1. Der Fahrer darf Mengen nach dem Tourstart noch hoch- oder runterdrehen.'
\echo '   Das erzeugt dieselbe Differenz und ist kein Fehler.'
\echo '2. Eine übersprungene Maschine lässt das Lager belastet, ohne zu buchen —'
\echo '   das ist die bewusst einseitige Bilanz (Restrisiko 1 im 5a-Plan),'
\echo '   nicht die Rundung.'
\echo '3. Eine über die Fachkapazität hinaus gepinnte Menge füllt nur bis'
\echo '   Kapazität. Auch hier: gewollt, nicht die Rundung.'
\echo 'Die Rundungssignatur ist eine KLEINE Differenz (meist ±1 bis ±2) bei einem'
\echo 'Produkt mit mehreren Fächern in einer Maschine und reduzierter Packmenge.'
\echo 'Abfrage A grenzt genau darauf ein; B ist nur ein Netz für die Zeit davor.'
\echo '4. Die Mehr-Fach-Eingrenzung in A liest die HEUTIGE Fachbelegung. Wird ein'
\echo '   Fach später umbelegt (der Review-Schritt tut genau das), kann A einen'
\echo '   alten Fall übersehen oder einen heutigen Fall einer alten Tour zuordnen.'
\echo '5. Ungeprüft gegen eine laufende Datenbank — beim ersten Lauf mit einem'
\echo '   LIMIT anfangen und die Spaltennamen gegen die Migrationen halten.'
