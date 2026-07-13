-- ============================================================================
--  NAZTECH OMS — seed data (run after schema.sql)
--  Passwords are SHA-256; demo password for every account is:  demo123
-- ============================================================================
USE dse_oms;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE order_event;
TRUNCATE TABLE audit_log;
TRUNCATE TABLE trade;
TRUNCATE TABLE oms_order;
TRUNCATE TABLE holding;
TRUNCATE TABLE risk_limit;
TRUNCATE TABLE client_account;
TRUNCATE TABLE price_history;
TRUNCATE TABLE market_data;
TRUNCATE TABLE news;
TRUNCATE TABLE app_user;
TRUNCATE TABLE security;
TRUNCATE TABLE broker;
TRUNCATE TABLE exchange;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------- exchanges ---
-- status is the trading session: the market opens when someone opens it (Exchange Admin →
-- Market Session, or market.auto-schedule=true to follow the Dhaka clock).
INSERT INTO exchange (code, name, timezone, currency, open_time, close_time, status) VALUES
  ('DSE', 'Dhaka Stock Exchange PLC',     'Asia/Dhaka', 'BDT', '10:00:00', '14:30:00', 'CLOSED'),
  ('CSE', 'Chittagong Stock Exchange PLC','Asia/Dhaka', 'BDT', '10:00:00', '14:30:00', 'CLOSED');

-- ------------------------------------------------------------------ broker ---
-- The house broker is Dragon Security, the DSE-enlisted brokerage this OMS is built for.
-- (Naztech is the IT vendor — it owns the product, not a TREC.)
INSERT INTO broker (exchange_id, trec_code, name, status, firm_limit, contact_email) VALUES
  ((SELECT id FROM exchange WHERE code='DSE'), 'TREC-101', 'Dragon Security', 'ACTIVE', 500000000, 'ops@dragonsecurity.com.bd'),
  ((SELECT id FROM exchange WHERE code='DSE'), 'TREC-102', 'Meridian Brokerage Ltd.', 'ACTIVE', 250000000, 'desk@meridian.com'),
  ((SELECT id FROM exchange WHERE code='CSE'), 'CTREC-21', 'Dragon Security (CSE)', 'ACTIVE', 300000000, 'ops@dragonsecurity.com.bd');

-- ------------------------------------------------------------------- users ---
-- password for all = demo123
SET @pw := SHA2('demo123', 256);
SET @nz := (SELECT id FROM broker WHERE trec_code='TREC-101');

INSERT INTO app_user (username, display_name, password_hash, role, broker_id, parent_id, status) VALUES
  ('exchadmin', 'DSE Exchange Admin',     @pw, 'EXCHANGE_ADMIN', NULL, NULL, 'ACTIVE'),
  ('brokeradmin','Naztech Broker Admin',  @pw, 'BROKER_ADMIN',   @nz,  NULL, 'ACTIVE'),
  ('rms',       'Naztech RMS Manager',    @pw, 'RMS_MANAGER',    @nz,  NULL, 'ACTIVE'),
  ('dealer1',   'Rakib (Dealer)',         @pw, 'DEALER',         @nz,  NULL, 'ACTIVE'),
  ('trader1',   'Hasan (Trader)',         @pw, 'TRADER',         @nz,  NULL, 'ACTIVE'),
  ('client1',   'Ayesha Rahman',          @pw, 'CLIENT',         @nz,  NULL, 'ACTIVE'),
  ('viewer',    'Audit Viewer',           @pw, 'VIEW_ONLY',      @nz,  NULL, 'ACTIVE');

-- -------------------------------------------------------- client accounts ---
INSERT INTO client_account (broker_id, bo_id, name, cash_balance, buying_power, status) VALUES
  (@nz, '1201010000001', 'Ayesha Rahman',       5000000, 5000000, 'ACTIVE'),
  (@nz, '1201010000002', 'Kamrul Islam',         2500000, 2500000, 'ACTIVE'),
  (@nz, '1201010000003', 'Dragon Security Proprietary', 50000000,50000000, 'ACTIVE');

