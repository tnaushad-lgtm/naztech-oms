-- ============================================================================
--  Bond tickers for testing the yield-basis order ticket.
--
--  Why this file exists: the nFIX rebuild (rebuild_from_nfix.sql /
--  import_dse_securities.py) repopulates `security` from the venue's equity
--  list, which has no debt instruments in it — so the three bonds seeded by
--  bond_upgrade.sql / bond_dse_v2_upgrade.sql get wiped and the OrderTicket's
--  PRICE/YIELD toggle has nothing to appear on. Re-run this after any rebuild.
--
--  Safe to re-run: upserts by (exchange_id, symbol) on fixed ids 9101-9106.
--
--  Coverage — each row exercises a different branch of BondMath:
--    9101 TB05Y2030   semi-annual, mid-tenor      general PV formula (N > 1)
--    9102 TB10Y2034   semi-annual, long           general PV formula (N > 1)
--    9103 TB20Y2044   semi-annual, very long      duration/convexity sensitivity
--    9104 BEXGSUKUK   semi-annual sukuk           shariah instrument, discount to par
--    9105 BRACSUBORD  annual coupon (freq = 1)    non-semi-annual schedule
--    9106 PBLPBOND    perpetual (maturity NULL)   consol formula, price = c/y
--
--  TB10Y2034, BEXGSUKUK and PBLPBOND are NOT free choices: they are the fixtures the
--  codebase already pins down, and their attributes must match or tests fail against
--  the live database. The canonical values live in
--    - exchange/fixsim/ReferencePrices.java  (TB10Y2034 99.85, BEXGSUKUK 96.50, PBLPBOND 4980.00)
--    - service/BondMathTest.java:113         (PBLPBOND face 5000, coupon 10%, perpetual)
--    - OmsFlowTest.java:259                  (TB10Y2034 8.5% semi-annual, reference ~99.82)
--    - AiOrderServiceTest.java:45            ("buy PBLPBOND 10 at 9% yield" must resolve)
--  Change a coupon here and you move a clean price; move it far enough and the RMS bond
--  price band (+/-10% off ycp) rejects the very order those tests place.
--
--  Board taxonomy follows the DSE "Bond Trading with Yield" BRS V2 §1.1.4/§1.1.5:
--  standardized coupon bonds and sukuk that trade on yield -> YIELDDBT;
--  perpetuals (PRPTL) -> ALTDBT, where they trade like equity.
--
--  Symbols: BEXGSUKUK and IBBLPBOND are real DSE-listed debt securities. The
--  TBxxYyyyy treasury codes and BRACSUBORD are representative test instruments
--  in DSE's shape, not literal exchange tickers.
-- ============================================================================
USE dse_oms;

INSERT INTO security
  (id, exchange_id, symbol, name, asset_class, board, sector,
   face_value, lot_size, tick_size, market_lot, status, category, is_shariah,
   coupon_rate, coupon_freq, maturity_date, issue_date, total_issued, day_count)
VALUES
  (9101, 1, 'TB05Y2030',  '5-Year Bangladesh Govt Treasury Bond 2030',  'GOVT_BOND', 'YIELDDBT', 'Treasury',
   100.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 0, 10.8500, 2, '2030-06-30', '2025-06-30', 40000000, 'ACT/ACT'),

  -- 8.50% semi-annual: pinned by OmsFlowTest and ReferencePrices, not a free choice.
  (9102, 1, 'TB10Y2034',  '10-Year Bangladesh Govt Treasury Bond 2034', 'GOVT_BOND', 'YIELDDBT', 'Treasury',
   100.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 0,  8.5000, 2, '2034-06-30', '2024-06-30', 50000000, 'ACT/ACT'),

  (9103, 1, 'TB20Y2044',  '20-Year Bangladesh Govt Treasury Bond 2044', 'GOVT_BOND', 'YIELDDBT', 'Treasury',
   100.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 0, 12.1000, 2, '2044-06-30', '2024-06-30', 25000000, 'ACT/ACT'),

  (9104, 1, 'BEXGSUKUK',  'Beximco Green Sukuk Al Istisna''a',          'SUKUK',     'YIELDDBT', 'Corporate Sukuk',
   100.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 1,  9.0000, 2, '2029-12-31', '2022-12-31', 30000000, 'ACT/ACT'),

  (9105, 1, 'BRACSUBORD', 'BRAC Bank Subordinated Bond IV',             'CORP_BOND', 'YIELDDBT', 'Bank Debt',
   100.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 0, 11.2500, 1, '2029-09-30', '2022-09-30', 8000000,  'ACT/ACT'),

  -- Face 5000 and a 10% perpetual coupon: pinned by BondMathTest; AiOrderServiceTest needs the row
  -- to exist at all for "buy PBLPBOND 10 at 9% yield" to resolve to a symbol.
  (9106, 1, 'PBLPBOND',   'Pubali Bank Perpetual Bond',                 'CORP_BOND', 'ALTDBT',   'Bank Debt',
   5000.0000, 1, 0.0100, 1, 'ACTIVE', 'A', 0, 10.0000, 1, NULL,        '2021-06-30', 4000000,  'ACT/ACT')
