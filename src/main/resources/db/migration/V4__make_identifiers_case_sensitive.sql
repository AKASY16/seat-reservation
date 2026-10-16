-- 외부에서 받는 식별자를 Java 쪽 비교(equals)와 같은 기준으로, 대소문자와 끝 공백까지 그대로 비교한다.
-- MySQL 기본 비교 규칙(utf8mb4_0900_ai_ci, docker-compose의 utf8mb4_unicode_ci)은 대소문자를 구분하지 않는다.
-- 그대로 두면 'evt-A'와 'evt-a'를 같은 이벤트로, 'Alice'와 'alice'를 같은 사용자로 보고
-- 중복 검사, 유니크 제약, 조회가 다른 행에 걸린다.
-- utf8mb4_bin은 대소문자는 구분하지만 끝 공백을 무시해서(PAD SPACE) 'evt-a'와 'evt-a '를 같게 본다.
-- 그래서 끝 공백도 구분하는(NO PAD) utf8mb4_0900_bin으로 식별자 컬럼만 바꾼다.

-- 외래 키가 걸린 두 컬럼은 비교 규칙이 같아야 하므로, 키를 잠시 풀었다가 다시 건다.
ALTER TABLE seat_hold_item DROP FOREIGN KEY fk_seat_hold_item_hold;

ALTER TABLE seat_hold
    MODIFY id              VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY user_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;

ALTER TABLE seat_hold_item
    MODIFY hold_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT fk_seat_hold_item_hold FOREIGN KEY (hold_id) REFERENCES seat_hold (id);

ALTER TABLE seat
    MODIFY hold_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;

ALTER TABLE payment
    MODIFY payment_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY hold_id    VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE payment_event
    MODIFY event_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY payment_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY hold_id    VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE unmatched_payment_event
    MODIFY event_id   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY payment_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY hold_id    VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
