-- Fresh DEAD rows for the write benchmarks. Called between measurements so the
-- 50k updates are always real state changes, and before each arm so both arms
-- start from the same 200k rows.
TRUNCATE TABLE delivery_write;

INSERT INTO delivery_write (tenant_id, event_id, endpoint_id, status, attempt_count, max_attempts,
                            last_error_class, last_status_code, created_at, updated_at)
SELECT 1, (n % 50000) + 1, FLOOR(n / 50000) + 1, 'DEAD', 8, 8, 'SERVER_5XX', 500,
       NOW(3) - INTERVAL 3600 SECOND, NOW(3) - INTERVAL (n % 3600) SECOND
  FROM seq WHERE n < 200000;
