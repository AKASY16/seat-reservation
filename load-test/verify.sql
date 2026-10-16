-- 부하 시험이 끝난 뒤 정합성을 확인한다. 세 쿼리 모두 0이 나와야 한다.

-- 1. 같은 좌석에 만료되지 않은 선점이 둘 이상 걸려 있는 좌석 수
SELECT COUNT(*) AS seats_with_multiple_active_holds
FROM (SELECT i.seat_id
      FROM seat_hold h
               JOIN seat_hold_item i ON i.hold_id = h.id
      WHERE h.status = 'ACTIVE'
        AND h.expires_at > UTC_TIMESTAMP(6)
      GROUP BY i.seat_id
      HAVING COUNT(*) > 1) t;

-- 2. 유효한 선점인데 자기 좌석 중 일부를 갖고 있지 않은 선점 수 (전부 또는 전무가 깨진 경우)
SELECT COUNT(DISTINCT h.id) AS partially_held_holds
FROM seat_hold h
         JOIN seat_hold_item i ON i.hold_id = h.id
         JOIN seat s ON s.id = i.seat_id
WHERE h.status = 'ACTIVE'
  AND h.expires_at > UTC_TIMESTAMP(6)
  AND (s.status <> 'HELD' OR s.hold_id <> h.id);

-- 3. HELD 상태인데 유효한 선점에 속하지 않은 좌석 수
SELECT COUNT(*) AS orphan_held_seats
FROM seat s
         LEFT JOIN seat_hold h ON h.id = s.hold_id AND h.status = 'ACTIVE'
WHERE s.status = 'HELD'
  AND h.id IS NULL;

-- 참고: 선점된 좌석 수와 성공한 선점 수
SELECT (SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND status = 'HELD') AS held_seats,
       (SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND status = 'HELD' AND id <= 100) AS held_popular_seats,
       (SELECT COUNT(*) FROM seat_hold WHERE performance_id = 1) AS holds;
