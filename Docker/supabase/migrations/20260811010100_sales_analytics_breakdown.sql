-- =========================================================
-- get_sales_analytics_breakdown — the switchable breakdown of the
-- Analytics page: sales aggregated per product, per category or per machine
-- for the same filters as get_sales_analytics_summary.
--
-- share_pct / cumulative_share_pct / abc_class are ALWAYS revenue-based, so
-- a row's Pareto class does not change when the user switches the displayed
-- metric. The share bar in the UI is computed client-side from the selected
-- metric and is deliberately a different thing.
--
-- The array is never truncated: the breakdown must stay sum-consistent with
-- the KPI row above it.
--
-- Purely additive. Creates one function, touches nothing else.
-- =========================================================

CREATE OR REPLACE FUNCTION public.get_sales_analytics_breakdown(
  p_company_id   uuid,
  p_from         timestamptz,
  p_to           timestamptz,
  p_machine_ids  uuid[] DEFAULT NULL,
  p_category_ids uuid[] DEFAULT NULL,
  p_timezone     text   DEFAULT 'UTC',
  p_dimension    text   DEFAULT 'product',
  p_product_id   uuid   DEFAULT NULL
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
  v_weeks     numeric;
  v_tz        text;
  v_result    json;
BEGIN
  IF v_caller IS NULL OR v_caller IS DISTINCT FROM p_company_id THEN
    RAISE EXCEPTION 'not authenticated or access denied';
  END IF;
  IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from THEN
    RAISE EXCEPTION 'invalid range';
  END IF;
  IF p_dimension IS NULL OR p_dimension NOT IN ('product', 'category', 'machine') THEN
    RAISE EXCEPTION 'invalid dimension: %', p_dimension;
  END IF;

  BEGIN
    PERFORM now() AT TIME ZONE p_timezone;
    v_tz := p_timezone;
  EXCEPTION WHEN OTHERS THEN
    v_tz := 'UTC';
  END;

  v_span      := p_to - p_from;
  v_prev_from := p_from - v_span;
  v_days      := GREATEST(EXTRACT(epoch FROM v_span) / 86400.0, 1.0 / 86400.0);
  v_weeks     := GREATEST(v_days / 7.0, 1.0 / 604800.0);

  WITH machines AS (
    SELECT vm.id
    FROM public."vendingMachine" vm
    WHERE vm.company = p_company_id
      AND (p_machine_ids IS NULL
           OR cardinality(p_machine_ids) = 0
           OR vm.id = ANY(p_machine_ids))
  ),
  raw_sales AS (
    SELECT s.id, s.created_at, s.item_price, s.price_net, s.machine_id,
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
    SELECT r.id, r.created_at, r.item_price, r.machine_id, r.product_id,
           (r.created_at >= p_from) AS is_current,
           p.category AS category_id,
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
      AND (p_product_id IS NULL OR product_id = p_product_id)
  ),
  keyed AS (
    SELECT CASE p_dimension
             WHEN 'product'  THEN product_id
             WHEN 'category' THEN category_id
             ELSE machine_id
           END AS key,
           is_current, item_price, eff_net, cost_net
    FROM fsales
  ),
  sales_agg AS (
    SELECT key,
           count(*) FILTER (WHERE is_current)::bigint AS units,
           round(COALESCE(sum(item_price) FILTER (WHERE is_current)::numeric, 0), 2) AS revenue_gross,
           round(COALESCE(sum(eff_net)    FILTER (WHERE is_current), 0), 2)          AS revenue_net,
           round(COALESCE(sum(eff_net - cost_net)
                          FILTER (WHERE is_current AND cost_net IS NOT NULL), 0), 2) AS gross_profit,
           count(*) FILTER (WHERE is_current AND cost_net IS NULL)::bigint           AS units_without_cost,
           count(*) FILTER (WHERE NOT is_current)::bigint AS prev_units,
           round(COALESCE(sum(item_price) FILTER (WHERE NOT is_current)::numeric, 0), 2) AS prev_revenue_gross,
           round(COALESCE(sum(eff_net - cost_net)
                          FILTER (WHERE NOT is_current AND cost_net IS NOT NULL), 0), 2) AS prev_gross_profit
    FROM keyed
    GROUP BY key
  ),
  trays AS (
    SELECT mt.machine_id, mt.product_id, p.category AS category_id,
           mt.capacity, mt.current_stock
    FROM public.machine_trays mt
    JOIN machines m ON m.id = mt.machine_id
    LEFT JOIN public.products p ON p.id = mt.product_id
    WHERE mt.product_id IS NOT NULL
      AND (p_category_ids IS NULL
           OR cardinality(p_category_ids) = 0
           OR p.category = ANY(p_category_ids))
      AND (p_product_id IS NULL OR mt.product_id = p_product_id)
  ),
  tray_agg AS (
    SELECT CASE p_dimension
             WHEN 'product'  THEN product_id
             WHEN 'category' THEN category_id
             ELSE machine_id
           END AS key,
           COALESCE(sum(capacity), 0)::bigint      AS total_capacity,
           COALESCE(sum(current_stock), 0)::bigint AS total_stock,
           count(DISTINCT machine_id)::int         AS machine_count,
           count(DISTINCT product_id)::int         AS product_count
    FROM trays
    GROUP BY 1
  ),
  all_keys AS (
    SELECT key FROM sales_agg
    UNION
    SELECT key FROM tray_agg
  ),
  labelled AS (
    SELECT k.key,
           CASE p_dimension
             WHEN 'product'  THEN COALESCE(pr.name, 'Unknown')
             WHEN 'category' THEN COALESCE(pc.name, 'Unknown')
             ELSE COALESCE(vm.name, 'Unknown')
           END AS label,
           CASE WHEN p_dimension = 'product' THEN pr.image_path END AS image_path
    FROM all_keys k
    LEFT JOIN public.products         pr ON p_dimension = 'product'  AND pr.id = k.key
    LEFT JOIN public.product_category pc ON p_dimension = 'category' AND pc.id = k.key
    LEFT JOIN public."vendingMachine" vm ON p_dimension = 'machine'  AND vm.id = k.key
  ),
  joined AS (
    SELECT l.key, l.label, l.image_path,
           COALESCE(sa.units, 0)              AS units,
           COALESCE(sa.revenue_gross, 0)      AS revenue_gross,
           COALESCE(sa.revenue_net, 0)        AS revenue_net,
           COALESCE(sa.gross_profit, 0)       AS gross_profit,
           COALESCE(sa.units_without_cost, 0) AS units_without_cost,
           COALESCE(sa.prev_units, 0)         AS prev_units,
           COALESCE(sa.prev_revenue_gross, 0) AS prev_revenue_gross,
           COALESCE(sa.prev_gross_profit, 0)  AS prev_gross_profit,
           COALESCE(ta.total_capacity, 0)     AS total_capacity,
           COALESCE(ta.total_stock, 0)        AS total_stock,
           COALESCE(ta.machine_count, 0)      AS machine_count,
           COALESCE(ta.product_count, 0)      AS product_count
    FROM labelled l
    LEFT JOIN sales_agg sa ON sa.key IS NOT DISTINCT FROM l.key
    LEFT JOIN tray_agg  ta ON ta.key IS NOT DISTINCT FROM l.key
  ),
  ranked AS (
    SELECT j.*,
           NULLIF(sum(revenue_gross) OVER (), 0) AS total_revenue,
           sum(revenue_gross) OVER (ORDER BY revenue_gross DESC, label ASC
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_revenue,
           -- Cumulative share of everything ABOVE this row. Classifying on the
           -- inclusive share would push a single dominant product out of A
           -- purely because of its own weight.
           sum(revenue_gross) OVER (ORDER BY revenue_gross DESC, label ASC
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_revenue
    FROM joined j
  )
  SELECT COALESCE(json_agg(json_build_object(
    'key',   key,
    'label', label,
    'image_path', image_path,
    'units', units,
    'revenue_gross', revenue_gross,
    'revenue_net',   revenue_net,
    'gross_profit',  gross_profit,
    'prev_units', prev_units,
    'prev_revenue_gross', prev_revenue_gross,
    'prev_gross_profit',  prev_gross_profit,
    'share_pct', CASE WHEN total_revenue IS NULL THEN 0
                      ELSE round(revenue_gross / total_revenue * 100, 2) END,
    'cumulative_share_pct', CASE WHEN total_revenue IS NULL THEN 0
                                 ELSE round(cum_revenue / total_revenue * 100, 2) END,
    'abc_class', CASE
                   WHEN total_revenue IS NULL THEN 'C'
                   WHEN COALESCE(prior_revenue, 0) / total_revenue * 100 < 80 THEN 'A'
                   WHEN COALESCE(prior_revenue, 0) / total_revenue * 100 < 95 THEN 'B'
                   ELSE 'C' END,
    'avg_daily_units',        round(units / v_days, 4),
    'avg_daily_revenue',      round(revenue_gross / v_days, 4),
    'avg_daily_gross_profit', round(gross_profit / v_days, 4),
    'total_capacity', total_capacity,
    'total_stock',    total_stock,
    'sell_through_pct', CASE WHEN total_capacity > 0
      THEN LEAST(round(units / (total_capacity * v_weeks) * 100, 2), 100) END,
    'days_of_supply',   CASE WHEN units > 0
      THEN round(total_stock / (units / v_days), 1) END,
    'machine_count', machine_count,
    'product_count', product_count,
    'has_cost', (units > 0 AND units_without_cost = 0)
  ) ORDER BY revenue_gross DESC, label ASC), '[]'::json)
  INTO v_result
  FROM ranked;

  RETURN v_result;
END $$;

GRANT EXECUTE ON FUNCTION public.get_sales_analytics_breakdown(uuid, timestamptz, timestamptz, uuid[], uuid[], text, text, uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_sales_analytics_breakdown(uuid, timestamptz, timestamptz, uuid[], uuid[], text, text, uuid) TO service_role;
