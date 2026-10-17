-- 측정 환경 기록용 MySQL 버전과 주요 설정
SELECT VERSION() AS version;
SELECT VARIABLE_NAME, VARIABLE_VALUE
FROM performance_schema.global_variables
WHERE VARIABLE_NAME IN ('innodb_buffer_pool_size', 'innodb_flush_log_at_trx_commit', 'sync_binlog',
                        'transaction_isolation', 'max_connections', 'performance_schema',
                        'character_set_server', 'collation_server', 'innodb_lock_wait_timeout')
ORDER BY VARIABLE_NAME;
