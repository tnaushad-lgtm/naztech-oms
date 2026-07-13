-- ============================================================================
--  NAZTECH OMS  —  Exchange-Hosted Order Management System (DSE / CSE)
--  MySQL schema  (database: dse_oms)
--
--  Built for the DSE RFP "Exchange-Hosted OMS — License & AMC Model".
--  `exchange` is a first-class dimension so the same platform serves DSE & CSE.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS dse_oms
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dse_oms;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS equity_snapshot;
DROP TABLE IF EXISTS order_event;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS trade;
DROP TABLE IF EXISTS oms_order;
DROP TABLE IF EXISTS holding;
DROP TABLE IF EXISTS risk_limit;
DROP TABLE IF EXISTS client_account;
DROP TABLE IF EXISTS price_history;
DROP TABLE IF EXISTS market_data;
DROP TABLE IF EXISTS news;
DROP TABLE IF EXISTS app_user;
DROP TABLE IF EXISTS security;
DROP TABLE IF EXISTS broker;
DROP TABLE IF EXISTS exchange;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------- exchange ---
CREATE TABLE exchange (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  code         VARCHAR(8)   NOT NULL UNIQUE,        -- DSE, CSE
  name         VARCHAR(120) NOT NULL,
  timezone     VARCHAR(40)  NOT NULL DEFAULT 'Asia/Dhaka',
  currency     VARCHAR(8)   NOT NULL DEFAULT 'BDT',
  open_time    TIME         NOT NULL DEFAULT '10:00:00',
  close_time   TIME         NOT NULL DEFAULT '14:30:00',
  -- The trading session, owned by MarketSessionService and restored from here on startup.
  -- Starts CLOSED: a market is closed until someone opens it, and an OMS that comes up trading
  -- because nobody said otherwise is not one you want on a Sunday morning.
  status       VARCHAR(16)  NOT NULL DEFAULT 'CLOSED' -- CLOSED / PRE_OPEN / OPEN / HALTED
) ENGINE=InnoDB;

