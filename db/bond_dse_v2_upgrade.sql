-- ============================================================================
--  Bond Trading with Yield, round 2 — bring the OMS up to the DSE BRS
--  ("Bond Trading with Yield", V2 23-05-2026). Run once against dse_oms,
--  AFTER bond_upgrade.sql. Safe to inspect before running; not safe to re-run
--  (the ALTERs will fail second time — that is your signal it is already in).
--
--  What this adds and why:
--
--  1. trade.accrued_interest / trade.trade_yield — BRS §1.1.2 says accrued
--     interest is "calculated and displayed on every trade (allowing the
--     settlement price to be easily calculated)", and §1.1.8 puts it on the
--     FIX Execution Report. Until now we computed accrued for the ticket and
--     then threw it away: nothing downstream could reconstruct what actually
--     changes hands at settlement (clean + accrued).
--
--  2. security.issue_date / total_issued / day_count — the §1.2 configuration
--     attributes a bond must carry. day_count records the convention the BRS
--     requires: Actual/Actual.
--
--  3. The DSE board taxonomy (§1.1.4/§1.1.5): standardized coupon bonds and
--     sukuk that trade with yield live on YIELDDBT; the perpetual (PRPTL
--     product) lives on ALTDBT and, per the BRS, trades like equity.
-- ============================================================================
USE dse_oms;

ALTER TABLE trade
  ADD COLUMN accrued_interest DECIMAL(14,4) NULL,   -- per unit, at settlement; NULL for equities
  ADD COLUMN trade_yield      DECIMAL(9,4)  NULL;   -- yield implied by the traded clean price

ALTER TABLE security
  ADD COLUMN issue_date   DATE NULL,
  ADD COLUMN total_issued BIGINT NULL,
  ADD COLUMN day_count    VARCHAR(12) NOT NULL DEFAULT 'ACT/ACT';

-- Boards per the BRS: yield-traded standardized bonds on YIELDDBT, perpetuals on ALTDBT.
UPDATE security SET board = 'YIELDDBT' WHERE symbol IN ('TB10Y2034', 'BEXGSUKUK');
UPDATE security SET board = 'ALTDBT'   WHERE symbol = 'PBLPBOND';

-- §1.2 configuration for the seeded bonds.
UPDATE security SET issue_date = '2024-06-30', total_issued = 50000000 WHERE symbol = 'TB10Y2034';
UPDATE security SET issue_date = '2022-12-31', total_issued = 30000000 WHERE symbol = 'BEXGSUKUK';
UPDATE security SET issue_date = '2021-06-30', total_issued = 4000000  WHERE symbol = 'PBLPBOND';

SELECT symbol, board, coupon_rate, coupon_freq, maturity_date, issue_date, total_issued, day_count
FROM security WHERE asset_class IN ('GOVT_BOND','CORP_BOND','SUKUK') ORDER BY symbol;
