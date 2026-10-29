-- =====================================================================
-- V4 index benchmark: the three measurements.
--
--   bench_chart(runs)        the console's settlement chart, 24h / 15m,
--                            platform-wide (no tenant filter) — the exact
--                            shape of ReportMapper.settlementBuckets
--   bench_settle(start, n)   n point updates by primary key in one
--                            transaction: the settle path's write, without
--                            commit-fsync noise, so what is left is row and
--                            index maintenance
--   bench_insert(start, n)   n single-row inserts in one transaction: the
--                            ingest fan-out's write
--
-- One transaction for the write loops is deliberate: fsync per commit would
-- add the same constant to both arms and bury the difference we are looking
-- for. The cost of "how many commits" is measured elsewhere (docs/performance.md
-- §20), not here.
-- =====================================================================

DROP PROCEDURE IF EXISTS bench_chart;
DELIMITER //
CREATE PROCEDURE bench_chart(IN runs INT)
BEGIN
    DECLARE i    INT DEFAULT 0;
    DECLARE t0   DATETIME(6);
    DECLARE sink BIGINT DEFAULT 0;

    SET t0 = NOW(6);
    WHILE i < runs DO
        SET sink = (SELECT COUNT(*) FROM (
            WITH RECURSIVE grid AS (
                SELECT FROM_UNIXTIME(
                           FLOOR(UNIX_TIMESTAMP(NOW(3) - INTERVAL 24 HOUR) / 900) * 900
                       ) AS bucketStart
                UNION ALL
                SELECT bucketStart + INTERVAL 900 SECOND
                  FROM grid
                 WHERE bucketStart + INTERVAL 900 SECOND < NOW(3)
            ),
            agg AS (
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(d.updated_at) / 900) * 900) AS bucketStart,
                       SUM(d.status = 'SUCCEEDED') AS succeeded,
                       SUM(d.status = 'DEAD')      AS dead,
                       SUM(d.status = 'CANCELED')  AS canceled
                  FROM delivery d
                 WHERE d.updated_at >= NOW(3) - INTERVAL 24 HOUR
                   AND d.updated_at < NOW(3)
                   AND d.status IN ('SUCCEEDED', 'DEAD', 'CANCELED')
                 GROUP BY bucketStart
            )
            SELECT g.bucketStart,
                   COALESCE(a.succeeded, 0) AS succeeded,
                   COALESCE(a.dead, 0)      AS dead,
                   COALESCE(a.canceled, 0)  AS canceled
              FROM grid g
              LEFT JOIN agg a ON a.bucketStart = g.bucketStart
        ) x);
        SET i = i + 1;
    END WHILE;

    SELECT 'chart' AS bench,
           runs AS runs,
           sink AS buckets_touched,
           TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) AS total_us,
           ROUND(TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / runs) AS avg_us;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS bench_settle;
DELIMITER //
CREATE PROCEDURE bench_settle(IN start_id BIGINT, IN n INT)
BEGIN
    DECLARE i  INT DEFAULT 0;
    DECLARE t0 DATETIME(6);

    SET t0 = NOW(6);
    START TRANSACTION;
    WHILE i < n DO
        UPDATE delivery_write
           SET status = 'SUCCEEDED', last_status_code = 200, updated_at = NOW(3)
         WHERE id = start_id + i;
        SET i = i + 1;
    END WHILE;
    COMMIT;

    SELECT 'settle' AS bench,
           n AS rows_touched,
           TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) AS total_us,
           ROUND(n / (TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / 1000000)) AS rows_per_s;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS bench_insert;
DELIMITER //
CREATE PROCEDURE bench_insert(IN start_event BIGINT, IN n INT)
BEGIN
    DECLARE i  INT DEFAULT 0;
    DECLARE t0 DATETIME(6);

    SET t0 = NOW(6);
    START TRANSACTION;
    WHILE i < n DO
        INSERT INTO delivery_write (tenant_id, event_id, endpoint_id, status, attempt_count,
                                    max_attempts, created_at, updated_at)
        VALUES (1, start_event + i, (i % 20) + 1, 'PENDING', 0, 8, NOW(3), NOW(3));
        SET i = i + 1;
    END WHILE;
    COMMIT;

    SELECT 'insert' AS bench,
           n AS rows_touched,
           TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) AS total_us,
           ROUND(n / (TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / 1000000)) AS rows_per_s;
END //
DELIMITER ;
