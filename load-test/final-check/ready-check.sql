-- 실행 전 상태 확인. 좌석 1,000개가 모두 빈 좌석이고 선점·결제·이벤트 기록이 하나도 없어야 한다.
SELECT (SELECT COUNT(*) FROM seat) AS seats,
       (SELECT COUNT(*)
        FROM seat
        WHERE performance_id = 1
          AND status = 'AVAILABLE'
          AND hold_id IS NULL
          AND held_until IS NULL) AS available_seats,
       (SELECT COUNT(*) FROM seat_hold) AS holds,
       (SELECT COUNT(*) FROM seat_hold_item) AS hold_items,
       (SELECT COUNT(*) FROM payment) AS payments,
       (SELECT COUNT(*) FROM payment_event) AS payment_events,
       (SELECT COUNT(*) FROM unmatched_payment_event) AS unmatched_payment_events;