-- ---------------------------------------------------------------- limits ---
-- Firm-level
INSERT INTO risk_limit (scope, entity_id, max_order_value, max_order_qty, max_gross_exposure, mtm_loss_limit, wash_sale_block, enabled)
VALUES ('BROKER', @nz, 50000000, 5000000, 500000000, 20000000, 1, 1);
-- Trader-level
INSERT INTO risk_limit (scope, entity_id, max_order_value, max_order_qty, max_gross_exposure, mtm_loss_limit, wash_sale_block, enabled)
VALUES ('TRADER', (SELECT id FROM app_user WHERE username='dealer1'), 10000000, 1000000, 100000000, 5000000, 1, 1);
-- Client-level
INSERT INTO risk_limit (scope, entity_id, max_order_value, max_order_qty, max_gross_exposure, mtm_loss_limit, wash_sale_block, enabled)
VALUES ('CLIENT', (SELECT id FROM client_account WHERE bo_id='1201010000001'), 3000000, 200000, 10000000, 1000000, 1, 1);

-- -------------------------------------------------------------- securities ---
-- A curated set of REAL DSE/CSE instruments across asset classes so the platform
-- works fully offline. The Python bdshare service can enrich/expand this set.
SET @dse := (SELECT id FROM exchange WHERE code='DSE');
SET @cse := (SELECT id FROM exchange WHERE code='CSE');

