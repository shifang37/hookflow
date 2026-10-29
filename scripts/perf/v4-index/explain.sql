-- The settlement chart's plan, with actual timings. Run once per arm.
EXPLAIN ANALYZE
SELECT COUNT(*) FROM (
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
    SELECT g.bucketStart
      FROM grid g
      LEFT JOIN agg a ON a.bucketStart = g.bucketStart
) x;
