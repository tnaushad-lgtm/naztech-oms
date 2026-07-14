-- ============================================================================
--  Repair the index board after the runaway drift.
--
--  ItchGateway.driftIndices() nudged each index by (nextDouble() - 0.48) * 0.0015
--  every tick. nextDouble() averages 0.5, so the mean step was POSITIVE — about
--  +0.003% per tick — and the result was written straight back to MySQL. Nothing
--  ever reset it, so each run resumed from the inflated figure and pushed on.
--
--  A tick every 1.2 seconds compounds +0.003% to roughly +9% an hour. Left open
--  across a few days, DSEX climbed from 5,240 to 52,110,405 — and the AI advisor,
--  reading the live data exactly as it was told to, quoted that to the dealer.
--
--  The drift itself is fixed in ItchGateway (centred step, anchored to YCP, hard
--  ±10% band). This restores the values it corrupted.
--
--  Safe to re-run.
-- ============================================================================
USE dse_oms;

UPDATE market_data m
JOIN security s ON s.id = m.security_id
JOIN (
  SELECT 'DSEX'  sym, 5240.65 ltp, 5210.30 op, 5258.90 hi, 5205.10 lo, 5218.40 cl UNION ALL
  SELECT 'DS30',      1985.20,     1975.00,    1992.40,    1972.10,    1978.60    UNION ALL
  SELECT 'DSES',      1142.80,     1138.00,    1147.30,    1136.20,    1139.50    UNION ALL
  SELECT 'CSCX',      9105.40,     9080.00,    9130.20,    9072.50,    9088.10    UNION ALL
  SELECT 'CASPI',    14820.60,    14780.00,   14860.30,   14770.10,   14795.40
) v ON v.sym = s.symbol
SET m.ltp         = v.ltp,
    m.open_price  = v.op,
    m.high_price  = v.hi,
    m.low_price   = v.lo,
    m.close_price = v.cl,
    m.ycp         = v.cl,
    m.change_pct  = ROUND((v.ltp - v.cl) / v.cl * 100, 2),
    m.source      = 'SEED',
    m.updated_at  = NOW()
WHERE s.asset_class = 'INDEX';

SELECT s.symbol, m.ltp, m.ycp, m.change_pct
FROM market_data m JOIN security s ON s.id = m.security_id
WHERE s.asset_class = 'INDEX'
ORDER BY s.symbol;