INSERT INTO security (exchange_id, symbol, name, asset_class, board, sector, face_value, lot_size, tick_size, market_lot, is_shariah) VALUES
  -- Main board equities
  (@dse,'GP',         'Grameenphone Ltd.',                 'EQUITY_MAIN','MAIN','Telecommunication', 10, 1, 0.10, 1, 0),
  (@dse,'ROBI',       'Robi Axiata Ltd.',                  'EQUITY_MAIN','MAIN','Telecommunication', 10, 1, 0.10, 1, 0),
  (@dse,'SQURPHARMA', 'Square Pharmaceuticals PLC',        'EQUITY_MAIN','MAIN','Pharmaceuticals',   10, 1, 0.10, 1, 0),
  (@dse,'BXPHARMA',   'Beximco Pharmaceuticals Ltd.',      'EQUITY_MAIN','MAIN','Pharmaceuticals',   10, 1, 0.10, 1, 1),
  (@dse,'RENATA',     'Renata PLC',                        'EQUITY_MAIN','MAIN','Pharmaceuticals',   10, 1, 0.10, 1, 0),
  (@dse,'BEXIMCO',    'Bangladesh Export Import Co. Ltd.', 'EQUITY_MAIN','MAIN','Miscellaneous',     10, 1, 0.10, 1, 1),
  (@dse,'BRACBANK',   'BRAC Bank PLC',                     'EQUITY_MAIN','MAIN','Bank',              10, 1, 0.10, 1, 0),
  (@dse,'CITYBANK',   'The City Bank PLC',                 'EQUITY_MAIN','MAIN','Bank',              10, 1, 0.10, 1, 0),
  (@dse,'ISLAMIBANK', 'Islami Bank Bangladesh PLC',        'EQUITY_MAIN','MAIN','Bank',              10, 1, 0.10, 1, 1),
  (@dse,'EBL',        'Eastern Bank PLC',                  'EQUITY_MAIN','MAIN','Bank',              10, 1, 0.10, 1, 0),
  (@dse,'WALTONHIL',  'Walton Hi-Tech Industries PLC',     'EQUITY_MAIN','MAIN','Engineering',       10, 1, 0.10, 1, 0),
  (@dse,'BATBC',      'British American Tobacco Bangladesh','EQUITY_MAIN','MAIN','Food & Allied',    10, 1, 0.10, 1, 0),
  (@dse,'MARICO',     'Marico Bangladesh Ltd.',            'EQUITY_MAIN','MAIN','Food & Allied',     10, 1, 0.10, 1, 0),
  (@dse,'OLYMPIC',    'Olympic Industries Ltd.',           'EQUITY_MAIN','MAIN','Food & Allied',     10, 1, 0.10, 1, 0),
  (@dse,'LHBL',       'LafargeHolcim Bangladesh PLC',      'EQUITY_MAIN','MAIN','Cement',            10, 1, 0.10, 1, 0),
  (@dse,'BSRMLTD',    'BSRM Ltd.',                         'EQUITY_MAIN','MAIN','Engineering',       10, 1, 0.10, 1, 0),
  (@dse,'UPGDCL',     'United Power Generation & Dist.',   'EQUITY_MAIN','MAIN','Fuel & Power',      10, 1, 0.10, 1, 0),
  (@dse,'SUMITPOWER', 'Summit Power Ltd.',                 'EQUITY_MAIN','MAIN','Fuel & Power',      10, 1, 0.10, 1, 0),
  (@dse,'TITASGAS',   'Titas Gas Trans. & Dist. Co.',      'EQUITY_MAIN','MAIN','Fuel & Power',      10, 1, 0.10, 1, 0),
  (@dse,'BERGERPBL',  'Berger Paints Bangladesh Ltd.',     'EQUITY_MAIN','MAIN','Miscellaneous',     10, 1, 0.10, 1, 0),
  -- SME board
  (@dse,'KrishiBid',  'Krishibid Feed Ltd.',               'EQUITY_SME','SME','Food & Allied',      10, 1, 0.10, 1, 0),
  (@dse,'MAMUNAGRO',  'Mamun Agro Products Ltd.',          'EQUITY_SME','SME','Food & Allied',      10, 1, 0.10, 1, 0),
  -- Mutual funds
  (@dse,'1JANATAMF',  'Janata Bank 1st Mutual Fund',       'MUTUAL_FUND','MAIN','Mutual Fund',      10, 1, 0.10, 1, 0),
  (@dse,'GREENDELMF', 'Green Delta Mutual Fund',           'MUTUAL_FUND','MAIN','Mutual Fund',      10, 1, 0.10, 1, 0),
  -- Bonds / Sukuk
  (@dse,'BEXGSUKUK',  'Beximco Green Sukuk Al Istisna''a', 'SUKUK','BOND','Sukuk',                 100, 1, 0.10, 1, 1),
  (@dse,'PBLPBOND',   'Pubali Bank Perpetual Bond',        'CORP_BOND','BOND','Bond',             5000, 1, 1.00, 1, 0),
  (@dse,'TB10Y2034',  'Govt. Treasury Bond 10Y 2034',      'GOVT_BOND','BOND','Govt Bond',         100, 1, 0.01, 1, 0),
  -- Indices (display / analytics)
  (@dse,'DSEX',       'DSE Broad Index',                   'INDEX','MAIN','Index',                  0, 1, 0.01, 1, 0),
  (@dse,'DS30',       'DSE 30 Index',                      'INDEX','MAIN','Index',                  0, 1, 0.01, 1, 0),
  (@dse,'DSES',       'DSE Shariah Index',                 'INDEX','MAIN','Index',                  0, 1, 0.01, 1, 1),
  -- CSE
  (@cse,'GP',         'Grameenphone Ltd. (CSE)',           'EQUITY_MAIN','MAIN','Telecommunication',10, 1, 0.10, 1, 0),
  (@cse,'SQURPHARMA', 'Square Pharmaceuticals (CSE)',      'EQUITY_MAIN','MAIN','Pharmaceuticals',  10, 1, 0.10, 1, 0),
  (@cse,'BRACBANK',   'BRAC Bank PLC (CSE)',               'EQUITY_MAIN','MAIN','Bank',             10, 1, 0.10, 1, 0),
  (@cse,'CSCX',       'CSE Selective Categories Index',    'INDEX','MAIN','Index',                   0, 1, 0.01, 1, 0),
  (@cse,'CASPI',      'CSE All Share Price Index',         'INDEX','MAIN','Index',                   0, 1, 0.01, 1, 0);

