-- 부하 시험을 다시 돌리기 전에 선점과 결제 기록을 지우고 좌석을 모두 빈 좌석으로 되돌린다.
DELETE FROM payment_event;
DELETE FROM payment;
DELETE FROM seat_hold_item;
DELETE FROM seat_hold;
UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL;
