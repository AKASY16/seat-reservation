CREATE TABLE performance
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(200) NOT NULL,
    starts_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- 좌석의 현재 상태를 이 테이블 한 곳에서 관리한다.
-- 이중 판매 방지는 이 테이블의 조건부 UPDATE가 맡는다.
CREATE TABLE seat
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    performance_id BIGINT      NOT NULL,
    seat_no        VARCHAR(20) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    hold_id        VARCHAR(36) NULL,
    held_until     DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seat_performance_seat_no (performance_id, seat_no),
    KEY idx_seat_hold_id (hold_id),
    CONSTRAINT fk_seat_performance FOREIGN KEY (performance_id) REFERENCES performance (id)
) ENGINE = InnoDB;

CREATE TABLE seat_hold
(
    id              VARCHAR(36) NOT NULL,
    performance_id  BIGINT      NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64) NULL,
    expires_at      DATETIME(6) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 사용자가 같은 키로 다시 보내면 두 번째 INSERT가 여기서 막힌다.
    -- 키가 NULL인 요청끼리는 MySQL 유니크 제약에 걸리지 않는다.
    UNIQUE KEY uk_seat_hold_user_key (user_id, idempotency_key),
    KEY idx_seat_hold_status_expires (status, expires_at)
) ENGINE = InnoDB;

-- 좌석이 만료되거나 다른 선점으로 넘어가도 이 선점이 어떤 좌석을 잡았었는지 남겨 둔다.
-- 만료된 선점에 결제 승인이 늦게 도착했을 때 좌석을 다시 확보하는 데 쓴다.
CREATE TABLE seat_hold_item
(
    hold_id VARCHAR(36) NOT NULL,
    seat_id BIGINT      NOT NULL,
    PRIMARY KEY (hold_id, seat_id),
    CONSTRAINT fk_seat_hold_item_hold FOREIGN KEY (hold_id) REFERENCES seat_hold (id)
) ENGINE = InnoDB;

CREATE TABLE payment
(
    payment_id   VARCHAR(64) NOT NULL,
    hold_id      VARCHAR(36) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    approved_at  DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_id),
    KEY idx_payment_hold_id (hold_id),
    KEY idx_payment_status (status)
) ENGINE = InnoDB;

-- 처리한 결제 이벤트 기록. event_id가 PK라서 같은 이벤트는 두 번 저장되지 않는다.
-- 이벤트 처리와 같은 트랜잭션에서 INSERT하므로, 처리가 롤백되면 이 기록도 함께 사라져 재전송 때 다시 처리된다.
CREATE TABLE payment_event
(
    event_id    VARCHAR(64) NOT NULL,
    payment_id  VARCHAR(64) NOT NULL,
    hold_id     VARCHAR(36) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    result      VARCHAR(30) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_payment_event_payment_id (payment_id)
) ENGINE = InnoDB;