-- ----------------------------------------------------- market data snapshot ---
-- Realistic recent levels so the terminal is populated immediately.
INSERT INTO market_data (security_id, ltp, open_price, high_price, low_price, close_price, ycp, bid, ask, volume, trades, value_mn, change_pct, source)
SELECT s.id, v.ltp, v.op, v.hi, v.lo, v.cl, v.cl, v.ltp-0.10, v.ltp+0.10, v.vol, v.trd, ROUND(v.ltp*v.vol/1000000,4),
       ROUND((v.ltp - v.cl)/v.cl*100,2), 'SEED'
FROM security s JOIN (
  SELECT 'GP' sym, 'DSE' ex, 305.40 ltp, 300.00 op, 308.90 hi, 299.50 lo, 301.20 cl, 1850000 vol, 4200 trd UNION ALL
  SELECT 'ROBI','DSE', 28.70, 28.50, 29.10, 28.20, 28.60, 6500000, 3100 UNION ALL
  SELECT 'SQURPHARMA','DSE', 212.30, 210.00, 214.80, 209.40, 210.50, 920000, 2600 UNION ALL
  SELECT 'BXPHARMA','DSE', 118.60, 116.50, 120.10, 116.00, 116.90, 1450000, 2100 UNION ALL
  SELECT 'RENATA','DSE', 720.10, 715.00, 728.40, 712.30, 716.80, 145000, 980 UNION ALL
  SELECT 'BEXIMCO','DSE', 102.40, 101.00, 104.50, 100.60, 101.30, 5400000, 5600 UNION ALL
  SELECT 'BRACBANK','DSE', 47.80, 47.20, 48.60, 46.90, 47.30, 2300000, 3400 UNION ALL
  SELECT 'CITYBANK','DSE', 22.10, 21.90, 22.50, 21.80, 21.95, 1800000, 1500 UNION ALL
  SELECT 'ISLAMIBANK','DSE', 35.60, 35.20, 36.10, 35.00, 35.30, 980000, 1200 UNION ALL
  SELECT 'EBL','DSE', 33.40, 33.10, 33.90, 32.90, 33.20, 760000, 900 UNION ALL
  SELECT 'WALTONHIL','DSE', 545.20, 540.00, 552.30, 538.10, 541.60, 64000, 750 UNION ALL
  SELECT 'BATBC','DSE', 498.70, 495.00, 505.20, 492.40, 496.10, 88000, 1100 UNION ALL
  SELECT 'MARICO','DSE', 2310.50, 2300.00, 2335.00, 2295.00, 2305.40, 22000, 600 UNION ALL
  SELECT 'OLYMPIC','DSE', 178.30, 176.00, 180.40, 175.50, 176.70, 410000, 820 UNION ALL
  SELECT 'LHBL','DSE', 68.90, 68.20, 69.80, 67.90, 68.40, 1250000, 1700 UNION ALL
  SELECT 'BSRMLTD','DSE', 95.20, 94.50, 96.40, 94.10, 94.80, 540000, 980 UNION ALL
  SELECT 'UPGDCL','DSE', 142.60, 141.00, 144.20, 140.50, 141.40, 360000, 700 UNION ALL
  SELECT 'SUMITPOWER','DSE', 34.10, 33.80, 34.60, 33.60, 33.90, 1600000, 1900 UNION ALL
  SELECT 'TITASGAS','DSE', 38.50, 38.10, 39.00, 37.90, 38.20, 1100000, 1300 UNION ALL
  SELECT 'BERGERPBL','DSE', 1620.40, 1610.00, 1635.00, 1605.00, 1612.30, 12000, 400 UNION ALL
  SELECT 'KrishiBid','DSE', 14.20, 14.00, 14.50, 13.90, 14.10, 250000, 300 UNION ALL
  SELECT 'MAMUNAGRO','DSE', 21.80, 21.50, 22.10, 21.40, 21.60, 180000, 260 UNION ALL
  SELECT '1JANATAMF','DSE', 6.40, 6.30, 6.50, 6.30, 6.35, 900000, 350 UNION ALL
  SELECT 'GREENDELMF','DSE', 8.70, 8.60, 8.90, 8.50, 8.65, 420000, 280 UNION ALL
  SELECT 'BEXGSUKUK','DSE', 96.50, 96.00, 97.20, 95.80, 96.20, 75000, 180 UNION ALL
  SELECT 'PBLPBOND','DSE', 4980.00, 4975.00, 4995.00, 4970.00, 4978.00, 1200, 40 UNION ALL
  SELECT 'TB10Y2034','DSE', 99.85, 99.80, 99.95, 99.78, 99.82, 5000, 25 UNION ALL
  SELECT 'DSEX','DSE', 5240.65, 5210.30, 5258.90, 5205.10, 5218.40, 0, 0 UNION ALL
  SELECT 'DS30','DSE', 1985.20, 1975.00, 1992.40, 1972.10, 1978.60, 0, 0 UNION ALL
  SELECT 'DSES','DSE', 1142.80, 1138.00, 1147.30, 1136.20, 1139.50, 0, 0 UNION ALL
  SELECT 'GP','CSE', 305.10, 300.50, 308.40, 299.80, 301.30, 120000, 220 UNION ALL
  SELECT 'SQURPHARMA','CSE', 212.00, 210.20, 214.50, 209.60, 210.40, 80000, 160 UNION ALL
  SELECT 'BRACBANK','CSE', 47.70, 47.30, 48.40, 47.00, 47.40, 95000, 180 UNION ALL
  SELECT 'CSCX','CSE', 9105.40, 9080.00, 9130.20, 9072.50, 9088.10, 0, 0 UNION ALL
  SELECT 'CASPI','CSE', 14820.60, 14780.00, 14860.30, 14770.10, 14795.40, 0, 0
) v ON s.symbol = v.sym AND s.exchange_id = (SELECT id FROM exchange WHERE code = v.ex);