AS new
ON DUPLICATE KEY UPDATE
  symbol        = new.symbol,
  name          = new.name,
  asset_class   = new.asset_class,
  board         = new.board,
  sector        = new.sector,
  face_value    = new.face_value,
  tick_size     = new.tick_size,
  market_lot    = new.market_lot,
  status        = new.status,
  is_shariah    = new.is_shariah,
  coupon_rate   = new.coupon_rate,
  coupon_freq   = new.coupon_freq,
  maturity_date = new.maturity_date,
  issue_date    = new.issue_date,
  total_issued  = new.total_issued,
  day_count     = new.day_count;

-- Reference prices. `ycp` is what the RMS bond price band (BRS §1.1.6, +/-10% by
-- default) measures a limit order against, so it must exist or every bond order is
-- judged against a zero reference and rejected.
--
-- TB10Y2034 / BEXGSUKUK / PBLPBOND take the values in ReferencePrices.java so the
-- database and the simulator agree on what these instruments are worth. The other
-- three are the engine's own clean price at a plausible market yield (govt ~11-12%,
-- corp ~12%), so a realistic order sits inside the band instead of looking like a
-- fat finger. bid/ask straddle by one tick.
INSERT INTO market_data
  (security_id, ltp, open_price, high_price, low_price, close_price, ycp, bid, ask,
   volume, trades, value_mn, change_pct, source)
VALUES
  (9101,   98.8936,   98.8936,   98.8936,   98.8936,   98.8936,   98.8936,   98.8436,   98.9436, 0,0,0,0,'SEED'),
  (9102,   99.8500,   99.8500,   99.8500,   99.8500,   99.8500,   99.8500,   99.8000,   99.9000, 0,0,0,0,'SEED'),
  (9103,   98.5454,   98.5454,   98.5454,   98.5454,   98.5454,   98.5454,   98.4954,   98.5954, 0,0,0,0,'SEED'),
  (9104,   96.5000,   96.5000,   96.5000,   96.5000,   96.5000,   96.5000,   96.4500,   96.5500, 0,0,0,0,'SEED'),
  (9105,   98.0055,   98.0055,   98.0055,   98.0055,   98.0055,   98.0055,   97.9555,   98.0555, 0,0,0,0,'SEED'),
  (9106, 4980.0000, 4980.0000, 4980.0000, 4980.0000, 4980.0000, 4980.0000, 4979.9500, 4980.0500, 0,0,0,0,'SEED')
AS new
ON DUPLICATE KEY UPDATE
  ltp = new.ltp, open_price = new.open_price, high_price = new.high_price,
  low_price = new.low_price, close_price = new.close_price, ycp = new.ycp,
  bid = new.bid, ask = new.ask, change_pct = 0, source = 'SEED';

-- Opening bond positions, so the SELL side is testable without having to buy
-- first. Without these the RMS correctly refuses every bond sell with
-- "Insufficient holdings to sell (have 0, ...)" — the short-sell guard, not a
-- bug, but it makes the yield ticket's sell path untestable out of the box.
-- Cost basis is set at par so unrealised P&L against the seeded reference
-- price is small and legible rather than a made-up windfall.
INSERT INTO holding (account_id, security_id, quantity, avg_cost)
SELECT a.id, s.id, 5000, 100.0000
FROM client_account a
CROSS JOIN security s
WHERE a.id IN (1, 2, 3) AND s.id BETWEEN 9101 AND 9106
ON DUPLICATE KEY UPDATE quantity = GREATEST(holding.quantity, 5000);

SELECT id, symbol, asset_class, board, face_value, coupon_rate, coupon_freq,
       maturity_date, day_count, is_shariah
FROM security WHERE asset_class LIKE '%BOND%' OR asset_class = 'SUKUK'
ORDER BY id;
