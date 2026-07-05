-- Price alerts (parity with DSE-FlexTP "Set Alert"). Run once against dse_oms.
CREATE TABLE IF NOT EXISTS price_alert (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id     BIGINT NOT NULL,
  security_id    BIGINT NOT NULL,
  target_price   DECIMAL(14,4) NOT NULL,
  direction      VARCHAR(8)  NOT NULL,                       -- ABOVE | BELOW
  status         VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE | TRIGGERED | CANCELLED
  note           VARCHAR(160),
  ltp_at_trigger DECIMAL(14,4),
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  triggered_at   DATETIME,
  KEY idx_alert_account (account_id),
  KEY idx_alert_status (status)
);