-- --------------------------------------------------------------- holdings ---
-- Give the demo client an existing portfolio so P&L is visible.
INSERT INTO holding (account_id, security_id, quantity, avg_cost)
SELECT (SELECT id FROM client_account WHERE bo_id='1201010000001'), s.id, q.qty, q.cost
FROM security s JOIN (
  SELECT 'GP' sym, 2000 qty, 295.00 cost UNION ALL
  SELECT 'BRACBANK', 10000, 45.00 UNION ALL
  SELECT 'BEXIMCO', 5000, 108.00 UNION ALL
  SELECT 'SQURPHARMA', 1500, 205.00
) q ON s.symbol = q.sym AND s.exchange_id = @dse;

-- ----------------------------------------------------------------- news ---
INSERT INTO news (exchange_id, category, symbol, title, body, sentiment) VALUES
  (@dse,'PRICE_SENSITIVE','SQURPHARMA','Square Pharma declares 60% cash dividend',
        'The Board recommended 60% cash dividend for the year ended 30 June. Record date to follow.', 'POSITIVE'),
  (@dse,'HALT','BEXIMCO','Trading of BEXIMCO halted pending price-sensitive disclosure',
        'DSE has suspended trading of BEXIMCO for one hour pending a material announcement.', 'NEGATIVE'),
  (@dse,'GENERAL',NULL,'DSEX crosses 5,200 on strong turnover',
        'The broad index advanced as telecom and pharma sectors led gains on robust volume.', 'POSITIVE');

-- ============================================================================
--  Investor logins (5) + share categories + diversified portfolios
--  Every investor logs in with password: demo123
-- ============================================================================

