-- DB 문장 통계의 수집 경계. 정식 측정 직전에 비운다.
-- 이 문장들과 경계 이후의 관측 쿼리, 만료 스케줄러의 조회도 통계에 함께 잡힐 수 있다.
TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
TRUNCATE TABLE performance_schema.events_statements_summary_by_account_by_event_name;
