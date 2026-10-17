-- 행 잠금 대기와 데드락 카운터. 측정 직전과 직후에 읽어서 차이를 본다.
-- Innodb_row_lock_time은 밀리초 단위다.
SELECT VARIABLE_NAME, VARIABLE_VALUE
FROM performance_schema.global_status
WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_row_lock_current_waits');

-- STATUS가 enabled가 아니면 값이 늘지 않으므로 미측정으로 본다.
SELECT NAME, COUNT, STATUS
FROM information_schema.INNODB_METRICS
WHERE NAME IN ('lock_deadlocks', 'lock_timeouts', 'lock_row_lock_waits');
