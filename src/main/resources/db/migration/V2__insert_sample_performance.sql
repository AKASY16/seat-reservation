-- 샘플 공연 1개와 좌석 1,000석 (A~T열, 열마다 50석).
-- 좌석 id는 A-1부터 순서대로 1~1000이 된다. 부하 시험의 인기 구간은 앞 두 열(A, B열 100석)이다.
INSERT INTO performance (id, title, starts_at)
VALUES (1, '샘플 공연', '2026-12-24 11:00:00');

INSERT INTO seat (performance_id, seat_no, status)
WITH RECURSIVE seq (n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 999
)
SELECT 1,
       CONCAT(CHAR(65 + n DIV 50), '-', n MOD 50 + 1),
       'AVAILABLE'
FROM seq
ORDER BY n;
