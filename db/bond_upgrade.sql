-- Bond trading with Yield — schema upgrade (run once against dse_oms).
-- Adds coupon/maturity to securities and yield/price-basis to orders.

ALTER TABLE security
  ADD COLUMN coupon_rate   DECIMAL(7,4) NULL,               -- annual coupon %
  ADD COLUMN coupon_freq   INT NOT NULL DEFAULT 2,          -- coupon payments per year
  ADD COLUMN maturity_date DATE NULL;                       -- NULL = perpetual

ALTER TABLE oms_order
  ADD COLUMN order_yield DECIMAL(9,4) NULL,                 -- yield the order was placed at (bonds)
  ADD COLUMN price_basis VARCHAR(8) NOT NULL DEFAULT 'PRICE'; -- PRICE | YIELD

-- Seed the existing DSE bonds with realistic coupon/maturity.
UPDATE security SET coupon_rate = 9.00,  coupon_freq = 2, maturity_date = '2027-12-31' WHERE symbol = 'BEXGSUKUK';
UPDATE security SET coupon_rate = 10.00, coupon_freq = 2, maturity_date = NULL         WHERE symbol = 'PBLPBOND';   -- perpetual
UPDATE security SET coupon_rate = 8.50,  coupon_freq = 2, maturity_date = '2034-06-30' WHERE symbol = 'TB10Y2034';