-- share categories (A regular / B low-dividend / N new / Z poor / G govt)
UPDATE security SET category='Z' WHERE symbol IN ('MAMUNAGRO','KrishiBid');
UPDATE security SET category='B' WHERE symbol IN ('ROBI','BEXIMCO');
UPDATE security SET category='N' WHERE symbol IN ('WALTONHIL');
UPDATE security SET category='G' WHERE asset_class='GOVT_BOND';

-- three more client accounts (acct1 & acct2 already exist from earlier in this file)
INSERT INTO client_account (broker_id, bo_id, name, cash_balance, buying_power, realized_pnl, status) VALUES
  (@nz, '1201010000004', 'Rumana Haque', 3000000, 3000000, 0, 'ACTIVE'),
  (@nz, '1201010000005', 'Tanvir Ahmed', 4000000, 4000000, 0, 'ACTIVE'),
  (@nz, '1201010000006', 'Shirin Akter', 1500000, 1500000, 0, 'ACTIVE');

-- five investor users
INSERT INTO app_user (username, display_name, password_hash, role, broker_id, status) VALUES
  ('investor1','Ayesha Rahman', @pw,'CLIENT',@nz,'ACTIVE'),
  ('investor2','Kamrul Islam',  @pw,'CLIENT',@nz,'ACTIVE'),
  ('investor3','Rumana Haque',  @pw,'CLIENT',@nz,'ACTIVE'),
  ('investor4','Tanvir Ahmed',  @pw,'CLIENT',@nz,'ACTIVE'),
  ('investor5','Shirin Akter',  @pw,'CLIENT',@nz,'ACTIVE');

-- link each investor to their own account
UPDATE client_account SET client_user_id=(SELECT id FROM app_user WHERE username='investor1') WHERE bo_id='1201010000001';
UPDATE client_account SET client_user_id=(SELECT id FROM app_user WHERE username='investor2') WHERE bo_id='1201010000002';
UPDATE client_account SET client_user_id=(SELECT id FROM app_user WHERE username='investor3') WHERE bo_id='1201010000004';
UPDATE client_account SET client_user_id=(SELECT id FROM app_user WHERE username='investor4') WHERE bo_id='1201010000005';
UPDATE client_account SET client_user_id=(SELECT id FROM app_user WHERE username='investor5') WHERE bo_id='1201010000006';

-- diversified holdings (avg_cost picked to create a profit/loss mix vs seeded LTPs)
INSERT INTO holding (account_id, security_id, quantity, avg_cost)
SELECT ca.id, s.id, h.qty, h.cost FROM client_account ca JOIN security s ON s.exchange_id=@dse JOIN (
  SELECT '1201010000002' bo,'SQURPHARMA' sym,1200 qty,200.0 cost UNION ALL
  SELECT '1201010000002','RENATA',300,700.0 UNION ALL
  SELECT '1201010000002','BXPHARMA',2000,110.0 UNION ALL
  SELECT '1201010000004','BRACBANK',12000,44.0 UNION ALL
  SELECT '1201010000004','CITYBANK',20000,23.0 UNION ALL
  SELECT '1201010000004','ISLAMIBANK',8000,34.0 UNION ALL
  SELECT '1201010000004','EBL',5000,35.0 UNION ALL
  SELECT '1201010000005','GP',1500,290.0 UNION ALL
  SELECT '1201010000005','ROBI',30000,30.0 UNION ALL
  SELECT '1201010000005','UPGDCL',2500,150.0 UNION ALL
  SELECT '1201010000005','SUMITPOWER',40000,33.0 UNION ALL
  SELECT '1201010000005','TITASGAS',15000,40.0 UNION ALL
  SELECT '1201010000006','WALTONHIL',300,560.0 UNION ALL
  SELECT '1201010000006','BEXIMCO',8000,130.0 UNION ALL
  SELECT '1201010000006','MAMUNAGRO',10000,25.0 UNION ALL
  SELECT '1201010000006','1JANATAMF',50000,7.0
) h ON h.bo=ca.bo_id AND h.sym=s.symbol;
