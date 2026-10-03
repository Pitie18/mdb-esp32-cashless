-- =========================================================
-- get_sales_analytics_summary — page header of the Analytics page.
--
-- Returns totals for a freely chosen window, the same totals for the
-- immediately preceding window of equal length, a gapless daily series,
-- a weekday x hour heatmap, the payment-channel split, and two data-quality
-- counters.
--
-- Purely additive: creates one function, touches no table and no existing
-- function. Old clients and old firmware are unaffected.
--
-- All day/hour bucketing happens in p_timezone, NOT in the session timezone.
-- Without that the heatmap would be shifted by the UTC offset and the daily
-- buckets would be cut at the wrong midnight.
-- =========================================================

CREATE OR REPLACE FUNCTION public.get_sales_analytics_summary(
  p_company_id   uuid,
  p_from         timestamptz,
  p_to           timestamptz,
  p_machine_ids  uuid[] DEFAULT NULL,
  p_category_ids uuid[] DEFAULT NULL,
  p_timezone     text   DEFAULT 'UTC'
)
RETURNS json
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_caller    uuid := public.my_company_id();
  v_span      interval;
  v_prev_from timestamptz;
  v_days      numeric;
  v_tz        text;
  v_result    json;
BEGIN
  IF v_caller IS NULL OR v_caller IS DISTINCT FROM p_company_id THEN
    RAISE EXCEPTION 'not authenticated or access denied';
  END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from THEN
    RAISE EXCEPTION 'invalid range';
  END IF;

  -- Validate the timezone; a client sending garbage must not break the page.
  BEGIN
    PERFORM now() AT TIME ZONE p_timezone;
    v_tz := p_timezone;
  EXCEPTION WHEN OTHERS THEN
    v_tz := 'UTC';
  END;

  v_span      := p_to - p_from;
  v_prev_from := p_from - v_span;
  v_days      := GREATEST(EXTRACT(epoch FROM v_span) / 86400.0, 1.0 / 86400.0);

  WITH machines AS (
    SELECT vm.id
    FROM public."vendingMachine" vm
    WHERE vm.company = p_company_id
      AND (p_machine_ids IS NULL
           OR cardinality(p_machine_ids) = 0
           OR vm.id = ANY(p_machine_ids))
  ),
  raw_sales AS (
    -- One pass over both windows; `is_current` separates them later.
    -- Legacy sales without a stamped product_id fall back to the slot's
    -- current product, exactly like get_machine_product_kpis does.
    SELECT s.id, s.created_at, s.item_price, s.price_net, s.channel,
           COALESCE(s.product_id, mt.product_id) AS product_id
    FROM public.sales s
    JOIN machines m ON m.id = s.machine_id
    LEFT JOIN public.machine_trays mt
      ON s.product_id IS NULL
     AND mt.machine_id = s.machine_id
     AND mt.item_number = s.item_number
    WHERE s.created_at >= v_prev_from
      AND s.created_at <  p_to
  ),
  priced AS (
    SELECT r.id, r.created_at, r.item_price, r.channel, r.product_id,
           (r.created_at >= p_from) AS is_current,
           p.category AS category_id,
           -- Net selling price: stamped where available, otherwise derived
           -- from the product's tax rate, otherwise the gross price.
           COALESCE(
             r.price_net,
             CASE WHEN tr.rate IS NOT NULL
                  THEN round(r.item_price::numeric / (1 + tr.rate), 4)
                  ELSE r.item_price::numeric
             END
           ) AS eff_net,
           ek.price_net AS cost_net
    FROM raw_sales r
    LEFT JOIN public.products p ON p.id = r.product_id
    LEFT JOIN LATERAL (
      SELECT public.resolve_product_tax_rate(
               r.product_id, (r.created_at AT TIME ZONE v_tz)::date) AS rate
    ) tr ON r.price_net IS NULL AND r.product_id IS NOT NULL
    LEFT JOIN LATERAL (
      -- The purchase price that was valid on the sale date: the newest
      -- notation at or before it. If the sale predates every notation, the
      -- oldest one is used — a slightly wrong basis beats a hole in the row.
      SELECT pp.price_net
      FROM public.product_purchase_prices pp
      WHERE pp.product_id = r.product_id
      ORDER BY (pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date) DESC,
               CASE WHEN pp.observed_on <= (r.created_at AT TIME ZONE v_tz)::date
                    THEN pp.observed_on END DESC NULLS LAST,
               pp.observed_on ASC
      LIMIT 1
    ) ek ON true
  ),
  fsales AS (
    SELECT * FROM priced
    WHERE (p_category_ids IS NULL
           OR cardinality(p_category_ids) = 0
           OR category_id = ANY(p_category_ids))
  ),
  cur AS (SELECT * FROM fsales WHERE is_current),
  prv AS (SELECT * FROM fsales WHERE NOT is_current),
  agg_cur AS (
    SELECT count(*)::bigint AS units,
           round(COALESCE(sum(item_price)::numeric, 0), 2)                             AS revenue_gross,
           round(COALESCE(sum(eff_net), 0), 2)                                         AS revenue_net,
           round(COALESCE(sum(cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2)     AS cost_net,
           round(COALESCE(sum(eff_net - cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS gross_profit,
           round(COALESCE(avg(item_price)::numeric, 0), 4)                             AS avg_ticket
    FROM cur
  ),
  agg_prv AS (
    SELECT count(*)::bigint AS units,
           round(COALESCE(sum(item_price)::numeric, 0), 2)                             AS revenue_gross,
           round(COALESCE(sum(eff_net), 0), 2)                                         AS revenue_net,
           round(COALESCE(sum(cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2)     AS cost_net,
           round(COALESCE(sum(eff_net - cost_net) FILTER (WHERE cost_net IS NOT NULL), 0), 2) AS gross_profit,
           round(COALESCE(avg(item_price)::numeric, 0), 4)                             AS avg_ticket
    FROM prv
  ),
  day_grid AS (
    SELECT generate_series(
             (p_from AT TIME ZONE v_tz)::date,
             ((p_to AT TIME ZONE v_tz) - interval '1 microsecond')::date,
             interval '1 day')::date AS day
  ),
  daily AS (
    -- Gapless on purpose: days without a sale must render as an empty bar,
    -- not silently collapse the chart.
    SELECT g.day,
           count(c.id)::bigint AS units,
           round(COALESCE(sum(c.item_price)::numeric, 0), 2) AS revenue_gross,
           round(COALESCE(sum(c.eff_net - c.cost_net) FILTER (WHERE c.cost_net IS NOT NULL), 0), 2) AS gross_profit
    FROM day_grid g
    LEFT JOIN cur c ON (c.created_at AT TIME ZONE v_tz)::date = g.day
    GROUP BY g.day
  ),
  heat AS (
    SELECT extract(isodow FROM (created_at AT TIME ZONE v_tz))::int AS dow,
           extract(hour   FROM (created_at AT TIME ZONE v_tz))::int AS hour,
           count(*)::bigint AS units,
           round(sum(item_price)::numeric, 2) AS revenue_gross
    FROM cur
    GROUP BY 1, 2
  ),
  chan AS (
    SELECT COALESCE(NULLIF(btrim(channel), ''), 'unknown') AS channel,
           count(*)::bigint AS units,
           round(sum(item_price)::numeric, 2) AS revenue_gross,
           round(avg(item_price)::numeric, 4) AS avg_ticket
    FROM cur
    GROUP BY 1
  )
  SELECT json_build_object(
    'range', json_build_object(
      'from', p_from, 'to', p_to,
      'previous_from', v_prev_from, 'previous_to', p_from,
      'days', round(v_days, 4), 'timezone', v_tz
    ),
    'totals', (SELECT json_build_object(
        'units', units, 'revenue_gross', revenue_gross, 'revenue_net', revenue_net,
        'cost_net', cost_net, 'gross_profit', gross_profit, 'avg_ticket', avg_ticket,
        'avg_daily_units',   round(units / v_days, 4),
        'avg_daily_revenue', round(revenue_gross / v_days, 4),
        'avg_daily_gross_profit', round(gross_profit / v_days, 4)
      ) FROM agg_cur),
    'previous', (SELECT json_build_object(
        'units', units, 'revenue_gross', revenue_gross, 'revenue_net', revenue_net,
        'cost_net', cost_net, 'gross_profit', gross_profit, 'avg_ticket', avg_ticket,
        'avg_daily_units',   round(units / v_days, 4),
        'avg_daily_revenue', round(revenue_gross / v_days, 4),
        'avg_daily_gross_profit', round(gross_profit / v_days, 4)
      ) FROM agg_prv),
    'daily', (SELECT COALESCE(json_agg(json_build_object(
        'day', day, 'units', units,
        'revenue_gross', revenue_gross, 'gross_profit', gross_profit
      ) ORDER BY day), '[]'::json) FROM daily),
    'heatmap', (SELECT COALESCE(json_agg(json_build_object(
        'dow', dow, 'hour', hour, 'units', units, 'revenue_gross', revenue_gross
      ) ORDER BY dow, hour), '[]'::json) FROM heat),
    'channels', (SELECT COALESCE(json_agg(json_build_object(
        'channel', channel, 'units', units,
        'revenue_gross', revenue_gross, 'avg_ticket', avg_ticket
      ) ORDER BY revenue_gross DESC), '[]'::json) FROM chan),
    'missing_cost_products', (
      SELECT count(DISTINCT product_id)::int FROM cur
      WHERE product_id IS NOT NULL AND cost_net IS NULL),
    'unknown_product_units', (
      SELECT count(*)::bigint FROM cur WHERE product_id IS NULL)
  ) INTO v_result;

  RETURN v_result;
END $$;

GRANT EXECUTE ON FUNCTION public.get_sales_analytics_summary(uuid, timestamptz, timestamptz, uuid[], uuid[], text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_sales_analytics_summary(uuid, timestamptz, timestamptz, uuid[], uuid[], text) TO service_role;
