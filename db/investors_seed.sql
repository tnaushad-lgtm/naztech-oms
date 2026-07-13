-- ============================================================================
--  500 retail investors for the house broker, for credit-control / limit testing.
--
--  Run:  mysql -u root -p dse_oms < db/investors_seed.sql
--  Safe to re-run: upserts on (broker_id, bo_id) and (scope, entity_id) — it will not duplicate.
--
--  Each investor gets a BO account, a buying-power tier, and a CLIENT-scope risk_limit row scaled
--  to that tier — so the parent -> child -> sub-child limit-inheritance rules of the CCD / credit
--  control module have a realistic population to be tested against, rather than the six demo
--  accounts.
--
--  Tiers (by design, not by accident — they are what makes the limit tests interesting):
--    RETAIL       60%   BDT   1.2 lakh  –   9 lakh    buying power
--    HNI          30%   BDT  12 lakh    –  90 lakh
--    INSTITUTION  10%   BDT   2 crore   –  15 crore
--
--  BO ids use the 1202 DP prefix so they can never collide with the six seeded demo accounts
--  (1201…), and are scattered rather than sequential, as real BO ids are.
-- ============================================================================

SET @broker := (SELECT id FROM broker WHERE trec_code = 'TREC-101');   -- the house broker (DSE)

-- ---------------------------------------------------------------- the investors
INSERT INTO client_account (broker_id, bo_id, name, cash_balance, buying_power, realized_pnl, status)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 500
)
SELECT
  @broker,
  CONCAT('1202', LPAD(1000 + (n * 7) % 9000, 4, '0'),
                 LPAD((n * 8191 + 100003) % 100000000, 8, '0')) AS bo_id,
  CONCAT(
    ELT(1 + (n * 3) % 20,
        'Ayesha','Kamrul','Rumana','Tanvir','Shirin','Mahfuz','Nusrat','Rakib','Farhana','Imran',
        'Sabrina','Arif','Tahmina','Shakil','Nafisa','Rezaul','Sadia','Mizanur','Rubina','Ashraful'),
    ' ',
    ELT(1 + (n * 11) % 15,
        'Rahman','Islam','Haque','Ahmed','Akter','Chowdhury','Hossain','Khan','Karim','Siddiqui',
        'Alam','Bhuiyan','Mollah','Sarker','Uddin')) AS name,
  -- buying power by tier, varied within the tier so no two investors look identical
  CASE
    WHEN n % 10 = 9 THEN 20000000 + (n * 260000) % 130000000     -- INSTITUTION  2cr – 15cr
    WHEN n % 10 >= 6 THEN 1200000 + (n * 17000)  % 7800000       -- HNI         12l – 90l
    ELSE                  120000 + (n * 1700)   % 780000         -- RETAIL     1.2l – 9l
  END AS cash_balance,
  CASE
    WHEN n % 10 = 9 THEN 20000000 + (n * 260000) % 130000000
    WHEN n % 10 >= 6 THEN 1200000 + (n * 17000)  % 7800000
    ELSE                  120000 + (n * 1700)   % 780000
  END AS buying_power,
  0,
  'ACTIVE'
FROM seq
ON DUPLICATE KEY UPDATE
  name         = VALUES(name),
  cash_balance = VALUES(cash_balance),
  buying_power = VALUES(buying_power),
  status       = 'ACTIVE';

-- ---------------------------------------------------------------- their credit limits
-- Derived from the investor's own buying power, so a limit test on any of them is meaningful:
--   single order   <= 10% of buying power        (fat-finger / oversized-clip control)
--   gross exposure <= 2x buying power            (margin headroom)
--   MTM stop       <= 15% of buying power        (loss cut-out)
INSERT INTO risk_limit (scope, entity_id, max_order_value, max_order_qty,
                        max_gross_exposure, mtm_loss_limit, wash_sale_block, enabled)
SELECT
  'CLIENT',
  c.id,
  ROUND(c.buying_power * 0.10, 2),
  GREATEST(FLOOR(c.buying_power * 0.10 / 50), 100),      -- ~10% of BP at a nominal BDT 50/share
  ROUND(c.buying_power * 2.00, 2),
  ROUND(c.buying_power * 0.15, 2),
  1,
  1
FROM client_account c
WHERE c.bo_id LIKE '1202%'
ON DUPLICATE KEY UPDATE
  max_order_value    = VALUES(max_order_value),
  max_order_qty      = VALUES(max_order_qty),
  max_gross_exposure = VALUES(max_gross_exposure),
  mtm_loss_limit     = VALUES(mtm_loss_limit),
  enabled            = 1;

-- ---------------------------------------------------------------- what we made
SELECT
  CASE
    WHEN buying_power >= 20000000 THEN 'INSTITUTION'
    WHEN buying_power >= 1200000  THEN 'HNI'
    ELSE 'RETAIL'
  END                                        AS tier,
  COUNT(*)                                   AS investors,
  CONCAT(FORMAT(MIN(buying_power), 0), ' – ', FORMAT(MAX(buying_power), 0)) AS buying_power_range
FROM client_account
WHERE bo_id LIKE '1202%'
GROUP BY tier
ORDER BY MIN(buying_power);
