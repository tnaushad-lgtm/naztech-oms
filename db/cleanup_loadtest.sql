-- ============================================================================
--  Remove everything the throughput harness created, and put back what it moved.
--
--  Runs:  mysql -u root -p dse_oms < db/cleanup_loadtest.sql
--
--  Harness orders are identified by their audit trail — AuditService records the actor on every
--  placement, and the harness places as 'loadtest'. That is exact: it cannot catch a real order,
--  and it cannot miss a synthetic one. (Matching on price/account/security would do both.)
-- ============================================================================

-- The orders the harness placed.
CREATE TEMPORARY TABLE IF NOT EXISTS tmp_loadtest_orders (
  id BIGINT PRIMARY KEY
) ENGINE=Memory;

INSERT IGNORE INTO tmp_loadtest_orders (id)
SELECT DISTINCT CAST(entity_id AS UNSIGNED)
FROM audit_log
WHERE actor = 'loadtest' AND entity_type = 'ORDER' AND entity_id REGEXP '^[0-9]+$';

SELECT CONCAT('orders to remove: ', COUNT(*)) AS plan FROM tmp_loadtest_orders;

-- Children first (no FK from trade/order_event to oms_order, but order matters for sanity).
DELETE t FROM trade t
  JOIN tmp_loadtest_orders l ON l.id IN (t.buy_order_id, t.sell_order_id);

DELETE e FROM order_event e
  JOIN tmp_loadtest_orders l ON l.id = e.order_id;

DELETE o FROM oms_order o
  JOIN tmp_loadtest_orders l ON l.id = o.id;

DELETE FROM audit_log WHERE actor = 'loadtest';

-- The harness primed account 3's buying power to 100bn and its fills left a synthetic position.
-- Both go back to the seeded state (db/seed.sql:53).
UPDATE client_account
   SET buying_power = 50000000.00,
       cash_balance = 50000000.00,
       realized_pnl = 0
 WHERE bo_id = '1201010000003';

DELETE h FROM holding h
  JOIN client_account c ON c.id = h.account_id
 WHERE c.bo_id = '1201010000003';

-- Every harness fill marked the tape, so BRACBANK's snapshot drifted (LTP 63.70, volume 4.7m).
-- Restore the seeded quote (db/seed.sql:127). The live feed will move it again from here.
UPDATE market_data md
  JOIN security s ON s.id = md.security_id
  JOIN exchange x ON x.id = s.exchange_id AND x.code = 'DSE'
   SET md.ltp = 47.80, md.open_price = 47.20, md.high_price = 48.60, md.low_price = 46.90,
       md.ycp = 47.30, md.close_price = 47.30, md.volume = 2300000, md.trades = 3400,
       md.value_mn = 0, md.bid = 47.70, md.ask = 47.90, md.change_pct = 0, md.source = 'SEED'
 WHERE s.symbol = 'BRACBANK';

DROP TEMPORARY TABLE tmp_loadtest_orders;

SELECT CONCAT('orders left: ', COUNT(*)) AS result FROM oms_order;
SELECT CONCAT('trades left: ', COUNT(*)) AS result FROM trade;