-- ------------------------------------------------------------------ broker ---
-- A TREC holder (brokerage firm) centrally hosted by the Exchange.
CREATE TABLE broker (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  exchange_id   BIGINT       NOT NULL,
  trec_code     VARCHAR(24)  NOT NULL,              -- TREC / member code
  name          VARCHAR(160) NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/SUSPENDED/OFFBOARDED
  firm_limit    DECIMAL(20,2) NOT NULL DEFAULT 0,   -- aggregate firm exposure cap
  contact_email VARCHAR(160),
  onboarded_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_broker_exchange FOREIGN KEY (exchange_id) REFERENCES exchange(id),
  UNIQUE KEY uq_broker_code (exchange_id, trec_code)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- app_user ---
-- Multi-level hierarchy: EXCHANGE_ADMIN > BROKER_ADMIN > RMS_MANAGER >
--                        DEALER/TRADER > CLIENT / VIEW_ONLY
CREATE TABLE app_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  display_name  VARCHAR(120) NOT NULL,
  password_hash VARCHAR(200) NOT NULL,             -- SHA-256 hex (POC)
  role          VARCHAR(24)  NOT NULL,             -- see enum above
  broker_id     BIGINT       NULL,                 -- null for EXCHANGE_ADMIN
  parent_id     BIGINT       NULL,                 -- hierarchy parent
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  session_token VARCHAR(64)  NULL,                 -- single active session (RFP 2.6)
  last_login    DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_user_broker FOREIGN KEY (broker_id) REFERENCES broker(id),
  KEY idx_user_role (role), KEY idx_user_broker (broker_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- security ---
CREATE TABLE security (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  exchange_id   BIGINT       NOT NULL,
  symbol        VARCHAR(32)  NOT NULL,
  name          VARCHAR(200) NOT NULL,
  asset_class   VARCHAR(24)  NOT NULL,  -- EQUITY_MAIN/EQUITY_SME/ATB_OTC/CORP_BOND/
                                        -- GOVT_BOND/SUKUK/MUTUAL_FUND/ETF/DERIVATIVE/INDEX
  board         VARCHAR(24)  NOT NULL DEFAULT 'MAIN',   -- MAIN/SME/ATB/OTC/BOND
  sector        VARCHAR(80),
  face_value    DECIMAL(14,4) NOT NULL DEFAULT 10,
  lot_size      INT          NOT NULL DEFAULT 1,
  tick_size     DECIMAL(10,4) NOT NULL DEFAULT 0.10,
  market_lot    INT          NOT NULL DEFAULT 1,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/SUSPENDED/HALTED
  category      VARCHAR(2)   NOT NULL DEFAULT 'A',       -- DSE share category A/B/N/Z/G
  is_shariah    TINYINT(1)   NOT NULL DEFAULT 0,
  CONSTRAINT fk_sec_exchange FOREIGN KEY (exchange_id) REFERENCES exchange(id),
  UNIQUE KEY uq_sec (exchange_id, symbol),
  KEY idx_sec_class (asset_class), KEY idx_sec_symbol (symbol)
) ENGINE=InnoDB;

-- ------------------------------------------------------------ market_data ---
-- Latest snapshot per security (one row per security, upserted by the feed).
CREATE TABLE market_data (
  security_id   BIGINT PRIMARY KEY,
  ltp           DECIMAL(14,4) NOT NULL DEFAULT 0,   -- last traded price
  open_price    DECIMAL(14,4) NOT NULL DEFAULT 0,
  high_price    DECIMAL(14,4) NOT NULL DEFAULT 0,
  low_price     DECIMAL(14,4) NOT NULL DEFAULT 0,
  close_price   DECIMAL(14,4) NOT NULL DEFAULT 0,   -- previous close
  ycp           DECIMAL(14,4) NOT NULL DEFAULT 0,   -- yesterday closing price
  bid           DECIMAL(14,4) NOT NULL DEFAULT 0,
  ask           DECIMAL(14,4) NOT NULL DEFAULT 0,
  volume        BIGINT       NOT NULL DEFAULT 0,
  trades        INT          NOT NULL DEFAULT 0,
  value_mn      DECIMAL(18,4) NOT NULL DEFAULT 0,   -- turnover in million
  change_pct    DECIMAL(10,4) NOT NULL DEFAULT 0,
  source        VARCHAR(16)  NOT NULL DEFAULT 'SIM',-- BDSHARE / SIM / ME
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_md_sec FOREIGN KEY (security_id) REFERENCES security(id)
) ENGINE=InnoDB;

-- ----------------------------------------------------------- price_history ---
CREATE TABLE price_history (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  security_id   BIGINT       NOT NULL,
  trade_date    DATE         NOT NULL,
  open_price    DECIMAL(14,4) NOT NULL DEFAULT 0,
  high_price    DECIMAL(14,4) NOT NULL DEFAULT 0,
  low_price     DECIMAL(14,4) NOT NULL DEFAULT 0,
  close_price   DECIMAL(14,4) NOT NULL DEFAULT 0,
  volume        BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT fk_ph_sec FOREIGN KEY (security_id) REFERENCES security(id),
  UNIQUE KEY uq_ph (security_id, trade_date)
) ENGINE=InnoDB;

-- ---------------------------------------------------------- client_account ---
CREATE TABLE client_account (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  broker_id     BIGINT       NOT NULL,
  client_user_id BIGINT      NULL,                 -- optional link to app_user(CLIENT)
  bo_id         VARCHAR(24)  NOT NULL,             -- Beneficiary Owner (BO) account
  name          VARCHAR(160) NOT NULL,
  cash_balance  DECIMAL(20,2) NOT NULL DEFAULT 0,
  buying_power  DECIMAL(20,2) NOT NULL DEFAULT 0,  -- cash + margin available
  realized_pnl  DECIMAL(20,2) NOT NULL DEFAULT 0,  -- booked P&L from closed/sold quantity
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT fk_ca_broker FOREIGN KEY (broker_id) REFERENCES broker(id),
  UNIQUE KEY uq_ca_bo (broker_id, bo_id)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------- holding ---
CREATE TABLE holding (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id    BIGINT       NOT NULL,
  security_id   BIGINT       NOT NULL,
  quantity      BIGINT       NOT NULL DEFAULT 0,
  avg_cost      DECIMAL(14,4) NOT NULL DEFAULT 0,
  CONSTRAINT fk_h_acct FOREIGN KEY (account_id) REFERENCES client_account(id),
  CONSTRAINT fk_h_sec  FOREIGN KEY (security_id) REFERENCES security(id),
  UNIQUE KEY uq_holding (account_id, security_id)
) ENGINE=InnoDB;

-- -------------------------------------------------------------- risk_limit ---
-- Pre-trade controls per the RFP RMS module.
CREATE TABLE risk_limit (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope            VARCHAR(16) NOT NULL,           -- BROKER / TRADER / CLIENT
  entity_id        BIGINT      NOT NULL,           -- broker.id / app_user.id / client_account.id
  max_order_value  DECIMAL(20,2) NOT NULL DEFAULT 0,  -- single order notional cap
  max_order_qty    BIGINT       NOT NULL DEFAULT 0,
  max_gross_exposure DECIMAL(20,2) NOT NULL DEFAULT 0, -- aggregate open+filled exposure
  mtm_loss_limit   DECIMAL(20,2) NOT NULL DEFAULT 0,   -- mark-to-market stop
  wash_sale_block  TINYINT(1)   NOT NULL DEFAULT 1,
  enabled          TINYINT(1)   NOT NULL DEFAULT 1,
  UNIQUE KEY uq_limit (scope, entity_id)
) ENGINE=InnoDB;

-- --------------------------------------------------------------- oms_order ---
CREATE TABLE oms_order (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_ref     VARCHAR(32)  NOT NULL UNIQUE,      -- human/exchange reference
  exchange_id   BIGINT       NOT NULL,
  broker_id     BIGINT       NOT NULL,
  dealer_id     BIGINT       NULL,                 -- app_user who entered it
  account_id    BIGINT       NOT NULL,             -- client_account
  security_id   BIGINT       NOT NULL,
  side          VARCHAR(4)   NOT NULL,             -- BUY / SELL
  order_type    VARCHAR(12)  NOT NULL DEFAULT 'LIMIT', -- LIMIT/MARKET/STOP/STOP_LIMIT
  trade_window  VARCHAR(16)  NOT NULL DEFAULT 'NORMAL', -- NORMAL/SPOT/BLOCK/ODD_LOT/...
  validity      VARCHAR(8)   NOT NULL DEFAULT 'DAY',    -- DAY/GTD/GTC/GTS
  expire_date   DATE         NULL,                 -- for GTD
  price         DECIMAL(14,4) NOT NULL DEFAULT 0,
  stop_price    DECIMAL(14,4) NULL,
  quantity      BIGINT       NOT NULL,
  filled_qty    BIGINT       NOT NULL DEFAULT 0,
  avg_fill_price DECIMAL(14,4) NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL DEFAULT 'NEW',
       -- NEW/PENDING_RISK/REJECTED/OPEN/PARTIAL/FILLED/CANCELLED/EXPIRED
  reject_reason VARCHAR(240) NULL,
  risk_score    DECIMAL(6,2) NOT NULL DEFAULT 0,   -- AI order-risk 0..100
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_o_exchange FOREIGN KEY (exchange_id) REFERENCES exchange(id),
  CONSTRAINT fk_o_broker   FOREIGN KEY (broker_id)   REFERENCES broker(id),
  CONSTRAINT fk_o_account  FOREIGN KEY (account_id)  REFERENCES client_account(id),
  CONSTRAINT fk_o_sec      FOREIGN KEY (security_id)  REFERENCES security(id),
  KEY idx_o_status (status), KEY idx_o_sec (security_id), KEY idx_o_book (security_id, side, status)
) ENGINE=InnoDB;

-- ------------------------------------------------------------------- trade ---
CREATE TABLE trade (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  trade_ref      VARCHAR(32) NOT NULL UNIQUE,
  security_id    BIGINT      NOT NULL,
  buy_order_id   BIGINT      NULL,
  sell_order_id  BIGINT      NULL,
  price          DECIMAL(14,4) NOT NULL,
  quantity       BIGINT      NOT NULL,
  aggressor_side VARCHAR(4)  NULL,                 -- which side crossed the book
  executed_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_t_sec FOREIGN KEY (security_id) REFERENCES security(id),
  KEY idx_t_sec (security_id), KEY idx_t_time (executed_at)
) ENGINE=InnoDB;

-- --------------------------------------------------------------- order_event ---
-- Append-only lifecycle log (audit + replay).
CREATE TABLE order_event (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id     BIGINT      NOT NULL,
  event_type   VARCHAR(24) NOT NULL,              -- ACCEPTED/RISK_PASS/RISK_REJECT/PARTIAL_FILL/...
  detail       VARCHAR(400),
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_oe_order (order_id)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------- audit_log ---
CREATE TABLE audit_log (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor        VARCHAR(64),
  action       VARCHAR(48) NOT NULL,
  entity_type  VARCHAR(48),
  entity_id    VARCHAR(48),
  detail       TEXT,
  ip_address   VARCHAR(45),
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_time (created_at), KEY idx_audit_actor (actor)
) ENGINE=InnoDB;

-- ------------------------------------------------------------------- news ---
CREATE TABLE news (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  exchange_id  BIGINT       NULL,
  broker_id    BIGINT       NULL,
  category     VARCHAR(32)  NOT NULL DEFAULT 'GENERAL', -- PRICE_SENSITIVE/DIVIDEND/AGM/HALT/GENERAL
  symbol       VARCHAR(32)  NULL,
  title        VARCHAR(240) NOT NULL,
  body         TEXT,
  attachment_url VARCHAR(400) NULL,
  sentiment    VARCHAR(12)  NULL,                  -- POSITIVE/NEUTRAL/NEGATIVE (AI)
  published_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_news_time (published_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------- equity_snapshot ---
-- Point-in-time account equity for the P&L-over-time / equity-curve charts.
CREATE TABLE equity_snapshot (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id     BIGINT       NOT NULL,
  ts             DATETIME     NOT NULL,
  total_value    DECIMAL(20,2) NOT NULL DEFAULT 0,
  cash           DECIMAL(20,2) NOT NULL DEFAULT 0,
  holdings_value DECIMAL(20,2) NOT NULL DEFAULT 0,
  unrealized_pnl DECIMAL(20,2) NOT NULL DEFAULT 0,
  realized_pnl   DECIMAL(20,2) NOT NULL DEFAULT 0,
  day_pnl        DECIMAL(20,2) NOT NULL DEFAULT 0,
  KEY idx_eq (account_id, ts)
) ENGINE=InnoDB;
