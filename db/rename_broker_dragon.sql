-- ============================================================================
--  The house broker is Dragon Security — the DSE-enlisted brokerage this OMS is built for.
--  Naztech is the IT vendor: it owns the product, not a TREC. The seed had the vendor's name on
--  the broker rows, which would have been visible to the client on every screen.
--
--  Run once against an existing database (db/seed.sql already carries the correct names for a
--  fresh install):  mysql -u root -p dse_oms < db/rename_broker_dragon.sql
-- ============================================================================

UPDATE broker SET name = 'Dragon Security',       contact_email = 'ops@dragonsecurity.com.bd'
 WHERE trec_code = 'TREC-101';

UPDATE broker SET name = 'Dragon Security (CSE)', contact_email = 'ops@dragonsecurity.com.bd'
 WHERE trec_code = 'CTREC-21';

UPDATE client_account SET name = 'Dragon Security Proprietary'
 WHERE bo_id = '1201010000003';

SELECT id, trec_code, name, status FROM broker;
