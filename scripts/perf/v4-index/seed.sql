-- =====================================================================
-- V4 index benchmark: dataset.
--
-- 1 tenant, 20 endpoints, 150 000 events, 1 000 000 deliveries spread over
-- the last 7 days (so ~1/7 of them fall inside the chart's 24h window), plus
-- a 200 000-row `delivery_write` for the settle/insert benchmarks.
--
-- Status mix: 80% SUCCEEDED, 8% DEAD, 4% CANCELED, 4% PENDING, 4% RETRY_WAIT.
-- That is not a real tenant's mix; it is deliberate — 92% settled means the
-- chart has to aggregate most of the window, which is the case the index is
-- for. A quiet tenant would make both arms look fast and prove nothing.
-- =====================================================================

-- Numbers 0..1 048 575 by doubling; no recursive CTE, no 1M-row VALUES list.
CREATE TABLE seq (n INT PRIMARY KEY) ENGINE = InnoDB;
INSERT INTO seq VALUES (0);
INSERT INTO seq SELECT n + 1 FROM seq;
INSERT INTO seq SELECT n + 2 FROM seq;
INSERT INTO seq SELECT n + 4 FROM seq;
INSERT INTO seq SELECT n + 8 FROM seq;
INSERT INTO seq SELECT n + 16 FROM seq;
INSERT INTO seq SELECT n + 32 FROM seq;
INSERT INTO seq SELECT n + 64 FROM seq;
INSERT INTO seq SELECT n + 128 FROM seq;
INSERT INTO seq SELECT n + 256 FROM seq;
INSERT INTO seq SELECT n + 512 FROM seq;
INSERT INTO seq SELECT n + 1024 FROM seq;
INSERT INTO seq SELECT n + 2048 FROM seq;
INSERT INTO seq SELECT n + 4096 FROM seq;
INSERT INTO seq SELECT n + 8192 FROM seq;
INSERT INTO seq SELECT n + 16384 FROM seq;
INSERT INTO seq SELECT n + 32768 FROM seq;
INSERT INTO seq SELECT n + 65536 FROM seq;
INSERT INTO seq SELECT n + 131072 FROM seq;
INSERT INTO seq SELECT n + 262144 FROM seq;
INSERT INTO seq SELECT n + 524288 FROM seq;

INSERT INTO tenant (id, name, rate_limit) VALUES (1, 'bench', 0);

INSERT INTO endpoint (id, tenant_id, url, secret_cipher, subscribed_types, max_attempts)
SELECT n + 1, 1, CONCAT('https://bench.example/hook/', n + 1), UNHEX('00'), '*', 8
  FROM seq WHERE n < 20;

-- 150 000 events: 1..50 000 are fanned out below, 50 001..150 000 are the
-- target of the insert benchmark.
INSERT INTO event (id, tenant_id, event_type, payload, idempotency_key)
SELECT n + 1, 1, 'order.paid', JSON_OBJECT('amount', n % 100), CONCAT('bench-', n)
  FROM seq WHERE n < 150000;

-- One delivery per (event, endpoint): event = n % 50 000 + 1,
-- endpoint = n / 50 000 + 1 keeps the pair unique.
INSERT INTO delivery (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts,
                      next_attempt_at, last_error_class, last_status_code, created_at, updated_at)
SELECT 1,
       (n % 50000) + 1,
       FLOOR(n / 50000) + 1,
       CASE
           WHEN n % 100 < 80 THEN 'SUCCEEDED'
           WHEN n % 100 < 88 THEN 'DEAD'
           WHEN n % 100 < 92 THEN 'CANCELED'
           WHEN n % 100 < 96 THEN 'PENDING'
           ELSE 'RETRY_WAIT'
       END,
       CASE WHEN n % 100 < 92 THEN 8 ELSE 0 END,
       8,
       NULL,
       CASE WHEN n % 100 >= 80 AND n % 100 < 88 THEN 'SERVER_5XX' END,
       CASE WHEN n % 100 >= 80 AND n % 100 < 88 THEN 500 END,
       NOW(3) - INTERVAL (n % 604800) SECOND - INTERVAL 30 SECOND,
       NOW(3) - INTERVAL (n % 604800) SECOND
  FROM seq WHERE n < 1000000;

-- Written by the settle benchmark. DEAD rows, so the update below is a real
-- state change (SUCCEEDED + updated_at rewrite) rather than a no-op.
INSERT INTO delivery_write (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts,
                            last_error_class, last_status_code, created_at, updated_at)
SELECT 1,
       (n % 50000) + 1,
       FLOOR(n / 50000) + 1,
       'DEAD', 8, 8, 'SERVER_5XX', 500,
       NOW(3) - INTERVAL 3600 SECOND,
       NOW(3) - INTERVAL (n % 3600) SECOND
  FROM seq WHERE n < 200000;

ANALYZE TABLE delivery, delivery_write;

SELECT 'delivery'            AS table_name, COUNT(*) AS rows_24h_share FROM delivery
 WHERE updated_at >= NOW(3) - INTERVAL 24 HOUR AND status IN ('SUCCEEDED','DEAD','CANCELED')
UNION ALL
SELECT 'delivery_total', COUNT(*) FROM delivery
UNION ALL
SELECT 'delivery_write', COUNT(*) FROM delivery_write;
