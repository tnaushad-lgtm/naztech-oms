-- ============================================================================
--  Indexes for the order hot path.
--  Run once against the dse_oms schema:  mysql -u root -p dse_oms < db/perf_indexes.sql
--
--  Measured on the throughput harness (Exchange Link -> Throughput Test), the pre-trade risk
--  checks cost ~9.7ms of the ~40ms an order takes. A big part of that is the wash-trade guard,
--  which asks: "does this client already have an opposite-side order resting in this security?"
--  It is asked on EVERY order, against oms_order — the table growing fastest under load.
--
--  The existing idx_o_book (security_id, side, status) cannot serve it: the query filters on
--  account_id first, so MySQL scans every order in the security and filters by hand. This index
--  matches the query's actual shape and turns the scan into a lookup.
-- ============================================================================

CREATE INDEX idx_o_wash ON oms_order (account_id, security_id, side, status);

-- Trades for an order: buy_order_id / sell_order_id had no index at all, so the blotter's
-- "fills for this order" lookup scanned the whole trade table.
CREATE INDEX idx_t_buy_order  ON trade (buy_order_id);
CREATE INDEX idx_t_sell_order ON trade (sell_order_id);
