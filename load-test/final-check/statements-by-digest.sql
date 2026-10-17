-- 같은 형태의 쿼리별 횟수와 시간. 밀리초 단위다.
-- 계정을 가리지 않으므로 수집 경계의 TRUNCATE 문 같은 관측 쿼리도 섞인다.
SELECT SCHEMA_NAME,
       COUNT_STAR,
       ROUND(SUM_TIMER_WAIT / 1000000000, 3) AS total_ms,
       ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_ms,
       ROUND(MAX_TIMER_WAIT / 1000000000, 3) AS max_ms,
       ROUND(SUM_LOCK_TIME / 1000000000, 3)  AS lock_ms,
       SUM_ROWS_AFFECTED,
       SUM_ROWS_SENT,
       SUM_ROWS_EXAMINED,
       SUM_ERRORS,
       DIGEST_TEXT
FROM performance_schema.events_statements_summary_by_digest
ORDER BY SUM_TIMER_WAIT DESC;
