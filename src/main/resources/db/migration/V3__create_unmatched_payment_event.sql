-- 선점을 찾지 못했거나 이미 다른 선점에 연결된 결제로 와서 422로 거절한 결제 이벤트.
-- 승인 이벤트라면 돈은 받았는데 판매로 이어지지 않은 결제이므로, 운영자가 확인하고 환불할 수 있게 남겨 둔다.
-- 제공자가 같은 이벤트를 다시 보내도 event_id가 PK라서 한 번만 남는다.
CREATE TABLE unmatched_payment_event
(
    event_id    VARCHAR(64) NOT NULL,
    payment_id  VARCHAR(64) NOT NULL,
    hold_id     VARCHAR(36) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    reason      VARCHAR(30) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_unmatched_payment_event_payment_id (payment_id)
) ENGINE = InnoDB;
