-- 애플리케이션 계정(app)이 실행한 문장 종류별 횟수와 시간. 밀리초 단위다.
-- HOST는 접속한 서버 컨테이너의 주소다(서버별 주소는 구성 폴더의 servers.txt).
-- 만료 스케줄러도 같은 계정을 쓰므로 함께 잡힌다.
SELECT HOST,
       EVENT_NAME,
       COUNT_STAR,
       ROUND(SUM_TIMER_WAIT / 1000000000, 3) AS total_ms,
       ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_ms,
       ROUND(MAX_TIMER_WAIT / 1000000000, 3) AS max_ms,
       ROUND(SUM_LOCK_TIME / 1000000000, 3)  AS lock_ms,
       SUM_ROWS_AFFECTED,
       SUM_ROWS_SENT,
       SUM_ROWS_EXAMINED,
       SUM_ERRORS
FROM performance_schema.events_statements_summary_by_account_by_event_name
WHERE USER = 'app'
  AND COUNT_STAR > 0
ORDER BY HOST, SUM_TIMER_WAIT DESC;
